//! The client end to end: reqwest against a local server, retries, layers and a custom service.
#![cfg(all(feature = "reqwest", feature = "retry"))]

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

use timvw_rust_runtime::multipart::{Form, Part};
use timvw_rust_runtime::retry::{self, RetryPolicy};
use timvw_rust_runtime::{Body, Client, Method, ReqwestService, StatusCode};
use tower::retry::RetryLayer;
use wiremock::matchers::{body_string_contains, header, header_regex, method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

/// reqwest without the default retries, so a test sets its own layers.
fn bare() -> Client {
    Client::from_service(ReqwestService::default())
}

fn fast_retries(max: usize) -> RetryLayer<RetryPolicy> {
    RetryLayer::new(RetryPolicy::new(max).base_delay(Duration::from_millis(1)))
}

#[tokio::test]
async fn sends_json_with_query_and_auth() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/items"))
        .and(query_param("limit", "10"))
        .and(header("authorization", "Bearer token"))
        .and(header("content-type", "application/json"))
        .and(body_string_contains(r#""name":"x""#))
        .respond_with(ResponseTemplate::new(201).set_body_string(r#"{"id":1}"#))
        .mount(&server)
        .await;

    let response = bare()
        .request(Method::POST, &format!("{}/items", server.uri()))
        .query(&[("limit", 10)])
        .bearer_auth("token")
        .json(&serde_json::json!({"name": "x"}))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::CREATED);
    assert_eq!(response.text().await.unwrap(), r#"{"id":1}"#);
}

#[tokio::test]
async fn sends_multipart() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(header_regex(
            "content-type",
            "^multipart/form-data; boundary=",
        ))
        .and(body_string_contains("name=\"purpose\"\r\n\r\nfine-tune"))
        .and(body_string_contains("filename=\"data.jsonl\""))
        .respond_with(ResponseTemplate::new(200))
        .mount(&server)
        .await;

    let form = Form::new().text("purpose", "fine-tune").part(
        "file",
        Part::bytes(b"{}\n".to_vec()).file_name("data.jsonl"),
    );
    let response = bare()
        .request(Method::POST, &format!("{}/files", server.uri()))
        .multipart(form)
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
}

#[tokio::test]
async fn retries_rate_limits_then_succeeds() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(429))
        .up_to_n_times(2)
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&server)
        .await;

    let client = bare().layer(fast_retries(3));
    let response = client
        .request(Method::POST, &server.uri())
        .json(&serde_json::json!({}))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(server.received_requests().await.unwrap().len(), 3);
}

#[tokio::test]
async fn gives_up_after_max_retries() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(503))
        .mount(&server)
        .await;

    let client = bare().layer(fast_retries(2));
    let response = client
        .request(Method::GET, &server.uri())
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(server.received_requests().await.unwrap().len(), 3);
}

#[tokio::test]
async fn a_timeout_layer_fails_the_request() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(200).set_delay(Duration::from_secs(5)))
        .mount(&server)
        .await;

    let client = bare().layer(tower::timeout::TimeoutLayer::new(Duration::from_millis(50)));
    let error = client
        .request(Method::GET, &server.uri())
        .send()
        .await
        .unwrap_err();
    assert!(error.is_request());
    assert!(error.is_timeout());
}

#[tokio::test]
async fn any_service_can_send() {
    let calls = Arc::new(AtomicUsize::new(0));
    let counter = calls.clone();
    let service = tower::service_fn(move |request: http::Request<Body>| {
        counter.fetch_add(1, Ordering::SeqCst);
        async move {
            let body = request.into_body().collect_bytes().await?;
            Ok::<_, timvw_rust_runtime::BoxError>(http::Response::new(Body::from(body)))
        }
    });

    let client = Client::from_service(service);
    let response = client
        .request(Method::PUT, "http://example.invalid/echo")
        .body("ping")
        .send()
        .await
        .unwrap();
    assert_eq!(response.text().await.unwrap(), "ping");
    assert_eq!(calls.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn streams_the_response_body() {
    use futures_util::TryStreamExt;

    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(200).set_body_string("data: 1\n\ndata: 2\n\n"))
        .mount(&server)
        .await;

    let response = bare()
        .request(Method::GET, &server.uri())
        .send()
        .await
        .unwrap();
    let chunks: Vec<_> = response.bytes_stream().try_collect().await.unwrap();
    assert_eq!(chunks.concat(), b"data: 1\n\ndata: 2\n\n");
}

#[tokio::test]
async fn the_default_client_retries() {
    let server = MockServer::start().await;

    for client in [
        Client::default(),
        Client::from_reqwest(reqwest::Client::new()),
    ] {
        server.reset().await;
        Mock::given(method("GET"))
            .respond_with(ResponseTemplate::new(503).insert_header("retry-after", "0"))
            .up_to_n_times(1)
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .respond_with(ResponseTemplate::new(200))
            .mount(&server)
            .await;
        let response = client
            .request(Method::GET, &server.uri())
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(server.received_requests().await.unwrap().len(), 2);
    }

    // from_service is used as is: no retries.
    server.reset().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(503))
        .mount(&server)
        .await;
    let response = bare()
        .request(Method::GET, &server.uri())
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(server.received_requests().await.unwrap().len(), 1);
}

const QUOTA_EXHAUSTED: &str = r#"{"error":{"message":"You exceeded your current quota, please check your plan and billing details.","type":"insufficient_quota","param":null,"code":"insufficient_quota"}}"#;
const RATE_LIMITED: &str = r#"{"error":{"message":"Rate limit reached","type":"requests","param":null,"code":"rate_limit_exceeded"}}"#;

