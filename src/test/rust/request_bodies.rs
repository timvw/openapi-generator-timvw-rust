//! Borrowed request bodies at run time, on the crate generated from src/test/resources/request_bodies.yaml.
//! `RequestBodiesTest` copies this file into that crate's tests/ and runs cargo. A one-shot HTTP server on a local
//! port answers each call with 204 and hands back the body it read.

use std::collections::HashMap;

use request_bodies::apis::configuration::Configuration;
use request_bodies::apis::default_api;
use request_bodies::models::Item;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

/// Serves one request with 204; returns the request body.
async fn serve() -> (Configuration, tokio::task::JoinHandle<String>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let handle = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.unwrap();
        let mut request = Vec::new();
        let mut buf = [0u8; 4096];
        let body = loop {
            let n = socket.read(&mut buf).await.unwrap();
            request.extend_from_slice(&buf[..n]);
            let text = String::from_utf8_lossy(&request).to_string();
            if let Some(end) = text.find("\r\n\r\n") {
                let length = text[..end]
                    .lines()
                    .find_map(|l| {
                        l.to_ascii_lowercase()
                            .strip_prefix("content-length:")
                            .map(|v| v.trim().parse::<usize>().unwrap())
                    })
                    .unwrap_or(0);
                if request.len() >= end + 4 + length || n == 0 {
                    break text[end + 4..].to_string();
                }
            }
            if n == 0 {
                break String::new();
            }
        };
        socket
            .write_all(b"HTTP/1.1 204 No Content\r\nconnection: close\r\n\r\n")
            .await
            .unwrap();
        socket.shutdown().await.unwrap();
        body
    });
    let mut configuration = Configuration::new();
    configuration.base_path = format!("http://127.0.0.1:{port}");
    (configuration, handle)
}

fn item() -> Item {
    Item::builder().name("a".to_owned()).build()
}

/// The builder takes the body by reference; the caller keeps it.
#[tokio::test]
async fn a_builder_call_borrows_the_body() {
    let item = item();
    let (configuration, server) = serve().await;
    default_api::create_item()
        .configuration(&configuration)
        .item(&item)
        .call()
        .await
        .unwrap();
    assert_eq!(server.await.unwrap(), "{\"name\":\"a\"}");
    assert_eq!(item.name, "a");

    let (configuration, server) = serve().await;
    default_api::update_item()
        .configuration(&configuration)
        .maybe_item(Some(&item))
        .call()
        .await
        .unwrap();
    assert_eq!(server.await.unwrap(), "{\"name\":\"a\"}");

    let (configuration, server) = serve().await;
    default_api::update_item()
        .configuration(&configuration)
        .item(&item)
        .call()
        .await
        .unwrap();
    assert_eq!(server.await.unwrap(), "{\"name\":\"a\"}");

    // An absent optional body goes out as JSON null, as before.
    let (configuration, server) = serve().await;
    default_api::update_item()
        .configuration(&configuration)
        .call()
        .await
        .unwrap();
    assert_eq!(server.await.unwrap(), "null");
}

/// An array body is a slice, so a `Vec` or an array goes in by reference.
#[tokio::test]
async fn an_array_body_is_a_slice() {
    let items = vec![item(), item()];
    let (configuration, server) = serve().await;
    default_api::create_items()
        .configuration(&configuration)
        .item(&items)
        .call()
        .await
        .unwrap();
    assert_eq!(server.await.unwrap(), "[{\"name\":\"a\"},{\"name\":\"a\"}]");

    let (configuration, server) = serve().await;
    default_api::create_items()
        .configuration(&configuration)
        .item(&[item()])
        .call()
        .await
        .unwrap();
    assert_eq!(server.await.unwrap(), "[{\"name\":\"a\"}]");
}

/// Maps and free-form values are borrowed; numbers are passed by value.
#[tokio::test]
async fn maps_values_and_numbers() {
    let labels = HashMap::from([("k".to_owned(), "v".to_owned())]);
    let (configuration, server) = serve().await;
    default_api::set_labels()
        .configuration(&configuration)
        .request_body(&labels)
        .call()
        .await
        .unwrap();
    assert_eq!(server.await.unwrap(), "{\"k\":\"v\"}");

    let value = serde_json::json!({"x": [1, 2]});
    let (configuration, server) = serve().await;
    default_api::send_anything()
        .configuration(&configuration)
        .body(&value)
        .call()
        .await
        .unwrap();
    assert_eq!(server.await.unwrap(), "{\"x\":[1,2]}");

    let (configuration, server) = serve().await;
    default_api::set_count()
        .configuration(&configuration)
        .body(7)
        .call()
        .await
        .unwrap();
    assert_eq!(server.await.unwrap(), "7");
}

/// An array of numbers is a slice, a union and a `oneOf` with a null branch are borrowed, a date-time is passed by
/// value.
#[tokio::test]
async fn numbers_unions_and_dates() {
    let (configuration, server) = serve().await;
    default_api::set_counts()
        .configuration(&configuration)
        .request_body(&[1, 2])
        .call()
        .await
        .unwrap();
    assert_eq!(server.await.unwrap(), "[1,2]");

    let shape = request_bodies::models::Shape::Item(item());
    let (configuration, server) = serve().await;
    default_api::send_shape()
        .configuration(&configuration)
        .shape(&shape)
        .call()
        .await
        .unwrap();
    assert_eq!(server.await.unwrap(), "{\"name\":\"a\"}");

    let item = item();
    let (configuration, server) = serve().await;
    default_api::send_maybe_item()
        .configuration(&configuration)
        .item(&item)
        .call()
        .await
        .unwrap();
    assert_eq!(server.await.unwrap(), "{\"name\":\"a\"}");

    let when = chrono::DateTime::parse_from_rfc3339("2026-01-02T03:04:05+01:00").unwrap();
    let (configuration, server) = serve().await;
    default_api::set_when()
        .configuration(&configuration)
        .body(when)
        .call()
        .await
        .unwrap();
    assert_eq!(server.await.unwrap(), "\"2026-01-02T03:04:05+01:00\"");
}
