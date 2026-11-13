//! Per-call `RequestOptions` as the last positional argument (`bonBuilders=false`), on the crate generated from
//! src/test/resources/request_options.yaml. `RequestOptionsTest` copies this file into that crate's tests/ and runs
//! cargo. A one-shot HTTP server on a local port answers the call and hands back the request it read.

use request_options_fixture::apis::configuration::Configuration;
use request_options_fixture::apis::{default_api, RequestOptions};
use request_options_fixture::models::Item;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

const ITEM: &str = r#"{"name":"lamp"}"#;

/// Serves one JSON request; returns the base URL and the request line and headers it read.
async fn serve() -> (String, tokio::task::JoinHandle<String>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let handle = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.unwrap();
        let mut request = Vec::new();
        let mut buf = [0u8; 4096];
        loop {
            let n = socket.read(&mut buf).await.unwrap();
            request.extend_from_slice(&buf[..n]);
            if n == 0 || String::from_utf8_lossy(&request).ends_with(ITEM) {
                break;
            }
        }
        let response = format!(
            "HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{ITEM}",
            ITEM.len()
        );
        socket.write_all(response.as_bytes()).await.unwrap();
        socket.shutdown().await.unwrap();
        String::from_utf8_lossy(&request).to_string()
    });
    (format!("http://127.0.0.1:{port}"), handle)
}

#[tokio::test]
async fn options_are_the_last_argument() {
    let (base, server) = serve().await;
    let mut configuration = Configuration::new();
    configuration.base_path = "http://127.0.0.1:9/v1".to_owned();
    configuration.client =
        timvw_rust_runtime::Client::from_service(timvw_rust_runtime::ReqwestService::default());
    let options = RequestOptions::new()
        .header("x-trace", "from-options")
        .unwrap()
        .base_url(&base)
        .unwrap();
    let item = Item {
        name: "lamp".to_owned(),
    };
    let created =
        default_api::create_item(&configuration, &item, Some("generated"), Some(5), Some(&options))
            .await
            .unwrap();
    assert_eq!(created.name, "lamp");
    let request = server.await.unwrap();
    assert!(
        request.starts_with("POST /items?limit=5 HTTP/1.1\r\n"),
        "{request}"
    );
    let lower = request.to_ascii_lowercase();
    assert!(lower.contains("\r\nx-trace: from-options\r\n"), "{request}");
    assert!(!lower.contains("x-trace: generated"), "{request}");
}

#[tokio::test]
async fn none_is_the_generated_request() {
    let configuration = Configuration::new();
    // Compiles with `None`: no options.
    let call = default_api::search(&configuration, Some("spec"), None);
    drop(call);
}
