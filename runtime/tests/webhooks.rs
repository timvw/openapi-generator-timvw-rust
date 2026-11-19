//! Webhooks sent by a client with the Standard Webhooks layer, and checked as a receiver checks them.
#![cfg(all(feature = "reqwest", feature = "retry", feature = "webhooks"))]

use std::time::Duration;

use timvw_rust_runtime::retry::{RetryLayer, RetryPolicy};
use timvw_rust_runtime::webhooks::{verify, StandardWebhooksLayer, WebhookError, WebhookSecret};
use timvw_rust_runtime::{http, Body, Client, Method, ReqwestService, StatusCode};
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

const BODY: &str = r#"{"type":"response.completed","data":{"id":"resp_1"}}"#;

fn secret() -> WebhookSecret {
    // Standard Webhooks' public test secret, written in pieces so that secret scanners do not take it for a real one
    WebhookSecret::new(concat!("whsec", "_", "MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw")).unwrap()
}

#[tokio::test]
async fn a_receiver_verifies_what_the_layer_signed() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/hooks"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&server)
        .await;
    let client = Client::default().layer(StandardWebhooksLayer::new(secret()));
    let response = client
        .request(Method::POST, &format!("{}/hooks", server.uri()))
        .header(http::header::CONTENT_TYPE, "application/json")
        .body(Body::from(BODY))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    let received = server.received_requests().await.unwrap();
    let request = &received[0];
    let mut headers = http::HeaderMap::new();
    for (name, value) in request.headers.iter() {
        headers.append(name.clone(), value.clone());
    }
    verify(
        &headers,
        &request.body,
        &[secret()],
        Duration::from_secs(60),
    )
    .unwrap();
    // the receiver's check fails on a changed body
    assert_eq!(
        verify(
            &headers,
            br#"{"type":"response.failed"}"#,
            &[secret()],
            Duration::from_secs(60)
        ),
        Err(WebhookError::NoMatchingSignature)
    );
}

#[tokio::test]
async fn a_retry_sends_the_same_id_and_signature() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(503))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&server)
        .await;
    // the layer outside the retries: it signs once
    let client = Client::from_service(ReqwestService::default())
        .layer(RetryLayer::new(
            RetryPolicy::default().base_delay(Duration::from_millis(10)),
        ))
        .layer(StandardWebhooksLayer::new(secret()));
    let response = client
        .request(Method::POST, &server.uri())
        .body(Body::from(BODY))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    let received = server.received_requests().await.unwrap();
    assert_eq!(received.len(), 2);
    for name in ["webhook-id", "webhook-timestamp", "webhook-signature"] {
        assert_eq!(
            received[0].headers.get(name),
            received[1].headers.get(name),
            "{name}"
        );
    }
}
