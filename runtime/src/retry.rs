//! Retries for HTTP APIs: [`RetryPolicy`], a tower retry policy, and [`RetryLayer`], which adds to it a look at the
//! body of a `429` to tell a rate limit (retried) from an exhausted quota (not retried).
//!
//! `Client::default()` already retries with `RetryLayer::new(RetryPolicy::default())`. For another policy, start from
//! the bare service:
//!
//! ```no_run
//! # #[cfg(feature = "reqwest")]
//! # fn demo() {
//! use timvw_rust_runtime::retry::{self, RetryLayer, RetryPolicy};
//! use timvw_rust_runtime::{Client, ReqwestService};
//!
//! let client = Client::from_service(ReqwestService::default())
//!     .layer(RetryLayer::new(RetryPolicy::new(5)).permanent_429(retry::openai_insufficient_quota));
//! # }
//! ```

use std::fmt;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};
use std::time::Duration;

use bytes::{Bytes, BytesMut};
use http::header::RETRY_AFTER;
use http::{HeaderMap, StatusCode};
use http_body_util::{BodyDataStream, BodyExt};
use tower::retry::Policy;
use tower::{Layer, Service};

use crate::{Body, BoxError};

/// The header OpenAI (and others) send next to `Retry-After`, in milliseconds.
const RETRY_AFTER_MS: &str = "retry-after-ms";

/// How much of a `429` body [`RetryLayer`] reads by default to classify it: 64 KiB.
pub const DEFAULT_BODY_LIMIT: usize = 64 * 1024;

/// Retries `429 Too Many Requests`, `408 Request Timeout` and `5xx` responses, and failures to connect.
///
/// - It waits as long as the server asks, with `retry-after-ms` (milliseconds) or `Retry-After` (seconds), up to
///   [`RetryPolicy::max_retry_after`] (60 s by default). A server that asks for longer gets its response returned at
///   once: waiting less than it asked would likely fail again, and waiting an hour is rarely what a caller wants.
/// - Without those headers it backs off exponentially from [`RetryPolicy::base_delay`] (200 ms), doubling on each retry
///   up to [`RetryPolicy::max_delay`] (8 s), with jitter: each delay is picked at random between half and all of it, so
///   that clients that failed together do not retry together. [`RetryPolicy::jitter`] turns that off.
/// - It never retries a response marked [`Permanent`] (see [`RetryLayer::permanent_429`]), nor a request whose body is
///   a stream (a streamed file upload), which cannot be sent again.
#[derive(Clone, Debug)]
pub struct RetryPolicy {
    max_retries: usize,
    attempts: usize,
    base_delay: Duration,
    max_delay: Duration,
    max_retry_after: Duration,
    jitter: bool,
}

impl RetryPolicy {
    /// A policy that retries at most `max_retries` times (after the first attempt).
    pub fn new(max_retries: usize) -> Self {
        Self {
            max_retries,
            attempts: 0,
            base_delay: Duration::from_millis(200),
            max_delay: Duration::from_secs(8),
            max_retry_after: Duration::from_secs(60),
            jitter: true,
        }
    }

    /// The first backoff delay, doubled on each retry (default 200 ms).
    pub fn base_delay(mut self, delay: Duration) -> Self {
        self.base_delay = delay;
        self
    }

    /// The longest backoff delay (default 8 s). It does not limit how long a server can ask to wait with
    /// `Retry-After`: that is [`RetryPolicy::max_retry_after`].
    pub fn max_delay(mut self, delay: Duration) -> Self {
        self.max_delay = delay;
        self
    }

    /// The longest wait a server can ask for with `Retry-After` or `retry-after-ms` (default 60 s). A response that
    /// asks for longer is not retried but returned.
    pub fn max_retry_after(mut self, delay: Duration) -> Self {
        self.max_retry_after = delay;
        self
    }

    /// Whether each backoff delay is picked at random between half and all of it (default `true`). The waits a
    /// server asks for are never jittered.
    pub fn jitter(mut self, jitter: bool) -> Self {
        self.jitter = jitter;
        self
    }

    /// The number of retries allowed.
    pub fn max_retries(&self) -> usize {
        self.max_retries
    }

