use std::borrow::Cow;
use std::fmt;
use std::ops::{Bound, RangeBounds};
use std::pin::Pin;
use std::sync::{Arc, Mutex, PoisonError};

use bytes::{Bytes, BytesMut};
use futures_core::Stream;
use futures_util::{future, stream, StreamExt, TryStreamExt};

use crate::{Body, BoxError, Error};

type Chunks = Pin<Box<dyn Stream<Item = Result<Bytes, BoxError>> + Send>>;
type Once = Arc<Mutex<Option<Chunks>>>;
type Factory = Arc<dyn Fn() -> Chunks + Send + Sync>;

/// Something to upload: a file, bytes, a reader or a stream, with a file name and a media type.
///
/// An `Upload` becomes a request body (`RequestBuilder::body(upload)`) or a multipart part
/// ([`Part::upload`](crate::multipart::Part::upload)). Generated clients take one for every file parameter, and a
/// `PathBuf`, `&Path`, `Vec<u8>` or `Bytes` converts into one.
///
/// | Source | Length | Retried |
/// |---|---|---|
/// | [`Upload::bytes`] (`Bytes`, `Vec<u8>`, `&'static [u8]`) | known | yes, without a copy |
/// | [`Upload::path`], [`Upload::file`] | from the file's metadata | yes: the file is opened again |
/// | [`Upload::from_fn`] | [`Upload::len`] | yes: the function makes a fresh stream |
/// | [`Upload::reader`], [`Upload::stream`] | [`Upload::len`] | no, unless [`Upload::buffer_for_retry`] holds it all |
///
/// When the length is known, the body (or the whole multipart form) is sent with a `Content-Length`, not chunked.
/// A path's length is read by [`Upload::resolve`], which generated clients call; [`Upload::file`] is
/// `Upload::path(path).resolve()`.
///
/// ```no_run
/// # #[cfg(feature = "fs")]
/// # async fn demo() -> Result<(), timvw_rust_runtime::Error> {
/// use timvw_rust_runtime::Upload;
///
/// // Streamed from disk, named `photo.png`, `image/png` guessed from the extension.
/// let file = Upload::file("data/photo.png").await?;
/// // Bytes held in memory, with a name and a media type of your own.
/// let image = Upload::bytes(vec![0x89, b'P', b'N', b'G']).file_name("cat.png").mime("image/png");
/// // The second 5 MiB of a file, for an API that takes a large file in parts.
/// let part = Upload::path("data/big.bin").slice(5 << 20..10 << 20).resolve().await?;
/// # Ok(())
/// # }
/// ```
#[derive(Clone)]
pub struct Upload {
    source: Source,
    pub(crate) file_name: Option<Cow<'static, str>>,
    pub(crate) mime: Option<Cow<'static, str>>,
    /// The length of the whole source, before `start`/`end`, when known.
    total: Option<u64>,
    start: u64,
    end: Option<u64>,
    buffer_limit: Option<usize>,
}

#[derive(Clone)]
enum Source {
    Bytes(Bytes),
    #[cfg(feature = "fs")]
    File(Arc<std::path::Path>),
    Once(Once),
    Factory(Factory),
}

impl Upload {
    /// Bytes held in memory: their length is known, and a retry sends them again without a copy.
    pub fn bytes(bytes: impl Into<Bytes>) -> Self {
        let bytes = bytes.into();
        let total = Some(bytes.len() as u64);
        Self::new(Source::Bytes(bytes), total)
    }

    /// A stream of chunks, read once: a retry cannot send it again unless [`Upload::buffer_for_retry`] holds it.
    /// Its length is unknown (the body is chunked) unless set with [`Upload::len`].
    pub fn stream<S, B, E>(stream: S) -> Self
    where
        S: Stream<Item = Result<B, E>> + Send + 'static,
        B: Into<Bytes> + 'static,
        E: Into<BoxError> + 'static,
    {
        let chunks: Chunks = Box::pin(stream.map_ok(Into::into).map_err(Into::into));
        Self::new(Source::Once(Arc::new(Mutex::new(Some(chunks)))), None)
    }

    /// A stream that `make` starts afresh for every attempt, so a retry sends it again. Its length is unknown (the
    /// body is chunked) unless set with [`Upload::len`]; every stream `make` returns must then have that length.
    pub fn from_fn<F, S, B, E>(make: F) -> Self
    where
        F: Fn() -> S + Send + Sync + 'static,
        S: Stream<Item = Result<B, E>> + Send + 'static,
        B: Into<Bytes> + 'static,
        E: Into<BoxError> + 'static,
    {
        let factory: Factory =
            Arc::new(move || Box::pin(make().map_ok(Into::into).map_err(Into::into)));
        Self::new(Source::Factory(factory), None)
    }

