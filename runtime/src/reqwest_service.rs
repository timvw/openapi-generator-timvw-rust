use std::future::Future;
use std::pin::Pin;
use std::task::{Context, Poll};

use tower::Service;

use crate::{Body, BoxError};

/// A tower service that sends `http::Request<Body>` with a `reqwest::Client`.
///
/// A body held in memory goes to reqwest as bytes (so reqwest can clone the request); a stream is wrapped.
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

impl Service<http::Request<Body>> for ReqwestService {
    type Response = http::Response<reqwest::Body>;
    type Error = BoxError;
    type Future = Pin<Box<dyn Future<Output = Result<Self::Response, Self::Error>> + Send>>;

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
