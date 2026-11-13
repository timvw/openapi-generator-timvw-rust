//! Per-call `RequestOptions` at run time, on the crate generated from src/test/resources/request_options.yaml (with
//! `typedEventStreams`, `contentTypeMethods` and `responseFunctions`). `RequestOptionsTest` copies this file into that
//! crate's tests/ and runs cargo. A one-shot HTTP server on a local port answers each call and hands back the request
//! it read.

use futures_util::StreamExt;
use request_options_fixture::apis::configuration::Configuration;
use request_options_fixture::apis::{default_api, RequestOptions};
use request_options_fixture::models::{Chunk, Item};
use timvw_rust_runtime::header::{HeaderMap, HeaderValue};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

/// Serves one request with `content_type` and `body`; returns the request it read.
async fn serve(
    content_type: &str,
    body: &'static str,
) -> (Configuration, tokio::task::JoinHandle<String>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let response = format!(
        "HTTP/1.1 200 OK\r\ncontent-type: {content_type}\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}",
        body.len()
    );
    let handle = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.unwrap();
        let mut request = Vec::new();
        let mut buf = [0u8; 4096];
        loop {
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
                if request.len() >= end + 4 + length {
                    break;
                }
            }
            if n == 0 {
                break;
            }
        }
        socket.write_all(response.as_bytes()).await.unwrap();
        socket.shutdown().await.unwrap();
        String::from_utf8_lossy(&request).to_string()
    });
    let mut configuration = Configuration::new();
    configuration.base_path = format!("http://127.0.0.1:{port}");
    configuration.bearer_access_token = Some("from-configuration".to_owned());
    // The server answers once: no retries.
    configuration.client =
        timvw_rust_runtime::Client::from_service(timvw_rust_runtime::ReqwestService::default());
    (configuration, handle)
}

/// The request line and the header lines, lowercased names, without the body.
fn head(request: &str) -> Vec<String> {
    request
        .split("\r\n\r\n")
        .next()
        .unwrap()
        .lines()
        .map(|l| match l.split_once(": ") {
            Some((name, value)) => format!("{}: {value}", name.to_ascii_lowercase()),
            None => l.to_owned(),
        })
        .collect()
}

fn header_values(request: &str, name: &str) -> Vec<String> {
    let prefix = format!("{name}: ");
    head(request)
        .iter()
        .filter_map(|l| l.strip_prefix(&prefix).map(str::to_owned))
        .collect()
}

fn item() -> Item {
    Item {
        name: "lamp".to_owned(),
    }
}

const ITEM: &str = r#"{"name":"lamp"}"#;

#[tokio::test]
async fn extra_headers_and_query_parameters_arrive() {
    let (configuration, server) = serve("application/json", ITEM).await;
    let options = RequestOptions::new()
        .header("x-extra", "yes")
        .unwrap()
        .query(&[("extra", "1"), ("other", "a b")])
        .unwrap();
    let created = default_api::create_item()
        .configuration(&configuration)
        .item(&item())
        .limit(5)
        .options(&options)
        .call()
        .await
        .unwrap();
    assert_eq!(created.name, "lamp");
    let request = server.await.unwrap();
    assert_eq!(
        head(&request)[0],
        "POST /items?limit=5&extra=1&other=a+b HTTP/1.1",
        "{request}"
    );
    assert_eq!(header_values(&request, "x-extra"), ["yes"], "{request}");
    assert_eq!(
        header_values(&request, "content-type"),
        ["application/json"],
        "{request}"
    );
    assert!(request.ends_with(ITEM), "{request}");
}