    fn new(source: Source, total: Option<u64>) -> Self {
        Self {
            source,
            file_name: None,
            mime: None,
            total,
            start: 0,
            end: None,
            buffer_limit: None,
        }
    }

    /// Sets the file name (multipart `filename`). A path's is its last component; a multipart part of an upload
    /// without one is named `file`.
    pub fn file_name(mut self, file_name: impl Into<Cow<'static, str>>) -> Self {
        self.file_name = Some(file_name.into());
        self
    }

    /// Sets the media type (the `Content-Type` of a multipart part), such as `image/png`. A path's is guessed from its
    /// extension. A value that is not a valid header value is sent as `application/octet-stream`.
    pub fn mime(mut self, mime: impl Into<Cow<'static, str>>) -> Self {
        self.mime = Some(mime.into());
        self
    }

    /// Sets the length of the source in bytes, for a stream or a reader: the body is then sent with a
    /// `Content-Length`, and the stream must have exactly that many bytes.
    pub fn len(mut self, len: u64) -> Self {
        self.total = Some(len);
        self
    }

    /// Only the bytes in `range` of the source (of the current slice, when sliced again): a file is read from the
    /// start of the range (a seek, no copy), bytes are sliced without a copy, a stream skips to it. For APIs that take
    /// a large file in parts.
    pub fn slice(mut self, range: impl RangeBounds<u64>) -> Self {
        let from = match range.start_bound() {
            Bound::Included(&start) => start,
            Bound::Excluded(&start) => start.saturating_add(1),
            Bound::Unbounded => 0,
        };
        let to = match range.end_bound() {
            Bound::Included(&end) => Some(end.saturating_add(1)),
            Bound::Excluded(&end) => Some(end),
            Bound::Unbounded => None,
        };
        let start = self.start.saturating_add(from);
        let end = to.map(|to| self.start.saturating_add(to));
        self.end = match (self.end, end) {
            (Some(old), Some(new)) => Some(old.min(new)),
            (old, new) => old.or(new),
        };
        self.start = match self.end {
            Some(end) => start.min(end),
            None => start,
        };
        self
    }

    /// Lets a retry send a stream or a reader again: [`Upload::resolve`] reads it into memory, up to `limit` bytes.
    /// A stream that ends within the limit becomes bytes (replayable, with a known length); a longer one is sent once,
    /// as if this were not set. Without `resolve` (which generated clients call) this has no effect.
    pub fn buffer_for_retry(mut self, limit: usize) -> Self {
        self.buffer_limit = Some(limit);
        self
    }

    /// The number of bytes the upload sends, when known: bytes, a resolved path, or a stream given a
    /// [`len`](Upload::len).
    pub fn content_length(&self) -> Option<u64> {
        self.total.map(|total| {
            let end = self.end.unwrap_or(total).min(total);
            end.saturating_sub(self.start.min(total))
        })
    }

    /// Whether a retry can send the upload again: bytes, a path and [`Upload::from_fn`] can, a stream or a reader
    /// cannot (unless [`Upload::buffer_for_retry`] held it all when it was resolved).
    pub fn is_replayable(&self) -> bool {
        !matches!(self.source, Source::Once(_))
    }

    /// Gets the upload ready to send: reads a path's length from its metadata (an error when the file does not
    /// exist), and reads a stream with [`Upload::buffer_for_retry`] into memory. Other uploads are returned as they
    /// are.
    pub async fn resolve(mut self) -> Result<Self, Error> {
        #[cfg(feature = "fs")]
        if let Source::File(path) = &self.source {
            if self.total.is_none() {
                let metadata = tokio::fs::metadata(&**path)
                    .await
                    .map_err(|error| Error::io(with_path(error, path)))?;
                if !metadata.is_file() {
                    return Err(Error::io(std::io::Error::other(format!(
                        "{}: not a file",
                        path.display()
                    ))));
                }
                self.total = Some(metadata.len());
            }
            return Ok(self);
        }
        let Some(limit) = self.buffer_limit else {
            return Ok(self);
        };
        let Source::Once(once) = &self.source else {
            return Ok(self);
        };
        let taken = once.lock().unwrap_or_else(PoisonError::into_inner).take();
        let Some(mut chunks) = taken else {
            return Ok(self);
        };
        let mut buffer = BytesMut::new();
        while let Some(chunk) = chunks.next().await {
            let chunk = chunk.map_err(Error::io)?;
            if buffer.len() + chunk.len() > limit {
                // Too long to hold: sent once, starting with what was read.
                let read = stream::iter([Ok(buffer.freeze()), Ok(chunk)]).chain(chunks);
                let chunks: Chunks = Box::pin(read);
                self.source = Source::Once(Arc::new(Mutex::new(Some(chunks))));
                return Ok(self);
            }
            buffer.extend_from_slice(&chunk);
        }
        self.total = Some(buffer.len() as u64);
        self.source = Source::Bytes(buffer.freeze());
        Ok(self)
    }
}

