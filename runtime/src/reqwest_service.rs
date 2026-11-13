use std::task::{Context, Poll};

use tower::Service;

use crate::maybe::BoxFuture;
use crate::{Body, BoxError};

/// A tower service that sends `http::Request<Body>` with a `reqwest::Client`.
///
/// A body held in memory goes to reqwest as bytes (so reqwest can clone the request); a stream is wrapped.
///
/// On WebAssembly (`target_family = "wasm"`) reqwest sends with the JavaScript host's `fetch`, which takes a request
/// body whole: a stream (a streamed [`Upload`](crate::Upload), a multipart form with one) is read into memory first.
/// The response body is still read as it arrives.
#[derive(Clone, Debug, Default)]
pub struct ReqwestService {
    client: reqwest::Client,
}

impl ReqwestService {
    /// A service that sends with `client`.
    pub fn new(client: reqwest::Client) -> Self {
        Self { client }
    }

    /// The reqwest client.
    pub fn client(&self) -> &reqwest::Client {
        &self.client
    }
}

#[cfg(not(target_family = "wasm"))]
impl Service<http::Request<Body>> for ReqwestService {
    type Response = http::Response<reqwest::Body>;
    type Error = BoxError;
    type Future = BoxFuture<'static, Result<Self::Response, Self::Error>>;

    fn poll_ready(&mut self, _: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        Poll::Ready(Ok(()))
    }

    fn call(&mut self, request: http::Request<Body>) -> Self::Future {
        let client = self.client.clone();
        Box::pin(async move {
            let request = request.map(|body| match body.as_bytes() {
                Some(bytes) => reqwest::Body::from(bytes::Bytes::copy_from_slice(bytes)),
                None => reqwest::Body::wrap(body),
            });
            let request = reqwest::Request::try_from(request)?;
            let response = client.execute(request).await?;
            Ok(http::Response::from(response))
        })
    }
}

#[cfg(target_family = "wasm")]
impl Service<http::Request<Body>> for ReqwestService {
    type Response = http::Response<Body>;
    type Error = BoxError;
    type Future = BoxFuture<'static, Result<Self::Response, Self::Error>>;

    fn poll_ready(&mut self, _: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        Poll::Ready(Ok(()))
    }

    fn call(&mut self, request: http::Request<Body>) -> Self::Future {
        let client = self.client.clone();
        Box::pin(async move {
            let (parts, body) = request.into_parts();
            let body = body.collect_bytes().await?;
            let request = http::Request::from_parts(parts, reqwest::Body::from(body));
            let request = reqwest::Request::try_from(request)?;
            let mut response = client.execute(request).await?;
            let mut builder = http::Response::builder().status(response.status());
            if let Some(headers) = builder.headers_mut() {
                *headers = std::mem::take(response.headers_mut());
            }
            Ok(builder.body(Body::wrap_stream(response.bytes_stream()))?)
        })
    }
}
