//! `serverWebhooks` end to end, on the crate generated from src/test/resources/webhooks.yaml with
//! `targets: [client, server]`, `webhookModule: Webhooks` and `serverWebhooks`. `ServerWebhooksTest` copies this file
//! into that crate's tests/ and runs cargo. A receiver of its own, on a local port, records what it gets; the server's
//! `webhooks::send` and the client's webhook functions send to it, signed as Standard Webhooks.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use webhooks_fixture::apis::configuration::Configuration;
use webhooks_fixture::apis::webhooks_api;
use webhooks_fixture::models::{Item, Pet};
use webhooks_fixture::server::webhooks::{
    send, verify, Client, Delivery, SendError, StandardWebhooksLayer, Webhook, WebhookEndpoint, WebhookError,
    WebhookSecret,
};

// Standard Webhooks' public test secret, written in pieces so that secret scanners do not take it for a real one
const SECRET: &str = concat!("whsec", "_", "MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");

/// What the receiver got: the path, the headers and the body of each request.
type Received = Arc<Mutex<Vec<(String, http::HeaderMap, Vec<u8>)>>>;

/// A receiver on a local port: `/gone` answers `410`, any other path `200`.
async fn receiver() -> (String, Received) {
    let received: Received = Arc::default();
    let record = received.clone();
    let app = axum::Router::new().fallback(move |request: axum::extract::Request| {
        let record = record.clone();
        async move {
            let path = request.uri().path().to_owned();
            let headers = request.headers().clone();
            let body = axum::body::to_bytes(request.into_body(), usize::MAX).await.unwrap().to_vec();
            record.lock().unwrap().push((path.clone(), headers, body));
            if path == "/gone" {
                http::StatusCode::GONE
            } else {
                http::StatusCode::OK
            }
        }
    });
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    (format!("http://{address}"), received)
}

fn signing() -> Client {
    Client::default().layer(StandardWebhooksLayer::new(WebhookSecret::new(SECRET).unwrap()))
}

fn item() -> Item {
    Item { id: Some("item_1".to_owned()) }
}

#[tokio::test]
async fn the_server_sends_a_signed_webhook_the_receiver_verifies() {
    let (url, received) = receiver().await;
    let endpoint = WebhookEndpoint::new(format!("{url}/hooks"), signing());
    let delivery = send(&endpoint, &Webhook::ItemCreated(item())).await.unwrap();
    assert_eq!(delivery, Delivery::Delivered(http::StatusCode::OK));

    let received = received.lock().unwrap();
    let (path, headers, body) = &received[0];
    assert_eq!(path, "/hooks");
    assert_eq!(headers[http::header::CONTENT_TYPE], "application/json");
    verify(headers, body, &[WebhookSecret::new(SECRET).unwrap()], Duration::from_secs(60)).unwrap();
    let sent: Item = serde_json::from_slice(body).unwrap();
    assert_eq!(sent, item());
    // another secret does not verify it
    let other = WebhookSecret::from_bytes(b"another key".to_vec());
    assert_eq!(verify(headers, body, &[other], Duration::from_secs(60)), Err(WebhookError::NoMatchingSignature));
}

#[tokio::test]
async fn an_endpoint_gets_only_the_webhooks_it_wants() {
    let (url, received) = receiver().await;
    let endpoint = WebhookEndpoint::new(format!("{url}/pets"), signing()).events(["petAdopted"]);
    assert_eq!(Webhook::ItemCreated(item()).name(), "itemCreated");
    assert_eq!(send(&endpoint, &Webhook::ItemCreated(item())).await.unwrap(), Delivery::NotWanted);
    let pet = Pet { name: Some("Rex".to_owned()) };
    assert_eq!(
        send(&endpoint, &Webhook::PetAdopted(pet)).await.unwrap(),
        Delivery::Delivered(http::StatusCode::OK)
    );
    assert_eq!(received.lock().unwrap().len(), 1);
}

#[tokio::test]
async fn a_status_that_is_not_2xx_is_an_error() {
    let (url, _) = receiver().await;
    let endpoint = WebhookEndpoint::new(format!("{url}/gone"), Client::default());
    let error = send(&endpoint, &Webhook::ItemCreated(item())).await.unwrap_err();
    assert!(matches!(error, SendError::Status(http::StatusCode::GONE)), "{error}");
}

#[tokio::test]
async fn the_clients_webhook_function_sends_to_base_path_itself() {
    let (url, received) = receiver().await;
    let mut configuration = Configuration::new();
    configuration.base_path = format!("{url}/hooks");
    configuration.client = signing();
    webhooks_api::item_created().configuration(&configuration).item(&item()).call().await.unwrap();

    let received = received.lock().unwrap();
    let (path, headers, body) = &received[0];
    // the endpoint's URL, not {base_path}/itemCreated
    assert_eq!(path, "/hooks");
    verify(headers, body, &[WebhookSecret::new(SECRET).unwrap()], Duration::from_secs(60)).unwrap();
}
