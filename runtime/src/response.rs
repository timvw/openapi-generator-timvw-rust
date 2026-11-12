use std::fmt;

use bytes::Bytes;
use futures_core::Stream;
use futures_util::TryStreamExt;
use http_body_util::BodyDataStream;
use serde::de::DeserializeOwned;

use crate::{Body, Error, HeaderMap, StatusCode};

/// A response, with the methods of reqwest's `Response` that generated clients use.
pub struct Response {
    inner: http::Response<Body>,
}

impl Response {
    /// The status code.
    pub fn status(&self) -> StatusCode {
        self.inner.status()
    }

    /// The headers.
    pub fn headers(&self) -> &HeaderMap {
        self.inner.headers()
    }

    /// The headers, to change.
    pub fn headers_mut(&mut self) -> &mut HeaderMap {
        self.inner.headers_mut()
    }

    /// Reads the body into memory.
    pub async fn bytes(self) -> Result<Bytes, Error> {
        self.inner
            .into_body()
            .collect_bytes()
            .await
            .map_err(Error::body)
    }

    /// Reads the body as text; bytes that are not UTF-8 become U+FFFD.
    pub async fn text(self) -> Result<String, Error> {
        let bytes = self.bytes().await?;
        Ok(match String::from_utf8(bytes.to_vec()) {
            Ok(text) => text,
            Err(error) => String::from_utf8_lossy(error.as_bytes()).into_owned(),
        })
    }

    /// Reads the body as JSON.
    pub async fn json<T: DeserializeOwned>(self) -> Result<T, Error> {
        let bytes = self.bytes().await?;
        serde_json::from_slice(&bytes).map_err(Error::decode)
    }

    /// The body as a stream of chunks, as they arrive.
    pub fn bytes_stream(self) -> impl Stream<Item = Result<Bytes, Error>> + Send + 'static {
        BodyDataStream::new(self.inner.into_body()).map_err(Error::body)
    }

    /// The underlying `http::Response`.
    pub fn into_http(self) -> http::Response<Body> {
        self.inner
    }
}

impl From<http::Response<Body>> for Response {
    fn from(inner: http::Response<Body>) -> Self {
        Self { inner }
    }
}

impl fmt::Debug for Response {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Response")
            .field("status", &self.inner.status())
            .field("headers", self.inner.headers())
            .finish_non_exhaustive()
    }
}
