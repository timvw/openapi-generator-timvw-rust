use std::fmt;
use std::future::Future;
use std::pin::Pin;
use std::sync::{Mutex, PoisonError};
use std::task::{Context, Poll};

use tower::util::BoxCloneSyncService;
use tower::{Layer, Service, ServiceExt};

use crate::{Body, BoxError, Error, Method, RequestBuilder, Response};

/// The service a [`Client`] sends requests through, with its type erased.
pub type HttpService = BoxCloneSyncService<http::Request<Body>, http::Response<Body>, BoxError>;

/// Sends the requests of a generated client through a tower service.
///
/// [`Client::default`] sends with reqwest (feature `reqwest`) and retries transient failures (feature `retry`).
/// [`Client::from_service`] takes any other service, as is, and [`Client::layer`] wraps the current one in a tower
/// layer:
///
/// ```no_run
/// # #[cfg(feature = "reqwest")]
/// # fn demo() {
/// use std::time::Duration;
/// use timvw_rust_runtime::Client;
///
/// let client = Client::default().layer(tower::timeout::TimeoutLayer::new(Duration::from_secs(30)));
/// # }
/// ```
#[derive(Clone)]
pub struct Client {
    service: HttpService,
}

impl Client {
    /// A client that sends with a new `reqwest::Client`, retrying with [`RetryPolicy::default`] (feature `retry`).
    ///
    /// [`RetryPolicy::default`]: crate::retry::RetryPolicy
    #[cfg(feature = "reqwest")]
    pub fn new() -> Self {
        Self::from_reqwest(reqwest::Client::new())
    }

    /// A client that sends with the given `reqwest::Client`, for example one with a proxy or a custom TLS setup, and
    /// retries like [`Client::new`].
    ///
    /// For other retries, or none, start from the bare service instead:
    /// `Client::from_service(ReqwestService::new(client))`, then add the layers you want.
    #[cfg(feature = "reqwest")]
    pub fn from_reqwest(client: reqwest::Client) -> Self {
        let client = Self::from_service(crate::ReqwestService::new(client));
        #[cfg(feature = "retry")]
        let client = client.layer(crate::retry::RetryLayer::default());
        client
    }

    /// A client that sends with any tower service from `http::Request<Body>` to `http::Response<B>`.
    ///
    /// The service must be `Clone` and `Send`, not `Sync`: services such as `ConcurrencyLimit` that cannot be shared
    /// between threads are held behind a lock that is only taken to clone them.
    pub fn from_service<S, B>(service: S) -> Self
    where
        S: Service<http::Request<Body>, Response = http::Response<B>> + Clone + Send + 'static,
        S::Error: Into<BoxError>,
        S::Future: Send + 'static,
        B: http_body::Body + Send + Sync + 'static,
        B::Data: Into<bytes::Bytes>,
        B::Error: Into<BoxError>,
    {
        let service = service
            .map_response(|response: http::Response<B>| response.map(Body::wrap))
            .map_err(Into::into);
        Self {
            service: BoxCloneSyncService::new(SyncService(Mutex::new(service))),
        }
    }

    /// Wraps the client's service in a tower layer: retries, timeouts, rate limits, tracing, ...
    ///
    /// Layers apply from the inside out: the last one added sees a request first.
    pub fn layer<L, B>(self, layer: L) -> Self
    where
        L: Layer<HttpService>,
        L::Service:
            Service<http::Request<Body>, Response = http::Response<B>> + Clone + Send + 'static,
        <L::Service as Service<http::Request<Body>>>::Error: Into<BoxError>,
        <L::Service as Service<http::Request<Body>>>::Future: Send + 'static,
        B: http_body::Body + Send + Sync + 'static,
        B::Data: Into<bytes::Bytes>,
        B::Error: Into<BoxError>,
    {
        Self::from_service(layer.layer(self.service))
    }

    /// The service requests go through.
    pub fn service(&self) -> &HttpService {
        &self.service
    }

    /// Starts a request to `url`.
    pub fn request(&self, method: Method, url: &str) -> RequestBuilder {
        RequestBuilder::new(self.clone(), method, url)
    }

    /// Sends a request.
    pub async fn execute(&self, request: http::Request<Body>) -> Result<Response, Error> {
        let response = self
            .service
            .clone()
            .oneshot(request)
            .await
            .map_err(Error::request)?;
        Ok(Response::from(response))
    }
}

/// With feature `reqwest` [`Client::new`]: reqwest, with retries when feature `retry` is on; without it a client whose every request fails, to be
/// replaced with [`Client::from_service`].
impl Default for Client {
    fn default() -> Self {
        #[cfg(feature = "reqwest")]
        {
            Self::new()
        }
        #[cfg(not(feature = "reqwest"))]
        {
            Self::from_service(NoService)
        }
    }
}

impl fmt::Debug for Client {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Client").finish_non_exhaustive()
    }
}

/// Makes a `Clone + Send` service `Sync`. Every request clones the service, under the lock, and calls the clone, so
/// the lock is never held across `poll_ready` or `call` on a shared value.
struct SyncService<S>(Mutex<S>);

impl<S: Clone> Clone for SyncService<S> {
    fn clone(&self) -> Self {
        let service = self
            .0
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .clone();
        Self(Mutex::new(service))
    }
}

impl<S: Service<R>, R> Service<R> for SyncService<S> {
    type Response = S::Response;
    type Error = S::Error;
    type Future = S::Future;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.0
            .get_mut()
            .unwrap_or_else(PoisonError::into_inner)
            .poll_ready(cx)
    }

    fn call(&mut self, request: R) -> Self::Future {
        self.0
            .get_mut()
            .unwrap_or_else(PoisonError::into_inner)
            .call(request)
    }
}

/// The service of a client built without an HTTP client: every request fails.
#[cfg_attr(feature = "reqwest", allow(dead_code))]
#[derive(Clone, Copy, Debug)]
struct NoService;

impl Service<http::Request<Body>> for NoService {
    type Response = http::Response<Body>;
    type Error = BoxError;
    type Future = Pin<Box<dyn Future<Output = Result<Self::Response, Self::Error>> + Send>>;

    fn poll_ready(&mut self, _: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        Poll::Ready(Ok(()))
    }

    fn call(&mut self, _: http::Request<Body>) -> Self::Future {
        Box::pin(async {
            Err("no HTTP client: enable the `reqwest` feature of timvw-rust-runtime or set one with Client::from_service".into())
        })
    }
}
