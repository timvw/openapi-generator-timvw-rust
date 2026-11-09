//! The error enum's variant at run time, on the crate generated from src/test/resources/error_status.yaml.
//! `ErrorStatusTest` copies this file into that crate's tests/ and runs cargo. A one-shot HTTP server on a local port
//! answers each call.

use error_status_fixture::apis::configuration::Configuration;
use error_status_fixture::apis::default_api::{self, GetBareError, GetPlainError, GetThingError};
use error_status_fixture::apis::{Error, ResponseContent};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

/// Serves one request with `status` and `body`.
async fn serve(status: &str, body: &'static str) -> Configuration {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let response = format!(
        "HTTP/1.1 {status}\r\ncontent-type: application/json\r\nx-request-id: req-{status}\r\nretry-after: 30\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}",
        body.len()
    );
    tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.unwrap();
        let mut request = Vec::new();
        let mut buf = [0u8; 4096];
        while !String::from_utf8_lossy(&request).contains("\r\n\r\n") {
            let n = socket.read(&mut buf).await.unwrap();
            if n == 0 {
                break;
            }
            request.extend_from_slice(&buf[..n]);
        }
        socket.write_all(response.as_bytes()).await.unwrap();
        socket.shutdown().await.unwrap();
    });
    let mut configuration = Configuration::new();
    configuration.base_path = format!("http://127.0.0.1:{port}");
    configuration
}

fn error<T: std::fmt::Debug>(result: Result<impl std::fmt::Debug, Error<T>>) -> ResponseContent<T> {
    match result {
        Err(Error::ResponseError(content)) => content,
        other => panic!("expected a response error, got {other:?}"),
    }
}

async fn thing(status: &str, body: &'static str) -> ResponseContent<GetThingError> {
    let configuration = serve(status, body).await;
    error(
        default_api::get_thing()
            .configuration(&configuration)
            .id("x")
            .call()
            .await,
    )
}

async fn plain(status: &str, body: &'static str) -> ResponseContent<GetPlainError> {
    let configuration = serve(status, body).await;
    error(
        default_api::get_plain()
            .configuration(&configuration)
            .call()
            .await,
    )
}

/// 400 and 404 share one body type; each status decodes into its own variant.
#[tokio::test]
async fn two_statuses_of_one_type_decode_into_their_own_variant() {
    let e = thing("400 Bad Request", r#"{"message":"bad"}"#).await;
    assert!(matches!(e.entity, Some(GetThingError::Status400(p)) if p.message == "bad"));
    let e = thing("404 Not Found", r#"{"message":"gone"}"#).await;
    assert!(matches!(e.entity, Some(GetThingError::Status404(p)) if p.message == "gone"));
}

/// A status without its own response falls in its range (4XX), and a response without a body gives its variant
/// whatever the body.
#[tokio::test]
async fn a_range_and_a_response_without_body() {
    let e = thing("418 I'm a teapot", r#"{"message":"tea"}"#).await;
    assert!(matches!(e.entity, Some(GetThingError::Status4XX(p)) if p.message == "tea"));
    let e = thing("409 Conflict", r#"{"anything":1}"#).await;
    assert!(matches!(e.entity, Some(GetThingError::Status409())));
    let e = plain("503 Service Unavailable", r#"{"message":"later"}"#).await;
    assert!(matches!(e.entity, Some(GetPlainError::Status5XX(p)) if p.message == "later"));
}

/// A status no response lists decodes as the `default` response.
#[tokio::test]
async fn an_unlisted_status_is_the_default_response() {
    let e = thing("500 Internal Server Error", r#"{"reason":"boom"}"#).await;
    assert_eq!(e.status.as_u16(), 500);
    assert!(matches!(e.entity, Some(GetThingError::DefaultResponse(f)) if f.reason == "boom"));
}

/// A body that does not decode as the status's type is `UnknownValue`, also when another variant would accept it;
/// without a `default` an unlisted status is `UnknownValue`; a body that is not JSON is no entity.
#[tokio::test]
async fn a_body_that_does_not_decode_is_unknown_value() {
    let e = thing("404 Not Found", r#"{"reason":"not a problem"}"#).await;
    assert!(
        matches!(&e.entity, Some(GetThingError::UnknownValue(v)) if v["reason"] == "not a problem"),
        "{:?}",
        e.entity
    );
    let e = thing("500 Internal Server Error", r#"{"message":"a problem, not a fallback"}"#).await;
    assert!(matches!(e.entity, Some(GetThingError::UnknownValue(_))), "{:?}", e.entity);
    let e = plain("401 Unauthorized", r#"{"message":"who"}"#).await;
    assert!(matches!(e.entity, Some(GetPlainError::UnknownValue(_))), "{:?}", e.entity);
    let e = plain("400 Bad Request", "not json").await;
    assert!(e.entity.is_none());
    assert_eq!(e.content, "not json");
    let configuration = serve("404 Not Found", "[1]").await;
    let e = error(
        default_api::get_bare()
            .configuration(&configuration)
            .call()
            .await,
    );
    assert!(matches!(e.entity, Some(GetBareError::UnknownValue(_))));
}

/// The error carries the response's headers (decision 4).
#[tokio::test]
async fn the_error_has_the_response_headers() {
    let e = thing("429 Too Many Requests", r#"{"message":"slow down"}"#).await;
    assert_eq!(e.headers["x-request-id"], "req-429 Too Many Requests");
    assert_eq!(e.headers["retry-after"], "30");
    assert_eq!(e.headers[reqwest::header::CONTENT_TYPE], "application/json");
    let configuration = serve("503 Service Unavailable", "{}").await;
    let e = error(
        default_api::get_bare()
            .configuration(&configuration)
            .call()
            .await,
    );
    assert_eq!(e.headers.get("retry-after").map(|v| v.to_str().unwrap()), Some("30"));
}
