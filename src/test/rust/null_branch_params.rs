//! How a parameter or form field whose schema is an inline `oneOf`/`anyOf` of one schema and `type: null` goes out,
//! on the crate generated from src/test/resources/null_branch_params.yaml. `NullBranchParamsTest` copies this file
//! into that crate's tests/ and runs cargo. A one-shot HTTP server on a local port answers each call with 204 and
//! hands back the request it read.

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use null_branch_params_fixture::apis::configuration::Configuration;
use null_branch_params_fixture::apis::default_api;
use null_branch_params_fixture::models::{Mixed, Status};

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

fn id() -> uuid::Uuid {
    uuid::Uuid::parse_str("67e55044-10b1-426f-9247-bb680e5fe0c8").unwrap()
}

fn at() -> chrono::DateTime<chrono::FixedOffset> {
    chrono::DateTime::parse_from_rfc3339("2026-01-02T03:04:05+01:00").unwrap()
}

/// Every parameter goes out as the plain reference does: the enum's value, the uuid hyphenated, the date-time in
/// RFC 3339, the number and the union as text, in the path, the query, a header and a cookie.
#[tokio::test]
async fn each_parameter_goes_out_as_the_plain_reference_does() {
    let (configuration, request) = serve().await;
    default_api::get_thing()
        .configuration(&configuration)
        .id(id())
        .required_status(Status::Archived)
        .limit(10)
        .text("hi")
        .status(Status::Active)
        .any_status(Status::Archived)
        .since(at())
        .count(7)
        .mixed(Mixed::Boolean(true))
        .owner(id())
        .x_status(Status::Active)
        .session(Status::Archived)
        .x_trace(id())
        .call()
        .await
        .unwrap();
    let thing = request.await.unwrap();

    let (configuration, request) = serve().await;
    default_api::get_plain()
        .configuration(&configuration)
        .id(id())
        .status(Status::Active)
        .since(at())
        .count(7)
        .mixed(Mixed::Boolean(true))
        .x_status(Status::Active)
        .session(Status::Archived)
        .call()
        .await
        .unwrap();
    let plain = request.await.unwrap();

    assert_eq!(
        thing.lines().next().unwrap(),
        "GET /things/67e55044-10b1-426f-9247-bb680e5fe0c8?status=active&required_status=archived\
         &any_status=archived&since=2026-01-02T03%3A04%3A05%2B01%3A00&count=7&mixed=true&limit=10&text=hi\
         &owner=67e55044-10b1-426f-9247-bb680e5fe0c8 HTTP/1.1"
    );
    assert_eq!(
        plain.lines().next().unwrap(),
        "GET /plain/67e55044-10b1-426f-9247-bb680e5fe0c8?status=active\
         &since=2026-01-02T03%3A04%3A05%2B01%3A00&count=7&mixed=true HTTP/1.1"
    );
    assert_eq!(header(&thing, "x-status"), Some("active"));
    assert_eq!(header(&thing, "x-status"), header(&plain, "x-status"));
    assert_eq!(header(&thing, "cookie"), Some("session=archived"));
    assert_eq!(header(&thing, "cookie"), header(&plain, "cookie"));
    assert_eq!(header(&thing, "x-trace"), Some("67e55044-10b1-426f-9247-bb680e5fe0c8"));
}

/// `None` sends nothing, a required parameter's too: a parameter has no `null` on the wire.
#[tokio::test]
async fn none_sends_nothing() {
    let (configuration, request) = serve().await;
    default_api::get_thing()
        .configuration(&configuration)
        .id(id())
        .maybe_required_status(None)
        .maybe_limit(None)
        .maybe_text(None)
        .call()
        .await
        .unwrap();
    let request = request.await.unwrap();
    assert_eq!(
        request.lines().next().unwrap(),
        "GET /things/67e55044-10b1-426f-9247-bb680e5fe0c8 HTTP/1.1"
    );
    assert_eq!(header(&request, "x-status"), None);
    assert_eq!(header(&request, "cookie"), None);
}

/// An urlencoded form field goes out as a field of the referenced schema; a required one left `None` is sent empty,
/// as a required `type: [T, null]` field is.
#[tokio::test]
async fn urlencoded_form_fields() {
    let (configuration, request) = serve().await;
    default_api::post_form()
        .configuration(&configuration)
        .since(at())
        .status(Status::Archived)
        .id(id())
        .call()
        .await
        .unwrap();
    let request = request.await.unwrap();
    assert!(
        request.ends_with(
            "since=2026-01-02T03%3A04%3A05%2B01%3A00&status=archived&id=67e55044-10b1-426f-9247-bb680e5fe0c8"
        ),
        "{request}"
    );

    let (configuration, request) = serve().await;
    default_api::post_form()
        .configuration(&configuration)
        .maybe_since(None)
        .call()
        .await
        .unwrap();
    assert!(request.await.unwrap().ends_with("\r\n\r\nsince="));

    let (configuration, request) = serve().await;
    default_api::post_thing_form()
        .configuration(&configuration)
        .owner(id())
        .call()
        .await
        .unwrap();
    assert!(request.await.unwrap().ends_with("\r\n\r\nowner=67e55044-10b1-426f-9247-bb680e5fe0c8"));
}

/// A multipart field is a text part of the referenced schema's text.
#[tokio::test]
async fn multipart_fields() {
    let (configuration, request) = serve().await;
    default_api::post_multipart()
        .configuration(&configuration)
        .since(at())
        .status(Status::Active)
        .id(id())
        .call()
        .await
        .unwrap();
    let request = request.await.unwrap();
    for (name, value) in [
        ("since", "2026-01-02T03:04:05+01:00"),
        ("status", "active"),
        ("id", "67e55044-10b1-426f-9247-bb680e5fe0c8"),
    ] {
        assert!(
            request.contains(&format!("name=\"{name}\"\r\n\r\n{value}\r\n")),
            "{name}: {request}"
        );
    }
}

/// A model keeps the field nullable: the form body's component still decodes `null`.
#[test]
fn the_model_keeps_the_field_nullable() {
    let thing: null_branch_params_fixture::models::Thing = serde_json::from_str(r#"{"owner":null}"#).unwrap();
    assert_eq!(thing.owner, Some(None));
}