#[tokio::test]
async fn an_options_header_replaces_a_generated_one() {
    let (configuration, server) = serve("application/json", ITEM).await;
    let mut tags = HeaderMap::new();
    tags.append("x-tag", HeaderValue::from_static("a"));
    tags.append("x-tag", HeaderValue::from_static("b"));
    let options = RequestOptions::new()
        .header("x-trace", "from-options")
        .unwrap()
        .header("authorization", "Bearer from-options")
        .unwrap()
        .headers(tags);
    default_api::create_item()
        .configuration(&configuration)
        .item(&item())
        .x_trace("generated")
        .options(&options)
        .call()
        .await
        .unwrap();
    let request = server.await.unwrap();
    assert_eq!(
        header_values(&request, "x-trace"),
        ["from-options"],
        "{request}"
    );
    assert_eq!(
        header_values(&request, "authorization"),
        ["Bearer from-options"],
        "{request}"
    );
    assert_eq!(header_values(&request, "x-tag"), ["a", "b"], "{request}");
}

#[tokio::test]
async fn without_options_the_request_is_the_generated_one() {
    let (configuration, server) = serve("application/json", ITEM).await;
    default_api::create_item()
        .configuration(&configuration)
        .item(&item())
        .x_trace("generated")
        .call()
        .await
        .unwrap();
    let request = server.await.unwrap();
    assert_eq!(head(&request)[0], "POST /items HTTP/1.1", "{request}");
    assert_eq!(
        header_values(&request, "x-trace"),
        ["generated"],
        "{request}"
    );
    assert_eq!(
        header_values(&request, "authorization"),
        ["Bearer from-configuration"],
        "{request}"
    );
}

#[tokio::test]
async fn a_multipart_method_takes_options() {
    let (configuration, server) = serve("application/json", ITEM).await;
    let options = RequestOptions::new()
        .header("x-extra", "yes")
        .unwrap()
        .query(&[("extra", "1")])
        .unwrap();
    default_api::create_item_multipart()
        .configuration(&configuration)
        .name("lamp")
        .options(&options)
        .call()
        .await
        .unwrap();
    let request = server.await.unwrap();
    assert_eq!(
        head(&request)[0],
        "POST /items?extra=1 HTTP/1.1",
        "{request}"
    );
    assert_eq!(header_values(&request, "x-extra"), ["yes"], "{request}");
    let content_type = header_values(&request, "content-type");
    assert!(
        content_type[0].starts_with("multipart/form-data; boundary="),
        "{request}"
    );
    assert!(request.contains("name=\"name\""), "{request}");
}

#[tokio::test]
async fn an_event_stream_method_takes_options() {
    let (configuration, server) = serve(
        "text/event-stream",
        "data: {\"delta\":\"a\"}\n\ndata: [DONE]\n\n",
    )
    .await;
    let options = RequestOptions::new().header("x-extra", "yes").unwrap();
    let chunks: Vec<Chunk> = default_api::create_chat_stream()
        .configuration(&configuration)
        .item(&item())
        .options(&options)
        .call()
        .await
        .unwrap()
        .map(Result::unwrap)
        .collect()
        .await;
    assert_eq!(chunks.len(), 1);
    let request = server.await.unwrap();
    assert_eq!(header_values(&request, "x-extra"), ["yes"], "{request}");
    assert_eq!(
        header_values(&request, "accept"),
        ["text/event-stream"],
        "{request}"
    );
}

#[tokio::test]
async fn a_response_function_takes_options() {
    let (configuration, server) = serve("application/json", ITEM).await;
    let options = RequestOptions::new().header("x-extra", "yes").unwrap();
    let response = default_api::create_item_response()
        .configuration(&configuration)
        .item(&item())
        .options(&options)
        .call()
        .await
        .unwrap();
    assert_eq!(response.json().unwrap().name, "lamp");
    let request = server.await.unwrap();
    assert_eq!(header_values(&request, "x-extra"), ["yes"], "{request}");
}

/// A parameter named `options` keeps its name; the request options are `request_options` then.
#[tokio::test]
async fn a_parameter_named_options_keeps_its_name() {
    let (configuration, server) = serve("application/json", "").await;
    let options = RequestOptions::new().query(&[("extra", "1")]).unwrap();
    default_api::search()
        .configuration(&configuration)
        .options("spec")
        .request_options(&options)
        .call()
        .await
        .unwrap();
    let request = server.await.unwrap();
    assert_eq!(
        head(&request)[0],
        "GET /search?options=spec&extra=1 HTTP/1.1",
        "{request}"
    );
}
