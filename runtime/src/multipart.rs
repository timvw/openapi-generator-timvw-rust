//! `multipart/form-data` bodies, with the API of reqwest's `multipart` module that generated clients use.
//!
//! A form whose parts are all held in memory encodes to a body held in memory, so a retry layer can send it again.
//! A form with a streamed part encodes to a stream.

use std::borrow::Cow;
use std::collections::hash_map::RandomState;
use std::fmt;
use std::hash::{BuildHasher, Hasher};

use bytes::{Bytes, BytesMut};
use futures_util::{future, stream, StreamExt, TryStreamExt};
use http::HeaderValue;
use http_body_util::BodyDataStream;

use crate::{Body, BoxError, Error};

/// A `multipart/form-data` form.
pub struct Form {
    boundary: String,
    parts: Vec<(Cow<'static, str>, Part)>,
}

/// A part of a [`Form`]: its content, file name and media type.
pub struct Part {
    content: Content,
    file_name: Option<Cow<'static, str>>,
    mime: Option<HeaderValue>,
}

enum Content {
    Bytes(Bytes),
    Stream(Body),
}

impl Form {
    /// An empty form with a random boundary.
    pub fn new() -> Self {
        Self {
            boundary: random_boundary(),
            parts: Vec::new(),
        }
    }

    /// The boundary between the parts.
    pub fn boundary(&self) -> &str {
        &self.boundary
    }

    /// Adds a text part.
    pub fn text<N, V>(self, name: N, value: V) -> Self
    where
        N: Into<Cow<'static, str>>,
        V: Into<Cow<'static, str>>,
    {
        self.part(name, Part::text(value))
    }

    /// Adds a part.
    pub fn part<N: Into<Cow<'static, str>>>(mut self, name: N, part: Part) -> Self {
        self.parts.push((name.into(), part));
        self
    }

    /// Encodes the form.
    pub fn into_body(self) -> Body {
        let closing = Bytes::from(format!("--{}--\r\n", self.boundary));
        if self
            .parts
            .iter()
            .all(|(_, part)| matches!(part.content, Content::Bytes(_)))
        {
            let mut out = BytesMut::new();
            for (name, part) in &self.parts {
                out.extend_from_slice(&part.head(&self.boundary, name));
                if let Content::Bytes(bytes) = &part.content {
                    out.extend_from_slice(bytes);
                }
                out.extend_from_slice(b"\r\n");
            }
            out.extend_from_slice(&closing);
            return Body::from(out.freeze());
        }
        let boundary = self.boundary;
        let chunks = stream::iter(self.parts)
            .map(move |(name, part)| {
                let head = stream::once(future::ready(Ok::<_, BoxError>(
                    part.head(&boundary, &name),
                )));
                let content = match part.content {
                    Content::Bytes(bytes) => stream::once(future::ready(Ok(bytes))).boxed(),
                    Content::Stream(body) => BodyDataStream::new(body).boxed(),
                };
                let tail = stream::once(future::ready(Ok(Bytes::from_static(b"\r\n"))));
                head.chain(content).chain(tail)
            })
            .flatten()
            .chain(stream::once(future::ready(Ok(closing))));
        Body::wrap_stream(chunks.into_stream())
    }
}

impl Default for Form {
    fn default() -> Self {
        Self::new()
    }
}

impl fmt::Debug for Form {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Form")
            .field("boundary", &self.boundary)
            .field("parts", &self.parts)
            .finish()
    }
}

