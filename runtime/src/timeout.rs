//! A request timeout that works natively and on WebAssembly: [`TimeoutLayer`].
//!
//! tower's own `TimeoutLayer` sleeps with tokio, which has no timers on WebAssembly (`wasm32-unknown-unknown`), and
//! `fetch` has no timeout of its own. This one waits with the timer the rest of the crate uses: tokio's natively (so
//! tests can pause time), the JavaScript host's `setTimeout` on WebAssembly.
//!
//! Where it sits decides what it bounds. [`Client::layer`](crate::Client::layer) wraps the current stack, so a timeout
//! added to `Client::default()` bounds the whole call, retries and their waits included. Built from the bare service,
//! a timeout added before the retries bounds each attempt:
//!
//! ```no_run
//! # #[cfg(all(feature = "reqwest", feature = "retry"))]
//! # fn demo() {
//! use std::time::Duration;
//! use timvw_rust_runtime::retry::{RetryLayer, RetryPolicy};
//! use timvw_rust_runtime::timeout::TimeoutLayer;
//! use timvw_rust_runtime::{Client, ReqwestService};
//!
//! // The whole call, retries included.
//! let client = Client::default().layer(TimeoutLayer::new(Duration::from_secs(120)));
//!
//! // Each attempt, retried when it times out; and the whole call.
//! let client = Client::from_service(ReqwestService::default())
//!     .layer(TimeoutLayer::new(Duration::from_secs(30)))
//!     .layer(RetryLayer::new(RetryPolicy::default().retry_timeouts(true)))
//!     .layer(TimeoutLayer::new(Duration::from_secs(120)));
//! # }
//! ```
//!
//! The timeout runs until the response's status and headers arrive: reading the body is not part of it. A request
//! that times out is dropped, which cancels it (reqwest aborts the `fetch` on WebAssembly), and fails with an
//! [`Error`](crate::Error) whose [`is_timeout`](crate::Error::is_timeout) is true.

use std::future::Future;
use std::pin::Pin;
use std::task::{Context, Poll};
use std::time::Duration;

use tower::{Layer, Service};

use crate::error::TimedOut;
use crate::timer::{Instant, Sleep};
use crate::BoxError;

/// Fails a request whose response does not arrive within a time limit; see the [module](self).
#[derive(Clone, Copy, Debug)]
pub struct TimeoutLayer {
    timeout: Duration,
}

impl TimeoutLayer {
    /// A timeout of `timeout`, counted from when the request is sent.
    pub fn new(timeout: Duration) -> Self {
        Self { timeout }
    }
}

impl<S> Layer<S> for TimeoutLayer {
    type Service = Timeout<S>;

    fn layer(&self, inner: S) -> Self::Service {
        Timeout {
            inner,
            timeout: self.timeout,
        }
    }
}

/// The service of a [`TimeoutLayer`].
#[derive(Clone, Debug)]
pub struct Timeout<S> {
    inner: S,
    timeout: Duration,
}

impl<S> Timeout<S> {
    /// Wraps `inner` in a timeout of `timeout`.
    pub fn new(inner: S, timeout: Duration) -> Self {
        Self { inner, timeout }
    }
}

impl<S, R> Service<R> for Timeout<S>
where
    S: Service<R>,
    S::Error: Into<BoxError>,
{
    type Response = S::Response;
    type Error = BoxError;
    type Future = ResponseFuture<S::Future>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx).map_err(Into::into)
    }

    fn call(&mut self, request: R) -> Self::Future {
        let deadline = Instant::now() + self.timeout;
        ResponseFuture {
            inner: Box::pin(self.inner.call(request)),
            sleep: Sleep::until(deadline),
            timeout: self.timeout,
        }
    }
}

/// The future of a [`Timeout`] service.
pub struct ResponseFuture<F> {
    inner: Pin<Box<F>>,
    sleep: Sleep,
    timeout: Duration,
}

impl<F, T, E> Future for ResponseFuture<F>
where
    F: Future<Output = Result<T, E>>,
    E: Into<BoxError>,
{
    type Output = Result<T, BoxError>;

    fn poll(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Self::Output> {
        if let Poll::Ready(result) = self.inner.as_mut().poll(cx) {
            return Poll::Ready(result.map_err(Into::into));
        }
        match Pin::new(&mut self.sleep).poll(cx) {
            Poll::Ready(()) => Poll::Ready(Err(Box::new(TimedOut(format!(
                "request timed out after {:?}",
                self.timeout
            ))))),
            Poll::Pending => Poll::Pending,
        }
    }
}

impl<F> std::fmt::Debug for ResponseFuture<F> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ResponseFuture")
            .field("timeout", &self.timeout)
            .finish_non_exhaustive()
    }
}

#[cfg(all(test, not(target_family = "wasm")))]
mod tests {
    use super::*;
    use tower::ServiceExt;

    #[tokio::test(start_paused = true)]
    async fn a_slow_service_times_out_and_a_fast_one_does_not() {
        let slow = tower::service_fn(|delay: Duration| async move {
            tokio::time::sleep(delay).await;
            Ok::<_, BoxError>(delay)
        });
        let service = TimeoutLayer::new(Duration::from_secs(1)).layer(slow);
        let fast = service
            .clone()
            .oneshot(Duration::from_millis(999))
            .await
            .unwrap();
        assert_eq!(fast, Duration::from_millis(999));
        let started = Instant::now();
        let error = service.oneshot(Duration::from_secs(5)).await.unwrap_err();
        assert_eq!(started.elapsed(), Duration::from_secs(1));
        assert!(error.is::<TimedOut>(), "{error}");
        assert_eq!(error.to_string(), "request timed out after 1s");
    }

    #[tokio::test(start_paused = true)]
    async fn the_service_error_passes_through() {
        let failing = tower::service_fn(|()| async { Err::<(), _>(BoxError::from("refused")) });
        let error = TimeoutLayer::new(Duration::from_secs(1))
            .layer(failing)
            .oneshot(())
            .await
            .unwrap_err();
        assert_eq!(error.to_string(), "refused");
    }
}
