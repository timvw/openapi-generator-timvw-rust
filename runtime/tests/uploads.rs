//! Uploads and downloads end to end: reqwest against a local server.
#![cfg(all(feature = "reqwest", feature = "retry", feature = "fs"))]

use std::time::Duration;

use bytes::Bytes;
use timvw_rust_runtime::multipart::{Form, Part};
use timvw_rust_runtime::retry::RetryPolicy;
use timvw_rust_runtime::{Client, Method, ReqwestService, StatusCode, Upload};
use tower::retry::RetryLayer;
use wiremock::matchers::method;
use wiremock::{Mock, MockServer, Request, ResponseTemplate};

fn retrying() -> Client {
    Client::from_service(ReqwestService::default()).layer(RetryLayer::new(
        RetryPolicy::new(2).base_delay(Duration::from_millis(1)),
    ))
}

/// A server that answers the first request with a 503 and every other with a 200.
async fn unavailable_once() -> MockServer {
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
    server
}

fn header<'a>(request: &'a Request, name: &str) -> Option<&'a str> {
    request
        .headers
        .get(name)
        .map(|value| value.to_str().unwrap())
}

fn body_text(request: &Request) -> String {
    String::from_utf8_lossy(&request.body).into_owned()
}

fn temp_file(contents: &[u8]) -> (tempfile::TempDir, std::path::PathBuf) {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("photo.png");
    std::fs::write(&path, contents).unwrap();
    (dir, path)
}

#[tokio::test]
async fn a_file_part_is_streamed_with_a_content_length_and_reopened_by_a_retry() {
    let server = unavailable_once().await;
    let contents = vec![7u8; 200_000];
    let (_dir, path) = temp_file(&contents);

    let form = Form::new()
        .text("purpose", "vision")
        .part("file", Part::upload(Upload::file(&path).await.unwrap()));
    let response = retrying()
        .request(Method::POST, &server.uri())
        .multipart(form)
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    let requests = server.received_requests().await.unwrap();
    assert_eq!(requests.len(), 2, "the 503 is retried");
    for request in &requests {
        assert_eq!(header(request, "transfer-encoding"), None);
        let length: usize = header(request, "content-length").unwrap().parse().unwrap();
        assert_eq!(length, request.body.len());
        let text = body_text(request);
        assert!(
            text.contains("name=\"file\"; filename=\"photo.png\"\r\nContent-Type: image/png\r\n"),
            "{text}"
        );
        assert!(request
            .body
            .windows(contents.len())
            .any(|window| window == contents.as_slice()));
    }
    assert_eq!(requests[0].body, requests[1].body);
}

#[tokio::test]
async fn a_file_body_is_streamed_with_a_content_length_and_retried() {
    let server = unavailable_once().await;
    let (_dir, path) = temp_file(b"0123456789");

    let response = retrying()
        .request(Method::POST, &server.uri())
        .body(Upload::file(&path).await.unwrap().slice(2..6))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let requests = server.received_requests().await.unwrap();
    assert_eq!(requests.len(), 2);
    for request in &requests {
        assert_eq!(header(request, "content-length"), Some("4"));
        assert_eq!(body_text(request), "2345");
    }
}

#[tokio::test]
async fn a_one_shot_stream_is_not_retried() {
    let server = unavailable_once().await;
    let chunks = futures_util::stream::iter([
        Ok::<_, std::io::Error>(Bytes::from("ab")),
        Ok(Bytes::from("cd")),
    ]);

    let form = Form::new().part(
        "file",
        Part::upload(Upload::stream(chunks).file_name("a.txt")),
    );
    let response = retrying()
        .request(Method::POST, &server.uri())
        .multipart(form)
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
    let requests = server.received_requests().await.unwrap();
    assert_eq!(requests.len(), 1);
    assert_eq!(
        header(&requests[0], "content-length"),
        None,
        "a stream of unknown length is chunked"
    );
    assert!(body_text(&requests[0]).contains("filename=\"a.txt\"\r\n\r\nabcd\r\n"));
}

#[tokio::test]
async fn buffer_for_retry_lets_a_short_stream_be_retried() {
    let server = unavailable_once().await;
    let chunks = futures_util::stream::iter([
        Ok::<_, std::io::Error>(Bytes::from("ab")),
        Ok(Bytes::from("cd")),
    ]);
    let upload = Upload::stream(chunks)
        .buffer_for_retry(1024)
        .resolve()
        .await
        .unwrap();

    let response = retrying()
        .request(Method::POST, &server.uri())
        .body(upload)
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let requests = server.received_requests().await.unwrap();
    assert_eq!(requests.len(), 2);
    assert!(requests.iter().all(|request| body_text(request) == "abcd"));
}

#[tokio::test]
async fn save_to_writes_the_whole_body() {
    let server = MockServer::start().await;
    let contents = "x".repeat(100_000);
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(200).set_body_string(contents.clone()))
        .mount(&server)
        .await;
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("download.txt");

    let written = retrying()
        .request(Method::GET, &server.uri())
        .send()
        .await
        .unwrap()
        .save_to(&path)
        .await
        .unwrap();
    assert_eq!(written, contents.len() as u64);
    assert_eq!(std::fs::read_to_string(&path).unwrap(), contents);

    let mut reader = retrying()
        .request(Method::GET, &server.uri())
        .send()
        .await
        .unwrap()
        .into_async_read();
    let mut copy = Vec::new();
    tokio::io::copy(&mut reader, &mut copy).await.unwrap();
    assert_eq!(copy, contents.as_bytes());
}
