//! The client end to end: reqwest against a local server, retries, layers and a custom service.
#![cfg(all(feature = "reqwest", feature = "retry"))]

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

use timvw_rust_runtime::multipart::{Form, Part};
use timvw_rust_runtime::retry::RetryPolicy;
use timvw_rust_runtime::{Body, Client, Method, StatusCode};
use tower::retry::RetryLayer;
use wiremock::matchers::{body_string_contains, header, header_regex, method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

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

    let response = Client::default()
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
    let response = Client::default()
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

    let client = Client::default().layer(fast_retries(3));
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

    let client = Client::default().layer(fast_retries(2));
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

    let client =
        Client::default().layer(tower::timeout::TimeoutLayer::new(Duration::from_millis(50)));
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

    let response = Client::default()
        .request(Method::GET, &server.uri())
        .send()
        .await
        .unwrap();
    let chunks: Vec<_> = response.bytes_stream().try_collect().await.unwrap();
    assert_eq!(chunks.concat(), b"data: 1\n\ndata: 2\n\n");
}
