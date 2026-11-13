//! File parameters as `timvw_rust_runtime::Upload` at run time, on the crate generated from
//! src/test/resources/uploads.yaml. `UploadsTest` copies this file into that crate's tests/ (with
//! and without `multipartHelper`) and runs cargo. A local HTTP server answers each request with the next status of a
//! list and hands back the requests it read.

use std::path::PathBuf;

use timvw_rust_runtime::Upload;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use uploads_fixture::apis::configuration::Configuration;
use uploads_fixture::apis::default_api;

/// Answers one request per status, in order; returns the requests it read.
async fn serve(statuses: &'static [u16]) -> (Configuration, tokio::task::JoinHandle<Vec<String>>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let handle = tokio::spawn(async move {
        let mut requests = Vec::new();
        for status in statuses {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut request = Vec::new();
            let mut buf = [0u8; 65536];
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
                        .expect("a Content-Length, not a chunked body");
                    if request.len() >= end + 4 + length {
                        break;
                    }
                }
                if n == 0 {
                    break;
                }
            }
            let response = format!(
                "HTTP/1.1 {status} X\r\nretry-after: 0\r\ncontent-length: 0\r\nconnection: close\r\n\r\n"
            );
            socket.write_all(response.as_bytes()).await.unwrap();
            socket.shutdown().await.unwrap();
            requests.push(String::from_utf8_lossy(&request).to_string());
        }
        requests
    });
    let mut configuration = Configuration::new();
    configuration.base_path = format!("http://127.0.0.1:{port}");
    (configuration, handle)
}

fn content_length(request: &str) -> usize {
    let head = request
        .split("\r\n\r\n")
        .next()
        .unwrap()
        .to_ascii_lowercase();
    head.lines()
        .find_map(|l| l.strip_prefix("content-length: "))
        .unwrap()
        .parse()
        .unwrap()
}

fn body(request: &str) -> &str {
    request.split_once("\r\n\r\n").unwrap().1
}

fn temp_file(dir: &tempfile::TempDir, name: &str, contents: &str) -> PathBuf {
    let path = dir.path().join(name);
    std::fs::write(&path, contents).unwrap();
    path
}

#[tokio::test]
async fn files_paths_and_bytes_go_out_as_parts_with_a_content_length() {
    let dir = tempfile::tempdir().unwrap();
    let photo = temp_file(&dir, "photo.png", "PNGDATA");
    let first = temp_file(&dir, "a.txt", "first");
    let second = temp_file(&dir, "b.txt", "second");
    let (configuration, server) = serve(&[200]).await;

    default_api::file_create()
        .configuration(&configuration)
        // a PathBuf still converts
        .file(photo)
        .mask(
            Upload::bytes(b"MASK".to_vec())
                .file_name("mask.png")
                .mime("image/png"),
        )
        .images(vec![first, second])
        .purpose("edit")
        .call()
        .await
        .unwrap();

    let request = server.await.unwrap().remove(0);
    let body = body(&request);
    assert_eq!(content_length(&request), body.len(), "{request}");
    assert!(
        body.contains(
            "name=\"file\"; filename=\"photo.png\"\r\nContent-Type: image/png\r\n\r\nPNGDATA\r\n"
        ),
        "{body}"
    );
    assert!(
        body.contains(
            "name=\"mask\"; filename=\"mask.png\"\r\nContent-Type: image/png\r\n\r\nMASK\r\n"
        ),
        "{body}"
    );
    assert!(
        body.contains(
            "name=\"images\"; filename=\"a.txt\"\r\nContent-Type: text/plain\r\n\r\nfirst\r\n"
        ),
        "{body}"
    );
    assert!(
        body.contains(
            "name=\"images\"; filename=\"b.txt\"\r\nContent-Type: text/plain\r\n\r\nsecond\r\n"
        ),
        "{body}"
    );
    assert!(body.contains("name=\"purpose\"\r\n\r\nedit\r\n"), "{body}");
}

#[tokio::test]
async fn a_required_array_takes_any_uploads() {
    let dir = tempfile::tempdir().unwrap();
    let path = temp_file(&dir, "data.jsonl", "{}");
    let (configuration, server) = serve(&[200]).await;

    default_api::batch_create()
        .configuration(&configuration)
        .files([Upload::from(path), Upload::bytes(&b"raw"[..])])
        .call()
        .await
        .unwrap();

    let request = server.await.unwrap().remove(0);
    let body = body(&request);
    assert!(body.contains("filename=\"data.jsonl\""), "{body}");
    assert!(
        body.contains("name=\"files\"; filename=\"file\"\r\n\r\nraw\r\n"),
        "{body}"
    );
}

#[tokio::test]
async fn a_retry_opens_the_file_again() {
    let dir = tempfile::tempdir().unwrap();
    let path = temp_file(&dir, "blob.bin", "0123456789");
    let (configuration, server) = serve(&[503, 204]).await;

    default_api::blob_put()
        .configuration(&configuration)
        .id("x")
        .body(path)
        .call()
        .await
        .unwrap();

    let requests = server.await.unwrap();
    assert_eq!(requests.len(), 2);
    for request in &requests {
        assert!(request.starts_with("PUT /blobs/x "), "{request}");
        assert_eq!(content_length(request), 10, "{request}");
        assert_eq!(body(request), "0123456789");
    }
}

#[tokio::test]
async fn an_optional_body_takes_bytes() {
    let (configuration, server) = serve(&[204]).await;
    default_api::blob_post()
        .configuration(&configuration)
        .body(b"bytes".to_vec())
        .call()
        .await
        .unwrap();
    assert_eq!(body(&server.await.unwrap()[0]), "bytes");
}

#[tokio::test]
async fn a_missing_file_fails_before_sending() {
    let error = default_api::blob_put()
        .configuration(&Configuration::new())
        .id("x")
        .body(PathBuf::from("/no/such/file.bin"))
        .call()
        .await
        .unwrap_err();
    assert!(error.to_string().contains("/no/such/file.bin"), "{error}");
}