#[cfg(feature = "fs")]
impl Upload {
    /// A file, streamed from disk with tokio and opened again for each attempt, so a retry sends it again.
    ///
    /// The file name is the path's last component, the media type is guessed from its extension. Nothing is read
    /// until the request is sent; [`Upload::resolve`] (or [`Upload::file`]) reads the length, so the body is sent with
    /// a `Content-Length`, and fails early when the file does not exist.
    pub fn path(path: impl Into<std::path::PathBuf>) -> Self {
        let path: Arc<std::path::Path> = Arc::from(path.into());
        let file_name = path
            .file_name()
            .map(|name| Cow::Owned(name.to_string_lossy().into_owned()));
        let mime = mime_guess::from_path(&path)
            .first()
            .map(|mime| Cow::Owned(mime.essence_str().to_owned()));
        let mut upload = Self::new(Source::File(path), None);
        upload.file_name = file_name;
        upload.mime = mime;
        upload
    }

    /// [`Upload::path`], resolved: its length read from the file's metadata, an error when there is no such file.
    pub async fn file(path: impl Into<std::path::PathBuf>) -> Result<Self, Error> {
        Self::path(path).resolve().await
    }

    /// A tokio reader, read once in chunks: a retry cannot send it again unless [`Upload::buffer_for_retry`] holds it.
    /// Its length is unknown (the body is chunked) unless set with [`Upload::len`].
    pub fn reader<R>(reader: R) -> Self
    where
        R: tokio::io::AsyncRead + Send + 'static,
    {
        Self::stream(read_chunks(Box::pin(reader), None))
    }
}

/// The chunks of a reader, up to its end; fewer bytes than `expected` is an error.
#[cfg(feature = "fs")]
fn read_chunks<R>(
    reader: R,
    expected: Option<u64>,
) -> impl Stream<Item = std::io::Result<Bytes>> + Send + 'static
where
    R: tokio::io::AsyncRead + Send + Unpin + 'static,
{
    use tokio::io::AsyncReadExt;
    const CHUNK: usize = 64 * 1024;
    stream::try_unfold((reader, 0u64), move |(mut reader, read)| async move {
        let mut buffer = BytesMut::with_capacity(CHUNK);
        let n = reader.read_buf(&mut buffer).await?;
        if n == 0 {
            return match expected {
                Some(expected) if read < expected => Err(std::io::Error::new(
                    std::io::ErrorKind::UnexpectedEof,
                    format!("the upload ended after {read} of its {expected} bytes"),
                )),
                _ => Ok(None),
            };
        }
        Ok(Some((buffer.freeze(), (reader, read + n as u64))))
    })
}

#[cfg(feature = "fs")]
fn with_path(error: std::io::Error, path: &std::path::Path) -> std::io::Error {
    std::io::Error::new(error.kind(), format!("{}: {error}", path.display()))
}

/// The bytes of a file from `start` to `end`, `len` of them when known.
#[cfg(feature = "fs")]
fn file_chunks(
    path: Arc<std::path::Path>,
    start: u64,
    end: Option<u64>,
    len: Option<u64>,
) -> Chunks {
    use tokio::io::{AsyncReadExt, AsyncSeekExt};
    let open = async move {
        let mut file = tokio::fs::File::open(&*path)
            .await
            .map_err(|error| with_path(error, &path))?;
        if start > 0 {
            file.seek(std::io::SeekFrom::Start(start)).await?;
        }
        let limit = len
            .or_else(|| end.map(|end| end.saturating_sub(start)))
            .unwrap_or(u64::MAX);
        Ok::<_, std::io::Error>(read_chunks(file.take(limit), len))
    };
    Box::pin(stream::once(open).try_flatten().map_err(BoxError::from))
}

