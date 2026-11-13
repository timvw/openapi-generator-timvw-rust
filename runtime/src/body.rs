use std::fmt;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};

use bytes::Bytes;
use futures_core::TryStream;
use futures_util::TryStreamExt;
use http_body::{Frame, SizeHint};
use http_body_util::combinators::BoxBody;
use http_body_util::{BodyExt, StreamBody};
use sync_wrapper::SyncStream;

use crate::BoxError;

/// The body of a request or a response: bytes held in memory, a stream, or a stream that can be made again.
///
/// A body held in memory can be cloned ([`Body::try_clone`]), which is what lets a retry layer send a request again.
/// So can a body made from a replayable [`Upload`](crate::Upload), such as a file, which is opened again for each
/// attempt. Any other stream can be read once.
pub struct Body {
    kind: Kind,
}

enum Kind {
    Full(Option<Bytes>),
    Stream {
        body: BoxBody<Bytes, BoxError>,
        /// The length, when known and the body cannot tell (a stream of chunks).
        len: Option<u64>,
    },
    Replay(Replay),
}

/// A body made again for each attempt: `make` starts a fresh stream, `current` is the one being read.
struct Replay {
    make: Arc<dyn Fn() -> Body + Send + Sync>,
    len: Option<u64>,
    current: Option<Box<Body>>,
}

impl Body {
    /// An empty body.
    pub fn empty() -> Self {
        Self {
            kind: Kind::Full(None),
        }
    }

    /// A body that wraps another [`http_body::Body`], such as the body of a hyper or reqwest response.
    pub fn wrap<B>(body: B) -> Self
    where
        B: http_body::Body + Send + Sync + 'static,
        B::Data: Into<Bytes>,
        B::Error: Into<BoxError>,
    {
        Self {
            kind: Kind::Stream {
                body: body
                    .map_frame(|frame| frame.map_data(Into::into))
                    .map_err(Into::into)
                    .boxed(),
                len: None,
            },
        }
    }

    /// A body that reads the chunks of a stream, such as a file read with `tokio_util::io::ReaderStream`.
    pub fn wrap_stream<S>(stream: S) -> Self
    where
        S: TryStream + Send + 'static,
        S::Ok: Into<Bytes>,
        S::Error: Into<BoxError>,
    {
        Self::sized_stream(stream, None)
    }

    /// A stream of chunks whose total length, when known, is `len` (sent as `Content-Length`).
    pub(crate) fn sized_stream<S>(stream: S, len: Option<u64>) -> Self
    where
        S: TryStream + Send + 'static,
        S::Ok: Into<Bytes>,
        S::Error: Into<BoxError>,
    {
        let frames = SyncStream::new(
            stream
                .into_stream()
                .map_ok(|chunk| Frame::data(chunk.into()))
                .map_err(Into::into),
        );
        Self {
            kind: Kind::Stream {
                body: StreamBody::new(frames).boxed(),
                len,
            },
        }
    }

    /// A body that `make` starts afresh for each attempt, so it can be cloned; `len` is the length of every body
    /// `make` returns, when known.
    pub(crate) fn replay(make: Arc<dyn Fn() -> Body + Send + Sync>, len: Option<u64>) -> Self {
        Self {
            kind: Kind::Replay(Replay {
                make,
                len,
                current: None,
            }),
        }
    }

    /// A copy of the body: a body held in memory, or a fresh start of one that can be made again (a file
    /// [`Upload`](crate::Upload), say); `None` for a stream that can be read once.
    pub fn try_clone(&self) -> Option<Self> {
        match &self.kind {
            Kind::Full(bytes) => Some(Self {
                kind: Kind::Full(bytes.clone()),
            }),
            Kind::Stream { .. } => None,
            Kind::Replay(replay) => Some(Self::replay(replay.make.clone(), replay.len)),
        }
    }

    /// The bytes of a body held in memory, or the body itself for a stream.
    pub(crate) fn into_bytes(self) -> Result<Bytes, Self> {
        match self.kind {
            Kind::Full(bytes) => Ok(bytes.unwrap_or_default()),
            kind => Err(Self { kind }),
        }
    }

    /// The bytes of a body held in memory; `None` for a stream.
    pub fn as_bytes(&self) -> Option<&[u8]> {
        match &self.kind {
            Kind::Full(bytes) => Some(bytes.as_deref().unwrap_or_default()),
            Kind::Stream { .. } | Kind::Replay(_) => None,
        }
    }