    /// How long to wait before the next retry, or `None` when the server asks for longer than `max_retry_after`.
    fn delay(&self, retry_after: Option<Duration>) -> Option<Duration> {
        if let Some(retry_after) = retry_after {
            return (retry_after <= self.max_retry_after).then_some(retry_after);
        }
        let backoff = self
            .base_delay
            .saturating_mul(1u32 << self.attempts.min(16))
            .min(self.max_delay);
        Some(if self.jitter {
            jittered(backoff, random_unit())
        } else {
            backoff
        })
    }
}

/// Three retries.
impl Default for RetryPolicy {
    fn default() -> Self {
        Self::new(3)
    }
}

/// `backoff` scaled to between half and all of it, by `unit` in `[0, 1)`.
fn jittered(backoff: Duration, unit: f64) -> Duration {
    backoff.mul_f64(0.5 + 0.5 * unit)
}

/// A random number in `[0, 1)`, from the standard library's randomly keyed hasher: good enough to spread retries.
fn random_unit() -> f64 {
    use std::hash::{BuildHasher, Hasher};
    let hash = std::collections::hash_map::RandomState::new()
        .build_hasher()
        .finish();
    (hash >> 11) as f64 / (1u64 << 53) as f64
}

/// Whether a response or error is worth retrying, and how long the server asked to wait.
fn retryable<B>(result: &Result<http::Response<B>, BoxError>) -> Option<Option<Duration>> {
    match result {
        Ok(response) => {
            let status = response.status();
            let transient = status == StatusCode::TOO_MANY_REQUESTS
                || status == StatusCode::REQUEST_TIMEOUT
                || status.is_server_error();
            let permanent = response.extensions().get::<Permanent>().is_some();
            (transient && !permanent).then(|| retry_after(response.headers()))
        }
        Err(error) => is_connect(error.as_ref()).then_some(None),
    }
}

/// The wait a server asks for: `retry-after-ms` in milliseconds, else `Retry-After` in seconds. Both may have a
/// fraction. `Retry-After` as an HTTP date is ignored (the policy backs off instead).
fn retry_after(headers: &HeaderMap) -> Option<Duration> {
    let parse = |name, scale: f64| {
        let value = headers
            .get(name)?
            .to_str()
            .ok()?
            .trim()
            .parse::<f64>()
            .ok()?;
        Duration::try_from_secs_f64(value * scale).ok()
    };
    parse(RETRY_AFTER_MS, 0.001).or_else(|| parse(RETRY_AFTER.as_str(), 1.0))
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
        let Some(delay) = self.delay(retry_after) else {
            #[cfg(feature = "tracing")]
            tracing::debug!(
                target: "timvw_rust_runtime::trace",
                retry_after_ms = retry_after.map_or(0, |delay| delay.as_millis() as u64),
                max_retry_after_ms = self.max_retry_after.as_millis() as u64,
                status = result.as_ref().ok().map(|response| response.status().as_u16()),
                "not retrying: the server asks to wait longer than max_retry_after"
            );
            return None;
        };
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

/// A response extension that tells [`RetryPolicy`] not to retry a response it would otherwise retry.
///
/// [`RetryLayer::permanent_429`] sets it on a `429` whose body says the error is permanent. A layer of your own, inside
/// the retries, can set it too: `response.extensions_mut().insert(Permanent)`.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub struct Permanent;

/// Whether the body of a `429` says the error is permanent, such as [`openai_insufficient_quota`].
type Classifier = Arc<dyn Fn(&[u8]) -> bool + Send + Sync>;

/// OpenAI's "quota exhausted": a JSON body whose `error.code` or `error.type` is `insufficient_quota`.
///
/// OpenAI answers `429` both when requests come too fast, which passes, and when the account's quota or credit is
/// used up, which does not pass by waiting. Use it with [`RetryLayer::permanent_429`].
pub fn openai_insufficient_quota(body: &[u8]) -> bool {
    const INSUFFICIENT_QUOTA: &str = "insufficient_quota";
    let Ok(value) = serde_json::from_slice::<serde_json::Value>(body) else {
        return false;
    };
    ["code", "type"].iter().any(|field| {
        value
            .pointer(&format!("/error/{field}"))
            .and_then(serde_json::Value::as_str)
            == Some(INSUFFICIENT_QUOTA)
    })
}

/// Retries with a [`RetryPolicy`], and optionally reads the body of a `429` to tell a permanent error from a rate
/// limit first.
///
/// `RetryLayer::new(policy)` retries exactly like `tower::retry::RetryLayer::new(policy)`. With
/// [`RetryLayer::permanent_429`], the body of each `429` is read (up to [`RetryLayer::body_limit`]) and given to a
/// function; when it returns `true`, the response is marked [`Permanent`] and returned at once. The body is put back,
/// so the caller (and a generated client's error) still sees all of it.
///
/// ```no_run
/// # #[cfg(feature = "reqwest")]
/// # fn demo() {
/// use timvw_rust_runtime::retry::{openai_insufficient_quota, RetryLayer, RetryPolicy};
/// use timvw_rust_runtime::{Client, ReqwestService};
///
/// let client = Client::from_service(ReqwestService::default())
///     .layer(RetryLayer::new(RetryPolicy::default()).permanent_429(openai_insufficient_quota));
/// # }
/// ```
#[derive(Clone)]
pub struct RetryLayer {
    policy: RetryPolicy,
    classify: Option<Classifier>,
    body_limit: usize,
}

impl RetryLayer {
    /// Retries with `policy`, without reading any body.
    pub fn new(policy: RetryPolicy) -> Self {
        Self {
            policy,
            classify: None,
            body_limit: DEFAULT_BODY_LIMIT,
        }
    }

    /// Reads the body of each `429` and does not retry it when `is_permanent` returns `true` for the body's bytes.
    ///
    /// A body longer than [`RetryLayer::body_limit`], or one that fails to read, is not given to `is_permanent`: the
    /// response is retried as a rate limit, and the caller still reads the whole body (or its error).
    pub fn permanent_429<F>(mut self, is_permanent: F) -> Self
    where
        F: Fn(&[u8]) -> bool + Send + Sync + 'static,
    {
        self.classify = Some(Arc::new(is_permanent));
        self
    }

    /// How many bytes of a `429` body are read to classify it (default [`DEFAULT_BODY_LIMIT`], 64 KiB).
    pub fn body_limit(mut self, limit: usize) -> Self {
        self.body_limit = limit;
        self
    }

    /// The retry policy.
    pub fn policy(&self) -> &RetryPolicy {
        &self.policy
    }
}

/// Retries with [`RetryPolicy::default`].
impl Default for RetryLayer {
    fn default() -> Self {
        Self::new(RetryPolicy::default())
    }
}

impl fmt::Debug for RetryLayer {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("RetryLayer")
            .field("policy", &self.policy)
            .field("permanent_429", &self.classify.is_some())
            .field("body_limit", &self.body_limit)
            .finish()
    }
}