/// The bytes of a stream from `start` to `end`.
fn range(chunks: Chunks, start: u64, end: Option<u64>) -> Chunks {
    if start == 0 && end.is_none() {
        return chunks;
    }
    Box::pin(stream::unfold(
        (chunks, 0u64),
        move |(mut chunks, mut position)| async move {
            loop {
                if end.is_some_and(|end| position >= end) {
                    return None;
                }
                let chunk = match chunks.next().await? {
                    Ok(chunk) => chunk,
                    Err(error) => return Some((Err(error), (chunks, position))),
                };
                let first = position;
                position += chunk.len() as u64;
                let from = start.max(first);
                let to = end.map_or(position, |end| end.min(position));
                if from < to {
                    let slice = chunk.slice((from - first) as usize..(to - first) as usize);
                    return Some((Ok(slice), (chunks, position)));
                }
            }
        },
    ))
}

/// The stream of a one-shot source, taken when it is first read; an error when it was taken before.
fn take_once(once: Once) -> Chunks {
    let taken = stream::once(future::lazy(move |_| {
        once.lock().unwrap_or_else(PoisonError::into_inner).take()
    }));
    Box::pin(taken.flat_map(|chunks| match chunks {
        Some(chunks) => chunks,
        None => Box::pin(stream::once(future::ready(Err(BoxError::from(
            "this upload is a stream that was sent already: a stream is read once",
        ))))) as Chunks,
    }))
}

impl From<Upload> for Body {
    fn from(upload: Upload) -> Self {
        let len = upload.content_length();
        let (start, end) = (upload.start, upload.end);
        match upload.source {
            Source::Bytes(bytes) => {
                let total = bytes.len() as u64;
                let to = end.unwrap_or(total).min(total);
                let from = start.min(to);
                Body::from(bytes.slice(from as usize..to as usize))
            }
            #[cfg(feature = "fs")]
            Source::File(path) => Body::replay(
                Arc::new(move || {
                    Body::sized_stream(file_chunks(path.clone(), start, end, len), len)
                }),
                len,
            ),
            Source::Factory(make) => Body::replay(
                Arc::new(move || Body::sized_stream(range(make(), start, end), len)),
                len,
            ),
            Source::Once(once) => Body::sized_stream(range(take_once(once), start, end), len),
        }
    }
}

impl From<Bytes> for Upload {
    fn from(bytes: Bytes) -> Self {
        Self::bytes(bytes)
    }
}

impl From<Vec<u8>> for Upload {
    fn from(bytes: Vec<u8>) -> Self {
        Self::bytes(bytes)
    }
}

impl From<&'static [u8]> for Upload {
    fn from(bytes: &'static [u8]) -> Self {
        Self::bytes(Bytes::from_static(bytes))
    }
}

impl From<&Upload> for Upload {
    fn from(upload: &Upload) -> Self {
        upload.clone()
    }
}

#[cfg(feature = "fs")]
impl From<std::path::PathBuf> for Upload {
    fn from(path: std::path::PathBuf) -> Self {
        Self::path(path)
    }
}

#[cfg(feature = "fs")]
impl From<&std::path::PathBuf> for Upload {
    fn from(path: &std::path::PathBuf) -> Self {
        Self::path(path.clone())
    }
}

#[cfg(feature = "fs")]
impl From<&std::path::Path> for Upload {
    fn from(path: &std::path::Path) -> Self {
        Self::path(path)
    }
}

impl fmt::Debug for Upload {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let mut debug = f.debug_struct("Upload");
        match &self.source {
            Source::Bytes(bytes) => debug.field("bytes", &bytes.len()),
            #[cfg(feature = "fs")]
            Source::File(path) => debug.field("path", path),
            Source::Once(_) => debug.field("stream", &true),
            Source::Factory(_) => debug.field("from_fn", &true),
        };
        debug
            .field("file_name", &self.file_name)
            .field("mime", &self.mime)
            .field("content_length", &self.content_length())
            .finish_non_exhaustive()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    async fn read(upload: Upload) -> Bytes {
        Body::from(upload).collect_bytes().await.unwrap()
    }

    fn chunks(
        parts: &[&'static str],
    ) -> impl Stream<Item = Result<Bytes, std::io::Error>> + Send + 'static {
        stream::iter(
            parts
                .iter()
                .map(|part| Ok(Bytes::from_static(part.as_bytes())))
                .collect::<Vec<_>>(),
        )
    }

    #[tokio::test]
    async fn bytes_are_replayable_and_sliced_without_a_copy() {
        let upload = Upload::bytes("hello world").slice(6..);
        assert_eq!(upload.content_length(), Some(5));
        let body = Body::from(upload);
        assert_eq!(http_body::Body::size_hint(&body).exact(), Some(5));
        let copy = body.try_clone().unwrap();
        assert_eq!(body.collect_bytes().await.unwrap(), "world");
        assert_eq!(copy.collect_bytes().await.unwrap(), "world");
        assert_eq!(
            read(Upload::bytes("hello").slice(1..=2).slice(1..)).await,
            "l"
        );
        assert_eq!(read(Upload::bytes("hello").slice(10..)).await, "");
    }