    /// Reads the whole body into memory.
    pub async fn collect_bytes(self) -> Result<Bytes, BoxError> {
        match self.kind {
            Kind::Full(bytes) => Ok(bytes.unwrap_or_default()),
            Kind::Stream { body, .. } => Ok(body.collect().await?.to_bytes()),
            kind @ Kind::Replay(_) => Ok(BodyExt::collect(Self { kind }).await?.to_bytes()),
        }
    }
}

impl Default for Body {
    fn default() -> Self {
        Self::empty()
    }
}

impl fmt::Debug for Body {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match &self.kind {
            Kind::Full(bytes) => f
                .debug_struct("Body")
                .field("len", &bytes.as_ref().map_or(0, Bytes::len))
                .finish(),
            Kind::Stream { len, .. } => f
                .debug_struct("Body")
                .field("stream", &true)
                .field("len", len)
                .finish(),
            Kind::Replay(replay) => f
                .debug_struct("Body")
                .field("replayable", &true)
                .field("len", &replay.len)
                .finish(),
        }
    }
}

impl http_body::Body for Body {
    type Data = Bytes;
    type Error = BoxError;

    fn poll_frame(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<Option<Result<Frame<Self::Data>, Self::Error>>> {
        match &mut self.get_mut().kind {
            Kind::Full(bytes) => Poll::Ready(
                bytes
                    .take()
                    .filter(|b| !b.is_empty())
                    .map(|b| Ok(Frame::data(b))),
            ),
            Kind::Stream { body, .. } => Pin::new(body).poll_frame(cx),
            Kind::Replay(replay) => {
                let current = replay
                    .current
                    .get_or_insert_with(|| Box::new((replay.make)()));
                Pin::new(&mut **current).poll_frame(cx)
            }
        }
    }

    fn is_end_stream(&self) -> bool {
        match &self.kind {
            Kind::Full(bytes) => bytes.as_ref().is_none_or(Bytes::is_empty),
            Kind::Stream { body, .. } => body.is_end_stream(),
            Kind::Replay(replay) => match &replay.current {
                Some(current) => current.is_end_stream(),
                None => replay.len == Some(0),
            },
        }
    }

    fn size_hint(&self) -> SizeHint {
        match &self.kind {
            Kind::Full(bytes) => SizeHint::with_exact(bytes.as_ref().map_or(0, |b| b.len() as u64)),
            Kind::Stream { body, len } => {
                len.map_or_else(|| body.size_hint(), SizeHint::with_exact)
            }
            Kind::Replay(replay) => replay
                .len
                .map_or_else(SizeHint::default, SizeHint::with_exact),
        }
    }
}

impl From<Bytes> for Body {
    fn from(bytes: Bytes) -> Self {
        Self {
            kind: Kind::Full(Some(bytes)),
        }
    }
}

impl From<Vec<u8>> for Body {
    fn from(bytes: Vec<u8>) -> Self {
        Bytes::from(bytes).into()
    }
}

impl From<String> for Body {
    fn from(text: String) -> Self {
        Bytes::from(text).into()
    }
}

impl From<&'static str> for Body {
    fn from(text: &'static str) -> Self {
        Bytes::from_static(text.as_bytes()).into()
    }
}

impl From<&'static [u8]> for Body {
    fn from(bytes: &'static [u8]) -> Self {
        Bytes::from_static(bytes).into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn bytes_clone_and_read() {
        let body = Body::from("hello");
        let copy = body.try_clone().expect("bytes can be cloned");
        assert_eq!(body.collect_bytes().await.unwrap(), "hello");
        assert_eq!(copy.as_bytes(), Some(&b"hello"[..]));
    }

    #[tokio::test]
    async fn streams_are_read_once() {
        let chunks = futures_util::stream::iter(vec![
            Ok::<_, std::io::Error>(Bytes::from("a")),
            Ok(Bytes::from("b")),
        ]);
        let body = Body::wrap_stream(chunks);
        assert!(body.try_clone().is_none());
        assert_eq!(body.collect_bytes().await.unwrap(), "ab");
    }

    #[test]
    fn empty_is_end_of_stream() {
        assert!(http_body::Body::is_end_stream(&Body::empty()));
        assert_eq!(
            http_body::Body::size_hint(&Body::from("abc")).exact(),
            Some(3)
        );
    }
}