impl Part {
    /// A text part.
    pub fn text<V: Into<Cow<'static, str>>>(value: V) -> Self {
        let bytes = match value.into() {
            Cow::Borrowed(text) => Bytes::from_static(text.as_bytes()),
            Cow::Owned(text) => Bytes::from(text),
        };
        Self::new(Content::Bytes(bytes))
    }

    /// A part of bytes.
    pub fn bytes<V: Into<Cow<'static, [u8]>>>(value: V) -> Self {
        let bytes = match value.into() {
            Cow::Borrowed(bytes) => Bytes::from_static(bytes),
            Cow::Owned(bytes) => Bytes::from(bytes),
        };
        Self::new(Content::Bytes(bytes))
    }

    /// A part read from a body, such as a file stream; a body held in memory stays in memory.
    pub fn stream<T: Into<Body>>(value: T) -> Self {
        let body = value.into();
        match body.as_bytes() {
            Some(bytes) => Self::new(Content::Bytes(Bytes::copy_from_slice(bytes))),
            None => Self::new(Content::Stream(body)),
        }
    }

    fn new(content: Content) -> Self {
        Self {
            content,
            file_name: None,
            mime: None,
        }
    }

    /// Sets the file name.
    pub fn file_name<T: Into<Cow<'static, str>>>(mut self, file_name: T) -> Self {
        self.file_name = Some(file_name.into());
        self
    }

    /// Sets the media type (`Content-Type` of the part).
    pub fn mime_str(mut self, mime: &str) -> Result<Self, Error> {
        let essence = mime.split(';').next().unwrap_or_default().trim();
        if essence
            .split_once('/')
            .is_none_or(|(kind, sub)| kind.is_empty() || sub.is_empty())
        {
            return Err(Error::builder(format!("invalid media type `{mime}`")));
        }
        self.mime = Some(HeaderValue::try_from(mime).map_err(Error::builder)?);
        Ok(self)
    }

    fn head(&self, boundary: &str, name: &str) -> Bytes {
        let mut head = format!(
            "--{boundary}\r\nContent-Disposition: form-data; name=\"{}\"",
            escape(name)
        );
        if let Some(file_name) = &self.file_name {
            head.push_str(&format!("; filename=\"{}\"", escape(file_name)));
        }
        head.push_str("\r\n");
        if let Some(mime) = &self.mime {
            head.push_str("Content-Type: ");
            head.push_str(mime.to_str().unwrap_or("application/octet-stream"));
            head.push_str("\r\n");
        }
        head.push_str("\r\n");
        Bytes::from(head)
    }
}

impl fmt::Debug for Part {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let content = match &self.content {
            Content::Bytes(bytes) => format!("{} bytes", bytes.len()),
            Content::Stream(_) => "stream".to_owned(),
        };
        f.debug_struct("Part")
            .field("content", &content)
            .field("file_name", &self.file_name)
            .field("mime", &self.mime)
            .finish()
    }
}

/// Escapes a name or file name in a `Content-Disposition` header as browsers do (WHATWG HTML): `"`, CR and LF become
/// percent escapes.
fn escape(value: &str) -> Cow<'_, str> {
    if value.contains(['"', '\r', '\n']) {
        Cow::Owned(
            value
                .replace('"', "%22")
                .replace('\r', "%0D")
                .replace('\n', "%0A"),
        )
    } else {
        Cow::Borrowed(value)
    }
}

fn random_boundary() -> String {
    let random = |salt: u64| {
        let mut hasher = RandomState::new().build_hasher();
        hasher.write_u64(salt);
        hasher.finish()
    };
    format!(
        "{:016x}-{:016x}-{:016x}-{:016x}",
        random(1),
        random(2),
        random(3),
        random(4)
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn form() -> Form {
        Form {
            boundary: "B".to_owned(),
            parts: Vec::new(),
        }
    }

    #[tokio::test]
    async fn parts_in_memory_encode_to_a_cloneable_body() {
        let body = form()
            .text("name", "value")
            .part(
                "file",
                Part::bytes(b"abc".to_vec())
                    .file_name("a.txt")
                    .mime_str("text/plain")
                    .unwrap(),
            )
            .into_body();
        assert!(body.try_clone().is_some());
        let expected = "--B\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\nvalue\r\n\
                        --B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n\
                        Content-Type: text/plain\r\n\r\nabc\r\n--B--\r\n";
        assert_eq!(body.collect_bytes().await.unwrap(), expected);
    }

    #[tokio::test]
    async fn a_streamed_part_encodes_to_a_stream() {
        let chunks = stream::iter(vec![
            Ok::<_, std::io::Error>(Bytes::from("a")),
            Ok(Bytes::from("b")),
        ]);
        let body = form()
            .part(
                "file",
                Part::stream(Body::wrap_stream(chunks)).file_name("f"),
            )
            .into_body();
        assert!(body.try_clone().is_none());
        let expected = "--B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"f\"\r\n\r\nab\r\n--B--\r\n";
        assert_eq!(body.collect_bytes().await.unwrap(), expected);
    }

    #[test]
    fn names_are_escaped_and_media_types_checked() {
        let part = Part::text("x").file_name("a\"b");
        assert!(String::from_utf8_lossy(&part.head("B", "n\r\n"))
            .contains("name=\"n%0D%0A\"; filename=\"a%22b\""));
        assert!(Part::text("x").mime_str("nonsense").is_err());
        assert!(Part::text("x")
            .mime_str("application/json; charset=utf-8")
            .is_ok());
    }

    #[test]
    fn boundaries_differ() {
        assert_ne!(Form::new().boundary, Form::new().boundary);
    }
}
