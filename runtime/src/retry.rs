//! A retry policy for HTTP APIs, for tower's `RetryLayer`.
//!
//! `Client::default()` already retries with `RetryPolicy::default()`. For another policy, start from the bare service:
//!
//! ```no_run
//! # #[cfg(feature = "reqwest")]
//! # fn demo() {
//! use timvw_rust_runtime::{retry::RetryPolicy, Client, ReqwestService};
//!
//! let client = Client::from_service(ReqwestService::default())
//!     .layer(tower::retry::RetryLayer::new(RetryPolicy::new(5)));
//! # }
//! ```

use std::future::Future;
use std::pin::Pin;
use std::time::Duration;

use http::header::RETRY_AFTER;
use http::StatusCode;
use tower::retry::Policy;

use crate::{Body, BoxError};

/// Retries `429 Too Many Requests`, `408 Request Timeout` and `5xx` responses, and failures to connect.
///
/// It waits as long as a `Retry-After` header (in seconds) asks, or else backs off exponentially from
/// [`RetryPolicy::base_delay`] up to [`RetryPolicy::max_delay`]. A request whose body is a stream (a streamed file
/// upload) cannot be sent again and is not retried.
#[derive(Clone, Debug)]
pub struct RetryPolicy {
    max_retries: usize,
    attempts: usize,
    base_delay: Duration,
    max_delay: Duration,
}

impl RetryPolicy {
    /// A policy that retries at most `max_retries` times (after the first attempt).
    pub fn new(max_retries: usize) -> Self {
        Self {
            max_retries,
            attempts: 0,
            base_delay: Duration::from_millis(200),
            max_delay: Duration::from_secs(8),
        }
    }

    /// The first backoff delay, doubled on each retry (default 200 ms).
    pub fn base_delay(mut self, delay: Duration) -> Self {
        self.base_delay = delay;
        self
    }

    /// The longest backoff delay, also the cap on `Retry-After` (default 8 s).
    pub fn max_delay(mut self, delay: Duration) -> Self {
        self.max_delay = delay;
        self
    }

    /// The number of retries allowed.
    pub fn max_retries(&self) -> usize {
        self.max_retries
    }

    fn delay(&self, retry_after: Option<Duration>) -> Duration {
        let backoff = self
            .base_delay
            .saturating_mul(1u32 << self.attempts.min(16));
        retry_after.unwrap_or(backoff).min(self.max_delay)
    }
}

/// Three retries.
impl Default for RetryPolicy {
    fn default() -> Self {
        Self::new(3)
    }
}

/// Whether a response or error is worth retrying, and how long the server asked to wait.
fn retryable<B>(result: &Result<http::Response<B>, BoxError>) -> Option<Option<Duration>> {
    match result {
        Ok(response) => {
            let status = response.status();
            let transient = status == StatusCode::TOO_MANY_REQUESTS
                || status == StatusCode::REQUEST_TIMEOUT
                || status.is_server_error();
            transient.then(|| {
                response
                    .headers()
                    .get(RETRY_AFTER)
                    .and_then(|value| value.to_str().ok())
                    .and_then(|value| value.trim().parse::<u64>().ok())
                    .map(Duration::from_secs)
            })
        }
        Err(error) => is_connect(error.as_ref()).then_some(None),
    }
}

#[allow(unused_variables)]
fn is_connect(error: &(dyn std::error::Error + 'static)) -> bool {
    #[cfg(feature = "reqwest")]
    {
        let mut source = Some(error);
        while let Some(error) = source {
            if error
                .downcast_ref::<reqwest::Error>()
                .is_some_and(reqwest::Error::is_connect)
            {
                return true;
            }
            source = error.source();
        }
    }
    false
}

impl<B> Policy<http::Request<Body>, http::Response<B>, BoxError> for RetryPolicy {
    type Future = Pin<Box<dyn Future<Output = ()> + Send>>;

    fn retry(
        &mut self,
        _request: &mut http::Request<Body>,
        result: &mut Result<http::Response<B>, BoxError>,
    ) -> Option<Self::Future> {
        if self.attempts >= self.max_retries {
            return None;
        }
        let retry_after = retryable(result)?;
        let delay = self.delay(retry_after);
        self.attempts += 1;
        #[cfg(feature = "tracing")]
        tracing::debug!(
            target: "timvw_rust_runtime::trace",
            attempt = self.attempts,
            max_retries = self.max_retries,
            delay_ms = delay.as_millis() as u64,
            status = result.as_ref().ok().map(|response| response.status().as_u16()),
            "retrying"
        );
        Some(Box::pin(tokio::time::sleep(delay)))
    }

    fn clone_request(&mut self, request: &http::Request<Body>) -> Option<http::Request<Body>> {
        let body = request.body().try_clone()?;
        let mut clone = http::Request::new(body);
        *clone.method_mut() = request.method().clone();
        *clone.uri_mut() = request.uri().clone();
        *clone.version_mut() = request.version();
        *clone.headers_mut() = request.headers().clone();
        Some(clone)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn response(status: u16, retry_after: Option<&str>) -> Result<http::Response<()>, BoxError> {
        let mut builder = http::Response::builder().status(status);
        if let Some(value) = retry_after {
            builder = builder.header(RETRY_AFTER, value);
        }
        Ok(builder.body(()).unwrap())
    }

    #[test]
    fn retries_transient_statuses_only() {
        assert_eq!(retryable(&response(429, None)), Some(None));
        assert_eq!(
            retryable(&response(503, Some("2"))),
            Some(Some(Duration::from_secs(2)))
        );
        assert_eq!(retryable(&response(408, None)), Some(None));
        assert_eq!(retryable(&response(400, None)), None);
        assert_eq!(retryable(&response(200, None)), None);
        assert_eq!(retryable::<()>(&Err("boom".into())), None);
    }

    #[test]
    fn backs_off_and_caps() {
        let mut policy = RetryPolicy::new(10)
            .base_delay(Duration::from_millis(100))
            .max_delay(Duration::from_secs(1));
        assert_eq!(policy.delay(None), Duration::from_millis(100));
        policy.attempts = 2;
        assert_eq!(policy.delay(None), Duration::from_millis(400));
        policy.attempts = 8;
        assert_eq!(policy.delay(None), Duration::from_secs(1));
        assert_eq!(
            policy.delay(Some(Duration::from_secs(30))),
            Duration::from_secs(1)
        );
    }

    #[test]
    fn only_bodies_in_memory_are_cloned() {
        let mut policy = RetryPolicy::default();
        let mut clone_request = |request: &http::Request<Body>| {
            Policy::<_, http::Response<()>, BoxError>::clone_request(&mut policy, request)
        };
        let request = http::Request::post("https://example.com")
            .header("x", "1")
            .body(Body::from("a"))
            .unwrap();
        let clone = clone_request(&request).unwrap();
        assert_eq!(clone.uri(), request.uri());
        assert_eq!(clone.headers()["x"], "1");
        let chunks =
            futures_util::stream::iter(vec![Ok::<_, std::io::Error>(bytes::Bytes::from("a"))]);
        let streamed = http::Request::post("https://example.com")
            .body(Body::wrap_stream(chunks))
            .unwrap();
        assert!(clone_request(&streamed).is_none());
    }
}
