//! `Retry-After` as a deadline (seconds, milliseconds, HTTP dates) and 429s retried only on the server's advice.
#![cfg(feature = "retry")]

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::{Duration, SystemTime};

use timvw_rust_runtime::retry::{RetryLayer, RetryPolicy};
use timvw_rust_runtime::{http, Body, BoxError, Client, Method, StatusCode};

/// A response: status, headers, and the delay before its body's one chunk.
type Scripted = (u16, Vec<(&'static str, String)>, Duration);

/// A service that answers with `responses` in turn, and counts the calls.
fn scripted(responses: Vec<Scripted>) -> (Client, Arc<AtomicUsize>) {
    let calls = Arc::new(AtomicUsize::new(0));
    let counter = calls.clone();
    let service = tower::service_fn(move |_: http::Request<Body>| {
        let call = counter.fetch_add(1, Ordering::SeqCst);
        let (status, headers, body_delay) = responses[call.min(responses.len() - 1)].clone();
        async move {
            let mut response = http::Response::builder().status(status);
            for (name, value) in headers {
                response = response.header(name, value);
            }
            let body = futures_util::stream::once(async move {
                tokio::time::sleep(body_delay).await;
                Ok::<_, BoxError>(bytes::Bytes::from_static(b"body"))
            });
            Ok::<_, BoxError>(response.body(Body::wrap_stream(body))?)
        }
    });
    (Client::from_service(service), calls)
}

fn answer(status: u16, headers: &[(&'static str, &str)]) -> Scripted {
    let headers = headers
        .iter()
        .map(|(name, value)| (*name, (*value).to_owned()))
        .collect();
    (status, headers, Duration::ZERO)
}

/// Sends a GET, and returns the status, the number of calls and how long it took.
async fn send(client: &Client, calls: &AtomicUsize) -> (StatusCode, usize, Duration) {
    let started = tokio::time::Instant::now();
    let response = client
        .request(Method::GET, "http://example.invalid/")
        .send()
        .await
        .unwrap();
    (
        response.status(),
        calls.load(Ordering::SeqCst),
        started.elapsed(),
    )
}

fn in_secs(secs: u64) -> String {
    httpdate::fmt_http_date(SystemTime::now() + Duration::from_secs(secs))
}

#[tokio::test(start_paused = true)]
async fn an_http_date_in_the_future_is_waited_for() {
    let date = in_secs(30);
    let (client, calls) = scripted(vec![
        answer(503, &[("retry-after", &date)]),
        answer(200, &[]),
    ]);
    let client = client.layer(RetryLayer::default());
    let (status, calls, waited) = send(&client, &calls).await;
    assert_eq!((status, calls), (StatusCode::OK, 2));
    // An HTTP date has whole seconds: between 29 and 30 s from now.
    assert!(
        (Duration::from_secs(29)..=Duration::from_secs(30)).contains(&waited),
        "{waited:?}"
    );
}

#[tokio::test(start_paused = true)]
async fn an_http_date_in_the_past_is_retried_at_once() {
    let date = httpdate::fmt_http_date(SystemTime::now() - Duration::from_secs(3600));
    let (client, calls) = scripted(vec![
        answer(429, &[("retry-after", &date)]),
        answer(200, &[]),
    ]);
    // With a long backoff, to tell a retry at once from one that backs off.
    let policy = RetryPolicy::default().base_delay(Duration::from_secs(5));
    let client = client.layer(RetryLayer::new(policy));
    let (status, calls, waited) = send(&client, &calls).await;
    assert_eq!((status, calls), (StatusCode::OK, 2));
    assert_eq!(waited, Duration::ZERO);
}

#[tokio::test(start_paused = true)]
async fn an_http_date_beyond_max_retry_after_is_returned() {
    let date = in_secs(3600);
    let (client, calls) = scripted(vec![
        answer(429, &[("retry-after", &date)]),
        answer(200, &[]),
    ]);
    let client = client.layer(RetryLayer::default());
    let (status, calls, waited) = send(&client, &calls).await;
    assert_eq!((status, calls), (StatusCode::TOO_MANY_REQUESTS, 1));
    assert_eq!(waited, Duration::ZERO);
}

#[tokio::test(start_paused = true)]
async fn reading_a_429_body_does_not_add_to_retry_after() {
    // The body of the 429 takes 2 s to arrive; the server asked for 5 s from its response.
    let (client, calls) = scripted(vec![
        (
            429,
            vec![("retry-after", "5".to_owned())],
            Duration::from_secs(2),
        ),
        answer(200, &[]),
    ]);
    let client = client.layer(RetryLayer::default().permanent_429(|_: &[u8]| false));
    let (status, calls, waited) = send(&client, &calls).await;
    assert_eq!((status, calls), (StatusCode::OK, 2));
    assert_eq!(waited, Duration::from_secs(5));

    // The same with `retry-after-ms`.
    let (client, calls) = scripted(vec![
        (
            503,
            vec![("retry-after-ms", "1500".to_owned())],
            Duration::from_millis(1000),
        ),
        answer(200, &[]),
    ]);
    // Only 429 bodies are read: a slow 503 body is not waited for, and the wait is the whole 1.5 s.
    let client = client.layer(RetryLayer::default().permanent_429(|_: &[u8]| false));
    let (status, calls, waited) = send(&client, &calls).await;
    assert_eq!((status, calls), (StatusCode::OK, 2));
    assert_eq!(waited, Duration::from_millis(1500));
}

#[tokio::test(start_paused = true)]
async fn server_directed_429s_are_retried_only_with_advice() {
    let policy = RetryPolicy::default()
        .base_delay(Duration::from_millis(100))
        .server_directed_429(true);

    let (client, calls) = scripted(vec![answer(429, &[]), answer(200, &[])]);
    let client = client.layer(RetryLayer::new(policy.clone()));
    let (status, calls, waited) = send(&client, &calls).await;
    assert_eq!((status, calls), (StatusCode::TOO_MANY_REQUESTS, 1));
    assert_eq!(waited, Duration::ZERO);

    let (client, calls) = scripted(vec![
        answer(429, &[("retry-after", "2")]),
        answer(429, &[("retry-after-ms", "500")]),
        answer(200, &[]),
    ]);
    let client = client.layer(RetryLayer::new(policy.clone()));
    let (status, calls, waited) = send(&client, &calls).await;
    assert_eq!((status, calls), (StatusCode::OK, 3));
    assert_eq!(waited, Duration::from_millis(2500));

    // Other transient statuses still back off without advice.
    let (client, calls) = scripted(vec![answer(503, &[]), answer(200, &[])]);
    let client = client.layer(RetryLayer::new(policy.jitter(false)));
    let (status, calls, waited) = send(&client, &calls).await;
    assert_eq!((status, calls), (StatusCode::OK, 2));
    assert_eq!(waited, Duration::from_millis(100));
}

#[tokio::test(start_paused = true)]
async fn by_default_a_plain_429_backs_off() {
    let (client, calls) = scripted(vec![answer(429, &[]), answer(200, &[])]);
    let policy = RetryPolicy::default()
        .base_delay(Duration::from_millis(100))
        .jitter(false);
    let client = client.layer(RetryLayer::new(policy));
    let (status, calls, waited) = send(&client, &calls).await;
    assert_eq!((status, calls), (StatusCode::OK, 2));
    assert_eq!(waited, Duration::from_millis(100));
}