impl<S> Layer<S> for RetryLayer {
    type Service = tower::retry::Retry<RetryPolicy, Classify429<S>>;

    fn layer(&self, inner: S) -> Self::Service {
        tower::retry::Retry::new(
            self.policy.clone(),
            Classify429 {
                inner,
                classify: self.classify.clone(),
                body_limit: self.body_limit,
            },
        )
    }
}

/// The service inside the retries of a [`RetryLayer`]: marks a `429` [`Permanent`] when its body says so.
#[derive(Clone)]
pub struct Classify429<S> {
    inner: S,
    classify: Option<Classifier>,
    body_limit: usize,
}

impl<S: fmt::Debug> fmt::Debug for Classify429<S> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Classify429")
            .field("inner", &self.inner)
            .field("permanent_429", &self.classify.is_some())
            .field("body_limit", &self.body_limit)
            .finish()
    }
}

impl<S, R, B> Service<R> for Classify429<S>
where
    S: Service<R, Response = http::Response<B>>,
    S::Future: Send + 'static,
    B: http_body::Body + Send + Sync + 'static,
    B::Data: Into<Bytes>,
    B::Error: Into<BoxError>,
{
    type Response = http::Response<Body>;
    type Error = S::Error;
    type Future = Pin<Box<dyn Future<Output = Result<Self::Response, Self::Error>> + Send>>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, request: R) -> Self::Future {
        let response = self.inner.call(request);
        let classify = self.classify.clone();
        let body_limit = self.body_limit;
        Box::pin(async move {
            let response = response.await?.map(Body::wrap);
            Ok(match classify {
                Some(classify) if response.status() == StatusCode::TOO_MANY_REQUESTS => {
                    classify_429(response, &*classify, body_limit).await
                }
                _ => response,
            })
        })
    }
}