fn openai_retries(max: usize) -> retry::RetryLayer {
    retry::RetryLayer::new(RetryPolicy::new(max).base_delay(Duration::from_millis(1)))
        .permanent_429(retry::openai_insufficient_quota)
}

#[tokio::test]
async fn an_exhausted_quota_is_not_retried_and_its_body_is_kept() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(429).set_body_string(QUOTA_EXHAUSTED))
        .mount(&server)
        .await;

    let client = bare().layer(openai_retries(3));
    let response = client
        .request(Method::POST, &server.uri())
        .json(&serde_json::json!({}))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(response.text().await.unwrap(), QUOTA_EXHAUSTED);
    assert_eq!(server.received_requests().await.unwrap().len(), 1);
}

#[tokio::test]
async fn a_rate_limit_is_retried_with_the_quota_check_on() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(429).set_body_string(RATE_LIMITED))
        .up_to_n_times(2)
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(200).set_body_string("ok"))
        .mount(&server)
        .await;

    let client = bare().layer(openai_retries(3));
    let response = client
        .request(Method::POST, &server.uri())
        .json(&serde_json::json!({}))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.text().await.unwrap(), "ok");
    assert_eq!(server.received_requests().await.unwrap().len(), 3);

    // Out of retries, the last 429 comes back with its body.
    server.reset().await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(429).set_body_string(RATE_LIMITED))
        .mount(&server)
        .await;
    let response = client
        .request(Method::POST, &server.uri())
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(response.text().await.unwrap(), RATE_LIMITED);
    assert_eq!(server.received_requests().await.unwrap().len(), 4);
}

#[tokio::test]
async fn the_default_client_is_provider_neutral_and_retries_an_exhausted_quota() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(
            ResponseTemplate::new(429)
                .insert_header("retry-after", "0")
                .set_body_string(QUOTA_EXHAUSTED),
        )
        .mount(&server)
        .await;
    let response = Client::default()
        .request(Method::GET, &server.uri())
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(server.received_requests().await.unwrap().len(), 4);
}

/// A service that answers with `responses` in turn, and counts the calls.
fn scripted(responses: Vec<(u16, &'static str, &'static str)>) -> (Client, Arc<AtomicUsize>) {
    let calls = Arc::new(AtomicUsize::new(0));
    let counter = calls.clone();
    let service = tower::service_fn(move |_: http::Request<Body>| {
        let call = counter.fetch_add(1, Ordering::SeqCst);
        let (status, header, value) = responses[call.min(responses.len() - 1)];
        let mut response = http::Response::builder().status(status);
        if !header.is_empty() {
            response = response.header(header, value);
        }
        let response = response.body(Body::from("body"));
        async move { Ok::<_, timvw_rust_runtime::BoxError>(response?) }
    });
    (Client::from_service(service), calls)
}

#[tokio::test(start_paused = true)]
async fn retry_after_beyond_the_backoff_cap_is_honoured() {
    let (client, calls) = scripted(vec![(429, "retry-after", "20"), (200, "", "")]);
    // max_delay (8 s by default) caps the backoff only, not the wait a server asks for.
    let client = client.layer(retry::RetryLayer::new(RetryPolicy::default()));
    let started = tokio::time::Instant::now();
    let response = client
        .request(Method::GET, "http://example.invalid/")
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(calls.load(Ordering::SeqCst), 2);
    let waited = started.elapsed();
    assert!(
        (Duration::from_secs(20)..Duration::from_secs(21)).contains(&waited),
        "{waited:?}"
    );

    let (client, calls) = scripted(vec![(503, "retry-after-ms", "1500"), (200, "", "")]);
    let client = client.layer(RetryLayer::new(RetryPolicy::default()));
    let started = tokio::time::Instant::now();
    let response = client
        .request(Method::GET, "http://example.invalid/")
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(calls.load(Ordering::SeqCst), 2);
    let waited = started.elapsed();
    assert!(
        (Duration::from_millis(1500)..Duration::from_millis(1600)).contains(&waited),
        "{waited:?}"
    );
}

#[tokio::test(start_paused = true)]
async fn retry_after_beyond_max_retry_after_is_returned_at_once() {
    let (client, calls) = scripted(vec![(429, "retry-after", "3600"), (200, "", "")]);
    let client = client.layer(retry::RetryLayer::default());
    let started = tokio::time::Instant::now();
    let response = client
        .request(Method::GET, "http://example.invalid/")
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(response.text().await.unwrap(), "body");
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    assert!(started.elapsed() < Duration::from_secs(1));
}

#[tokio::test(start_paused = true)]
async fn backoff_is_jittered_within_bounds() {
    let (client, calls) = scripted(vec![(503, "", ""), (503, "", ""), (200, "", "")]);
    let policy = RetryPolicy::new(3).base_delay(Duration::from_secs(1));
    let client = client.layer(retry::RetryLayer::new(policy));
    let started = tokio::time::Instant::now();
    let response = client
        .request(Method::GET, "http://example.invalid/")
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(calls.load(Ordering::SeqCst), 3);
    // 1 s then 2 s, each jittered to between half and all of it.
    let waited = started.elapsed();
    assert!(
        (Duration::from_millis(1500)..=Duration::from_secs(3)).contains(&waited),
        "{waited:?}"
    );
}
