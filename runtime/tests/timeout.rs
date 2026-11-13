//! `timeout::TimeoutLayer`: a timeout of the whole call or of each attempt, and how it composes with `RetryLayer`.
#![cfg(feature = "retry")]

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

use timvw_rust_runtime::retry::{RetryLayer, RetryPolicy};
use timvw_rust_runtime::timeout::TimeoutLayer;
use timvw_rust_runtime::{http, Body, BoxError, Client, Error, Method, Response, StatusCode};
use tokio::time::Instant;

/// A service that answers call `n` with `script[n]` (then the last): a status after a delay. Counts the calls.
fn scripted(script: Vec<(u16, Duration)>) -> (Client, Arc<AtomicUsize>) {
    let calls = Arc::new(AtomicUsize::new(0));
    let counter = calls.clone();
    let service = tower::service_fn(move |_: http::Request<Body>| {
        let call = counter.fetch_add(1, Ordering::SeqCst);
        let (status, delay) = script[call.min(script.len() - 1)];
        async move {
            tokio::time::sleep(delay).await;
            Ok::<_, BoxError>(
                http::Response::builder()
                    .status(status)
                    .body(Body::from("body"))?,
            )
        }
    });
    (Client::from_service(service), calls)
}

async fn send(client: &Client) -> (Result<Response, Error>, Duration) {
    let started = Instant::now();
    let result = client
        .request(Method::GET, "http://example.invalid/")
        .send()
        .await;
    (result, started.elapsed())
}

fn policy() -> RetryPolicy {
    RetryPolicy::new(3)
        .base_delay(Duration::from_secs(1))
        .jitter(false)
}

#[tokio::test(start_paused = true)]
async fn a_slow_response_times_out() {
    let (client, _) = scripted(vec![(200, Duration::from_secs(60))]);
    let client = client.layer(TimeoutLayer::new(Duration::from_secs(10)));
    let (result, took) = send(&client).await;
    let error = result.unwrap_err();
    assert!(error.is_timeout() && error.is_request(), "{error:?}");
    assert_eq!(
        error.to_string(),
        "error sending the request: request timed out after 10s"
    );
    assert_eq!(took, Duration::from_secs(10));
}

#[tokio::test(start_paused = true)]
async fn a_response_in_time_is_returned() {
    let (client, _) = scripted(vec![(200, Duration::from_secs(9))]);
    let client = client.layer(TimeoutLayer::new(Duration::from_secs(10)));
    let (result, _) = send(&client).await;
    assert_eq!(result.unwrap().status(), StatusCode::OK);
}

/// Outside the retries, the timeout bounds the whole call: attempts and the waits between them.
#[tokio::test(start_paused = true)]
async fn outside_the_retries_it_bounds_the_whole_call() {
    // 503 at once, three times, backing off 1 s, 2 s, 4 s: 7 s without a timeout.
    let (client, calls) = scripted(vec![(503, Duration::ZERO)]);
    let client = client
        .layer(RetryLayer::new(policy()))
        .layer(TimeoutLayer::new(Duration::from_secs(5)));
    let (result, took) = send(&client).await;
    assert!(result.unwrap_err().is_timeout());
    assert_eq!(took, Duration::from_secs(5));
    // Sent at 0 s, 1 s and 3 s; the retry due at 7 s never is.
    assert_eq!(calls.load(Ordering::SeqCst), 3);
}

/// Inside the retries, it bounds each attempt; an attempt that timed out is retried with `retry_timeouts`.
#[tokio::test(start_paused = true)]
async fn inside_the_retries_it_bounds_each_attempt() {
    let (client, calls) = scripted(vec![
        (200, Duration::from_secs(60)),
        (200, Duration::from_secs(60)),
        (200, Duration::from_secs(1)),
    ]);
    let client = client
        .layer(TimeoutLayer::new(Duration::from_secs(10)))
        .layer(RetryLayer::new(policy().retry_timeouts(true)));
    let (result, took) = send(&client).await;
    assert_eq!(result.unwrap().status(), StatusCode::OK);
    assert_eq!(calls.load(Ordering::SeqCst), 3);
    // 10 s, a 1 s backoff, 10 s, a 2 s backoff, 1 s.
    assert_eq!(took, Duration::from_secs(24));
}

/// By default a request that timed out is not retried: it may have reached the server.
#[tokio::test(start_paused = true)]
async fn by_default_a_timed_out_attempt_is_not_retried() {
    let (client, calls) = scripted(vec![(200, Duration::from_secs(60)), (200, Duration::ZERO)]);
    let client = client
        .layer(TimeoutLayer::new(Duration::from_secs(10)))
        .layer(RetryLayer::new(policy()));
    let (result, took) = send(&client).await;
    assert!(result.unwrap_err().is_timeout());
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    assert_eq!(took, Duration::from_secs(10));
}

/// Each attempt and the whole call: the outer timeout ends retries the inner one keeps starting.
#[tokio::test(start_paused = true)]
async fn per_attempt_and_overall_timeouts_compose() {
    let (client, calls) = scripted(vec![(200, Duration::from_secs(60))]);
    let client = client
        .layer(TimeoutLayer::new(Duration::from_secs(10)))
        .layer(RetryLayer::new(policy().retry_timeouts(true)))
        .layer(TimeoutLayer::new(Duration::from_secs(25)));
    let (result, took) = send(&client).await;
    assert!(result.unwrap_err().is_timeout());
    assert_eq!(took, Duration::from_secs(25));
    // At 0 s, at 11 s (10 s and a 1 s backoff), and at 23 s (10 s and 2 s).
    assert_eq!(calls.load(Ordering::SeqCst), 3);
}