/// Reads a `429` body, up to `limit` bytes, marks the response [`Permanent`] if `classify` says so, and puts the body
/// back.
async fn classify_429(
    response: http::Response<Body>,
    classify: &(dyn Fn(&[u8]) -> bool + Send + Sync),
    limit: usize,
) -> http::Response<Body> {
    let (mut parts, mut body) = response.into_parts();
    let mut read = BytesMut::new();
    let body = loop {
        match body.frame().await {
            None => {
                let read = read.freeze();
                if classify(&read) {
                    parts.extensions.insert(Permanent);
                    #[cfg(feature = "tracing")]
                    tracing::debug!(
                        target: "timvw_rust_runtime::trace",
                        status = parts.status.as_u16(),
                        "not retrying: the body says the error is permanent"
                    );
                }
                break Body::from(read);
            }
            // Trailers on a 429 are dropped.
            Some(Ok(frame)) => {
                let Ok(data) = frame.into_data() else {
                    continue;
                };
                read.extend_from_slice(&data);
                if read.len() > limit {
                    let read = futures_util::stream::once(async move { Ok(read.freeze()) });
                    break Body::wrap_stream(futures_util::StreamExt::chain(
                        read,
                        BodyDataStream::new(body),
                    ));
                }
            }
            Some(Err(error)) => {
                break Body::wrap_stream(futures_util::stream::iter([
                    Ok(read.freeze()),
                    Err::<Bytes, BoxError>(error),
                ]));
            }
        }
    };
    http::Response::from_parts(parts, body)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn response(status: u16, headers: &[(&str, &str)]) -> Result<http::Response<()>, BoxError> {
        let mut builder = http::Response::builder().status(status);
        for (name, value) in headers {
            builder = builder.header(*name, *value);
        }
        Ok(builder.body(()).unwrap())
    }

    #[test]
    fn retries_transient_statuses_only() {
        assert_eq!(retryable(&response(429, &[])), Some(None));
        assert_eq!(
            retryable(&response(503, &[("retry-after", "2")])),
            Some(Some(Duration::from_secs(2)))
        );
        assert_eq!(retryable(&response(408, &[])), Some(None));
        assert_eq!(retryable(&response(400, &[])), None);
        assert_eq!(retryable(&response(200, &[])), None);
        assert_eq!(retryable::<()>(&Err("boom".into())), None);
    }

    #[test]
    fn permanent_responses_are_not_retried() {
        let mut result = response(429, &[]);
        result.as_mut().unwrap().extensions_mut().insert(Permanent);
        assert_eq!(retryable(&result), None);
    }

    #[test]
    fn parses_retry_after_and_retry_after_ms() {
        let wait = |headers: &[(&str, &str)]| retryable(&response(429, headers)).unwrap();
        assert_eq!(
            wait(&[("retry-after", " 20 ")]),
            Some(Duration::from_secs(20))
        );
        assert_eq!(
            wait(&[("retry-after", "1.5")]),
            Some(Duration::from_millis(1500))
        );
        assert_eq!(
            wait(&[("retry-after-ms", "250"), ("retry-after", "1")]),
            Some(Duration::from_millis(250))
        );
        assert_eq!(
            wait(&[("retry-after-ms", "12.5")]),
            Some(Duration::from_micros(12_500))
        );
        assert_eq!(
            wait(&[("retry-after", "Wed, 21 Oct 2015 07:28:00 GMT")]),
            None
        );
        assert_eq!(wait(&[("retry-after", "-1")]), None);
        assert_eq!(
            wait(&[("retry-after-ms", "nan"), ("retry-after", "3")]),
            Some(Duration::from_secs(3))
        );
    }

    #[test]
    fn backs_off_and_caps() {
        let mut policy = RetryPolicy::new(10)
            .base_delay(Duration::from_millis(100))
            .max_delay(Duration::from_secs(1))
            .jitter(false);
        assert_eq!(policy.delay(None), Some(Duration::from_millis(100)));
        policy.attempts = 2;
        assert_eq!(policy.delay(None), Some(Duration::from_millis(400)));
        policy.attempts = 8;
        assert_eq!(policy.delay(None), Some(Duration::from_secs(1)));
    }

    #[test]
    fn honours_retry_after_beyond_max_delay_up_to_max_retry_after() {
        let policy = RetryPolicy::new(3).max_delay(Duration::from_secs(1));
        assert_eq!(
            policy.delay(Some(Duration::from_secs(30))),
            Some(Duration::from_secs(30))
        );
        assert_eq!(
            policy.delay(Some(Duration::from_secs(60))),
            Some(Duration::from_secs(60))
        );
        assert_eq!(policy.delay(Some(Duration::from_secs(61))), None);
        let policy = policy.max_retry_after(Duration::from_secs(5));
        assert_eq!(policy.delay(Some(Duration::from_secs(6))), None);
    }

    #[test]
    fn jitter_stays_between_half_and_all_of_the_backoff() {
        let backoff = Duration::from_millis(800);
        assert_eq!(jittered(backoff, 0.0), Duration::from_millis(400));
        assert_eq!(jittered(backoff, 0.5), Duration::from_millis(600));
        assert!(jittered(backoff, 0.999_999) <= backoff);

        let mut policy = RetryPolicy::new(10)
            .base_delay(Duration::from_millis(100))
            .max_delay(Duration::from_secs(1));
        policy.attempts = 3; // 800 ms before jitter
        let delays: Vec<_> = (0..200).map(|_| policy.delay(None).unwrap()).collect();
        for delay in &delays {
            assert!(
                (Duration::from_millis(400)..=backoff).contains(delay),
                "{delay:?}"
            );
        }
        assert!(delays.iter().any(|delay| *delay != delays[0]), "random");
        // The waits a server asks for are not jittered.
        assert_eq!(
            policy.delay(Some(Duration::from_secs(2))),
            Some(Duration::from_secs(2))
        );
    }

    #[test]
    fn random_unit_is_in_range() {
        for _ in 0..1000 {
            assert!((0.0..1.0).contains(&random_unit()));
        }
    }

    #[test]
    fn recognises_openai_insufficient_quota() {
        assert!(openai_insufficient_quota(
            br#"{"error":{"message":"You exceeded your current quota","type":"insufficient_quota","param":null,"code":"insufficient_quota"}}"#
        ));
        assert!(openai_insufficient_quota(
            br#"{"error":{"type":"insufficient_quota"}}"#
        ));
        assert!(openai_insufficient_quota(
            br#"{"error":{"type":"requests","code":"insufficient_quota"}}"#
        ));
        assert!(!openai_insufficient_quota(
            br#"{"error":{"message":"Rate limit reached","type":"requests","code":"rate_limit_exceeded"}}"#
        ));
        assert!(!openai_insufficient_quota(b"insufficient_quota"));
        assert!(!openai_insufficient_quota(b""));
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

    fn body_429(chunks: Vec<Result<&'static str, &'static str>>) -> http::Response<Body> {
        let stream = futures_util::stream::iter(
            chunks
                .into_iter()
                .map(|chunk| chunk.map(Bytes::from).map_err(BoxError::from)),
        );
        http::Response::builder()
            .status(429)
            .body(Body::wrap_stream(stream))
            .unwrap()
    }

    #[tokio::test]
    async fn classifies_the_whole_body_and_puts_it_back() {
        let response = body_429(vec![
            Ok(r#"{"error":{"type":"#),
            Ok(r#""insufficient_quota"}}"#),
        ]);
        let response = classify_429(response, &openai_insufficient_quota, 1024).await;
        assert!(response.extensions().get::<Permanent>().is_some());
        let body = response.into_body().collect_bytes().await.unwrap();
        assert_eq!(body, r#"{"error":{"type":"insufficient_quota"}}"#);
    }

    #[tokio::test]
    async fn a_body_over_the_limit_is_not_classified_but_kept_whole() {
        let response = body_429(vec![Ok("0123"), Ok("4567"), Ok("89")]);
        let response = classify_429(response, &|_: &[u8]| true, 5).await;
        assert!(response.extensions().get::<Permanent>().is_none());
        let body = response.into_body().collect_bytes().await.unwrap();
        assert_eq!(body, "0123456789");
    }

    #[tokio::test]
    async fn a_body_that_fails_is_not_classified_and_keeps_its_error() {
        let response = body_429(vec![Ok("01"), Err("reset")]);
        let response = classify_429(response, &|_: &[u8]| true, 1024).await;
        assert!(response.extensions().get::<Permanent>().is_none());
        let error = response.into_body().collect_bytes().await.unwrap_err();
        assert_eq!(error.to_string(), "reset");
    }
}
