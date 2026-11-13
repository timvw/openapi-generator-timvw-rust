//! Logs every request with [`tracing`](https://docs.rs/tracing) (feature `tracing`).
//!
//! [`TraceLayer`] opens a `http.request` span per request, with the method and the URL without its query (a query can
//! hold an API key), and records the status and duration when the response arrives:
//!
//! - a response below `500`: an event at `DEBUG`;
//! - a `5xx` response or a failure to send: an event at `WARN`.
//!
//! Events have the target `timvw_rust_runtime::trace`, so `RUST_LOG=timvw_rust_runtime=debug` shows every request.
//! With the `retry` feature, [`RetryPolicy`](crate::retry::RetryPolicy) logs each retry at `DEBUG` too.
//!
//! ```no_run
//! # #[cfg(feature = "reqwest")]
//! # fn demo() {
//! use timvw_rust_runtime::{trace::TraceLayer, Client};
//!
//! let client = Client::default().layer(TraceLayer::new());
//! # }
//! ```
//!
//! Headers and bodies are not logged. For more (headers, custom spans, OpenTelemetry), use `tower-http`'s
//! `TraceLayer`; it works with [`Client::layer`](crate::Client::layer) too.

use std::fmt;
use std::task::{Context, Poll};
#[cfg(not(target_family = "wasm"))]
use std::time::Instant;

use tower::{Layer, Service};
use tracing::Instrument;
#[cfg(target_family = "wasm")]
use web_time::Instant;

use crate::maybe::BoxFuture;
use crate::MaybeSend;

/// A tower layer that logs each request and its outcome. See the [module](self) documentation.
#[derive(Clone, Copy, Debug, Default)]
pub struct TraceLayer {
    _private: (),
}

impl TraceLayer {
    /// The layer.
    pub fn new() -> Self {
        Self::default()
    }
}

impl<S> Layer<S> for TraceLayer {
    type Service = TraceService<S>;

    fn layer(&self, inner: S) -> Self::Service {
        TraceService { inner }
    }
}

/// The service of a [`TraceLayer`].
#[derive(Clone, Debug)]
pub struct TraceService<S> {
    inner: S,
}

impl<S, ReqBody, ResBody> Service<http::Request<ReqBody>> for TraceService<S>
where
    S: Service<http::Request<ReqBody>, Response = http::Response<ResBody>>,
    S::Error: fmt::Display,
    S::Future: MaybeSend + 'static,
{
    type Response = S::Response;
    type Error = S::Error;
    type Future = BoxFuture<'static, Result<S::Response, S::Error>>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, request: http::Request<ReqBody>) -> Self::Future {
        let uri = request.uri();
        let url = format!(
            "{}://{}{}",
            uri.scheme_str().unwrap_or("http"),
            uri.authority().map(|a| a.as_str()).unwrap_or(""),
            uri.path()
        );
        let span = tracing::debug_span!(
            "http.request",
            http.request.method = %request.method(),
            url.full = %url,
            http.response.status_code = tracing::field::Empty,
        );
        let start = Instant::now();
        let future = {
            let _entered = span.enter();
            self.inner.call(request)
        };
        let instrumented = span.clone();
        Box::pin(
            async move {
                let result = future.await;
                let elapsed_ms = start.elapsed().as_millis() as u64;
                match &result {
                    Ok(response) => {
                        let status = response.status();
                        span.record("http.response.status_code", status.as_u16());
                        if status.is_server_error() {
                            tracing::warn!(status = status.as_u16(), elapsed_ms, "response");
                        } else {
                            tracing::debug!(status = status.as_u16(), elapsed_ms, "response");
                        }
                    }
                    Err(error) => tracing::warn!(%error, elapsed_ms, "request failed"),
                }
                result
            }
            .instrument(instrumented),
        )
    }
}