    #[tokio::test]
    async fn a_stream_is_read_once() {
        let upload = Upload::stream(chunks(&["ab", "cd"]));
        assert!(!upload.is_replayable());
        let copy = upload.clone();
        let body = Body::from(upload);
        assert!(body.try_clone().is_none());
        assert_eq!(body.collect_bytes().await.unwrap(), "abcd");
        assert!(Body::from(copy).collect_bytes().await.is_err());
    }

    #[tokio::test]
    async fn a_stream_is_sliced_across_chunks() {
        assert_eq!(
            read(Upload::stream(chunks(&["ab", "cd", "ef"])).slice(1..5)).await,
            "bcde"
        );
        let sized = Upload::stream(chunks(&["ab", "cd"])).len(4).slice(1..);
        assert_eq!(sized.content_length(), Some(3));
        assert_eq!(
            http_body::Body::size_hint(&Body::from(sized)).exact(),
            Some(3)
        );
    }

    #[tokio::test]
    async fn from_fn_is_replayed() {
        let upload = Upload::from_fn(|| chunks(&["x", "y"])).len(2);
        assert!(upload.is_replayable());
        let body = Body::from(upload);
        let copy = body.try_clone().unwrap();
        assert_eq!(body.collect_bytes().await.unwrap(), "xy");
        assert_eq!(copy.collect_bytes().await.unwrap(), "xy");
    }

    #[tokio::test]
    async fn buffer_for_retry_holds_a_short_stream() {
        let short = Upload::stream(chunks(&["ab", "cd"]))
            .buffer_for_retry(4)
            .resolve()
            .await
            .unwrap();
        assert!(short.is_replayable());
        assert_eq!(short.content_length(), Some(4));
        assert_eq!(read(short).await, "abcd");

        let long = Upload::stream(chunks(&["ab", "cd", "e"]))
            .buffer_for_retry(4)
            .resolve()
            .await
            .unwrap();
        assert!(!long.is_replayable());
        assert_eq!(long.content_length(), None);
        assert_eq!(read(long).await, "abcde");
    }

    #[cfg(feature = "fs")]
    #[tokio::test]
    async fn a_file_is_resolved_reopened_and_sliced() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("notes.json");
        std::fs::write(&path, "0123456789").unwrap();

        let upload = Upload::path(&path);
        assert_eq!(upload.file_name.as_deref(), Some("notes.json"));
        assert_eq!(upload.mime.as_deref(), Some("application/json"));
        assert_eq!(upload.content_length(), None);
        let upload = upload.resolve().await.unwrap();
        assert_eq!(upload.content_length(), Some(10));

        let body = Body::from(upload.clone());
        assert_eq!(http_body::Body::size_hint(&body).exact(), Some(10));
        let copy = body.try_clone().unwrap();
        assert_eq!(body.collect_bytes().await.unwrap(), "0123456789");
        assert_eq!(copy.collect_bytes().await.unwrap(), "0123456789");

        let slice = upload.slice(2..5);
        assert_eq!(slice.content_length(), Some(3));
        assert_eq!(read(slice.clone()).await, "234");
        assert_eq!(read(slice.slice(1..)).await, "34");
        let tail = Upload::file(&path).await.unwrap().slice(8..20);
        assert_eq!(tail.content_length(), Some(2));
        assert_eq!(read(tail).await, "89");
    }

    #[cfg(feature = "fs")]
    #[tokio::test]
    async fn a_missing_file_fails_to_resolve() {
        let error = Upload::file("/no/such/file.bin").await.unwrap_err();
        assert!(error.is_io(), "{error:?}");
        assert!(error.to_string().contains("/no/such/file.bin"), "{error}");
    }

    #[cfg(feature = "fs")]
    #[tokio::test]
    async fn a_file_that_shrank_is_an_error() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("data.bin");
        std::fs::write(&path, "0123456789").unwrap();
        let upload = Upload::file(&path).await.unwrap();
        std::fs::write(&path, "0123").unwrap();
        assert!(Body::from(upload).collect_bytes().await.is_err());
    }

    #[cfg(feature = "fs")]
    #[tokio::test]
    async fn a_reader_is_read_once() {
        let upload = Upload::reader(&b"from a reader"[..]);
        assert!(!upload.is_replayable());
        assert_eq!(read(upload).await, "from a reader");
    }
}
