//! Writing a response body to a file, and reading it as a tokio `AsyncRead`.

use std::io;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::task::{Context, Poll};

use bytes::{Buf, Bytes};
use futures_core::Stream;
use futures_util::StreamExt;
use tokio::io::{AsyncRead, AsyncWriteExt, ReadBuf};

use crate::Error;

type Chunks = Pin<Box<dyn Stream<Item = Result<Bytes, Error>> + Send>>;

/// Writes `body` to a temporary file next to `path`, syncs it and renames it to `path`.
pub(crate) async fn save_to(
    body: impl Stream<Item = Result<Bytes, Error>> + Send + 'static,
    path: &Path,
) -> Result<u64, Error> {
    let temporary = temporary_path(path);
    let mut file = tokio::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temporary)
        .await
        .map_err(|error| Error::io(with_path(error, &temporary)))?;
    let written = async {
        let mut body = std::pin::pin!(body);
        let mut written = 0u64;
        while let Some(chunk) = body.next().await {
            let chunk = chunk?;
            file.write_all(&chunk).await.map_err(Error::io)?;
            written += chunk.len() as u64;
        }
        file.flush().await.map_err(Error::io)?;
        file.sync_all().await.map_err(Error::io)?;
        Ok::<_, Error>(written)
    }
    .await;
    drop(file);
    let written = match written {
        Ok(written) => written,
        Err(error) => {
            let _ = tokio::fs::remove_file(&temporary).await;
            return Err(error);
        }
    };
    if let Err(error) = tokio::fs::rename(&temporary, path).await {
        let _ = tokio::fs::remove_file(&temporary).await;
        return Err(Error::io(with_path(error, path)));
    }
    #[cfg(unix)]
    if let Ok(directory) = tokio::fs::File::open(directory_of(path)).await {
        // Best effort: makes the rename itself durable.
        let _ = directory.sync_all().await;
    }
    Ok(written)
}

fn directory_of(path: &Path) -> &Path {
    match path.parent() {
        Some(parent) if !parent.as_os_str().is_empty() => parent,
        _ => Path::new("."),
    }
}

/// `.<name>.<random>.part` in the directory of `path`.
fn temporary_path(path: &Path) -> PathBuf {
    let name = path
        .file_name()
        .map(|name| name.to_string_lossy().into_owned())
        .unwrap_or_else(|| "download".to_owned());
    directory_of(path).join(format!(
        ".{name}.{:016x}.part",
        crate::multipart::random_u64(0)
    ))
}

fn with_path(error: io::Error, path: &Path) -> io::Error {
    io::Error::new(error.kind(), format!("{}: {error}", path.display()))
}

/// A stream of chunks read as an `AsyncRead`.
pub(crate) struct BodyReader {
    chunks: Option<Chunks>,
    chunk: Bytes,
}

impl BodyReader {
    pub(crate) fn new(chunks: impl Stream<Item = Result<Bytes, Error>> + Send + 'static) -> Self {
        Self {
            chunks: Some(Box::pin(chunks)),
            chunk: Bytes::new(),
        }
    }
}

impl AsyncRead for BodyReader {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let this = &mut *self;
        while this.chunk.is_empty() {
            let Some(chunks) = this.chunks.as_mut() else {
                return Poll::Ready(Ok(()));
            };
            match chunks.as_mut().poll_next(cx) {
                Poll::Pending => return Poll::Pending,
                Poll::Ready(Some(Ok(chunk))) => this.chunk = chunk,
                Poll::Ready(Some(Err(error))) => {
                    this.chunks = None;
                    return Poll::Ready(Err(io::Error::other(error)));
                }
                Poll::Ready(None) => this.chunks = None,
            }
        }
        let n = this.chunk.len().min(buf.remaining());
        buf.put_slice(&this.chunk[..n]);
        this.chunk.advance(n);
        Poll::Ready(Ok(()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures_util::stream;
    use tokio::io::AsyncReadExt;

    fn body(
        chunks: Vec<Result<&'static str, &'static str>>,
    ) -> impl Stream<Item = Result<Bytes, Error>> + Send {
        stream::iter(chunks.into_iter().map(|chunk| {
            chunk
                .map(|text| Bytes::from_static(text.as_bytes()))
                .map_err(Error::body)
        }))
    }

    fn entries(dir: &Path) -> Vec<String> {
        let mut names: Vec<_> = std::fs::read_dir(dir)
            .unwrap()
            .map(|entry| entry.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        names.sort();
        names
    }

    #[tokio::test]
    async fn saves_the_whole_body() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("out.bin");
        std::fs::write(&path, "old").unwrap();
        let written = save_to(body(vec![Ok("ab"), Ok("cd")]), &path)
            .await
            .unwrap();
        assert_eq!(written, 4);
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "abcd");
        assert_eq!(entries(dir.path()), ["out.bin"]);
    }

    #[tokio::test]
    async fn a_failed_body_leaves_the_file_as_it_was() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("out.bin");
        std::fs::write(&path, "old").unwrap();
        let error = save_to(body(vec![Ok("ab"), Err("connection reset")]), &path)
            .await
            .unwrap_err();
        assert!(error.is_body(), "{error:?}");
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "old");
        assert_eq!(entries(dir.path()), ["out.bin"]);

        let fresh = dir.path().join("fresh.bin");
        assert!(save_to(body(vec![Err("reset")]), &fresh).await.is_err());
        assert_eq!(entries(dir.path()), ["out.bin"]);
    }

    #[tokio::test]
    async fn a_missing_directory_is_an_io_error() {
        let dir = tempfile::tempdir().unwrap();
        let error = save_to(body(vec![Ok("ab")]), &dir.path().join("no/such/out.bin"))
            .await
            .unwrap_err();
        assert!(error.is_io(), "{error:?}");
    }

    #[tokio::test]
    async fn reads_the_body_in_small_reads() {
        let mut reader = BodyReader::new(body(vec![Ok("hello "), Ok(""), Ok("world")]));
        let mut small = [0u8; 4];
        assert_eq!(reader.read(&mut small).await.unwrap(), 4);
        assert_eq!(&small, b"hell");
        let mut rest = String::new();
        reader.read_to_string(&mut rest).await.unwrap();
        assert_eq!(rest, "o world");

        let mut failing = BodyReader::new(body(vec![Ok("a"), Err("reset")]));
        let mut all = Vec::new();
        let error = failing.read_to_end(&mut all).await.unwrap_err();
        assert!(error
            .get_ref()
            .unwrap()
            .downcast_ref::<Error>()
            .unwrap()
            .is_body());
    }
}
