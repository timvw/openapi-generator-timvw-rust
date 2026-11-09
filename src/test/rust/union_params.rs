//! How a parameter whose type is a oneOf union goes out, on the crate generated from
//! src/test/resources/union_params.yaml. `UnionParamsTest` copies this file into that crate's tests/ and runs cargo.
//! A one-shot HTTP server on a local port answers each call with 204 and hands back the request it read.

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use union_params_fixture::apis::configuration::Configuration;
use union_params_fixture::apis::default_api;
use union_params_fixture::models::{Mixed, Point, Status, StatusFilter};

/// Serves one request with 204; returns the request.
async fn serve() -> (Configuration, tokio::task::JoinHandle<String>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let handle = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.unwrap();
        let mut request = Vec::new();
        let mut buf = [0u8; 4096];
        loop {
            let n = socket.read(&mut buf).await.unwrap();
            request.extend_from_slice(&buf[..n]);
            let text = String::from_utf8_lossy(&request).to_string();
            if let Some(end) = text.find("\r\n\r\n") {
                let length = header(&text[..end], "content-length")
                    .map(|v| v.parse::<usize>().unwrap())
                    .unwrap_or(0);
                if request.len() >= end + 4 + length {
                    break;
                }
            }
            if n == 0 {
                break;
            }
        }
        socket
            .write_all(b"HTTP/1.1 204 No Content\r\nconnection: close\r\n\r\n")
            .await
            .unwrap();
        socket.shutdown().await.unwrap();
        String::from_utf8_lossy(&request).to_string()
    });
    let mut configuration = Configuration::new();
    configuration.base_path = format!("http://127.0.0.1:{port}");
    (configuration, handle)
}

/// The value of a header in the request head, by its name in any case.
fn header<'a>(request: &'a str, name: &str) -> Option<&'a str> {
    request.lines().find_map(|line| {
        let (key, value) = line.split_once(':')?;
        key.eq_ignore_ascii_case(name).then(|| value.trim())
    })
}

/// A scalar variant is its plain text (a string unquoted, a number or a bool as text), in the path, the query and a
/// header, and per item of an array; any other variant is its JSON.
#[tokio::test]
async fn a_scalar_variant_is_plain_text() {
    let (configuration, request) = serve().await;
    default_api::list_vaults()
        .configuration(&configuration)
        .status(StatusFilter::Status(Status::Active))
        .mixed(Mixed::Integer(5))
        .filter(StatusFilter::Status(Status::Archived))
        .filters(vec![
            StatusFilter::Status(Status::Active),
            StatusFilter::ArrayOfStatuss(vec![Status::Active, Status::Archived]),
        ])
        .x_status(StatusFilter::Status(Status::Active))
        .call()
        .await
        .unwrap();
    let request = request.await.unwrap();
    assert_eq!(
        request.lines().next().unwrap(),
        "GET /vaults/active?filter=archived&mixed=5\
         &filters=active&filters=%5B%22active%22%2C%22archived%22%5D HTTP/1.1"
    );
    assert_eq!(header(&request, "x-status"), Some("active"));
}

/// A boolean is `true`, an object its JSON.
#[tokio::test]
async fn other_variants() {
    let (configuration, request) = serve().await;
    default_api::list_vaults()
        .configuration(&configuration)
        .status(StatusFilter::ArrayOfStatuss(vec![Status::Archived]))
        .mixed(Mixed::Boolean(true))
        .filter(StatusFilter::ArrayOfStatuss(vec![Status::Active]))
        .call()
        .await
        .unwrap();
    assert_eq!(
        request.await.unwrap().lines().next().unwrap(),
        "GET /vaults/%5B%22archived%22%5D?filter=%5B%22active%22%5D&mixed=true HTTP/1.1"
    );

    let (configuration, request) = serve().await;
    default_api::list_vaults()
        .configuration(&configuration)
        .status(StatusFilter::Status(Status::Active))
        .mixed(Mixed::Point(Point { x: 1 }))
        .call()
        .await
        .unwrap();
    assert_eq!(
        request.await.unwrap().lines().next().unwrap(),
        "GET /vaults/active?mixed=%7B%22x%22%3A1%7D HTTP/1.1"
    );
}
