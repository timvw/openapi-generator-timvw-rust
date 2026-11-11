//! The typed response headers of `responseFunctions` at run time, on the crate generated from
//! src/test/resources/response_headers.yaml. `ResponseHeadersTest` copies this file into that crate's tests/ and runs
//! cargo. A one-shot HTTP server on a local port answers the end-to-end call.

use response_headers::apis::configuration::Configuration;
use response_headers::apis::images_api::{self, CreateImageResponseHeaders, DeleteImageResponseHeaders};
use response_headers::apis::ApiResponse;
use response_headers::models::{Image, Level, Status};
use reqwest::header::{HeaderMap, HeaderValue};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

fn headers(pairs: &[(&'static str, &'static str)]) -> HeaderMap {
    let mut map = HeaderMap::new();
    for (name, value) in pairs {
        map.append(*name, HeaderValue::from_static(value));
    }
    map
}

/// Serves one request with this status line, extra header lines and body.
async fn serve(status: &str, headers: &str, body: &'static str) -> Configuration {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let response = format!(
        "HTTP/1.1 {status}\r\n{headers}content-length: {}\r\nconnection: close\r\n\r\n{body}",
        body.len()
    );
    tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.unwrap();
        let mut buf = [0u8; 4096];
        let mut request = Vec::new();
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

/// Every declared type is read from its text.
#[test]
fn each_type_parses() {
    let h = CreateImageResponseHeaders::from_headers(&headers(&[
        ("x-ratelimit-remaining-images", "42"),
        ("x-ratelimit-reset-images", "6m0s"),
        ("retry-after", "30"),
        ("x-cost", "0.25"),
        ("x-cached", "true"),
        ("x-request-id", "6f1c2a7e-3b4d-4c8e-9f00-1a2b3c4d5e6f"),
        ("last-modified-at", "2026-11-11T10:20:30+01:00"),
        ("x-day", "2026-11-11"),
        ("x-status", "done"),
        ("x-level", "2"),
        ("x-mode", "fast"),
        ("x-ids", "1, 2,3"),
        ("x-tags", "a,b , c"),
        ("x-statuses", "queued,done"),
        ("x-object", "a=1"),
        ("x-content", "7"),
        ("type", "png"),
        ("x-shared", "9"),
        ("location", "/images/1"),
    ]));
    assert_eq!(h.x_ratelimit_remaining_images, Some(42i32));
    assert_eq!(h.x_ratelimit_reset_images.as_deref(), Some("6m0s"));
    // Retry-After is an integer in the 200 and a string in the 201: it is read as text.
    assert_eq!(h.retry_after.as_deref(), Some("30"));
    assert_eq!(h.x_cost, Some(0.25f64));
    assert_eq!(h.x_cached, Some(true));
    assert_eq!(
        h.x_request_id,
        Some("6f1c2a7e-3b4d-4c8e-9f00-1a2b3c4d5e6f".parse::<uuid::Uuid>().unwrap())
    );
    let at = h.last_modified_at.unwrap();
    assert_eq!(at.to_rfc3339(), "2026-11-11T10:20:30+01:00");
    assert_eq!(h.x_day, Some(chrono::NaiveDate::from_ymd_opt(2026, 11, 11).unwrap()));
    assert_eq!(h.x_status, Some(Status::Done));
    assert_eq!(h.x_level, Some(Level::Variant2));
    assert_eq!(h.x_mode.as_deref(), Some("fast"));
    assert_eq!(h.x_ids, Some(vec![1i64, 2, 3]));
    assert_eq!(
        h.x_tags,
        Some(vec!["a".to_owned(), "b".to_owned(), "c".to_owned()])
    );
    assert_eq!(h.x_statuses, Some(vec![Status::Queued, Status::Done]));
    assert_eq!(h.x_object.as_deref(), Some("a=1"));
    assert_eq!(h.x_content, Some(7i64));
    assert_eq!(h.r#type.as_deref(), Some("png"));
    assert_eq!(h.x_shared, Some(9i64));
    assert_eq!(h.location.as_deref(), Some("/images/1"));
}

/// A missing header, or one whose value does not parse, is `None`; nothing fails.
#[test]
fn missing_or_unparseable_is_none() {
    assert_eq!(
        CreateImageResponseHeaders::from_headers(&HeaderMap::new()),
        CreateImageResponseHeaders::default()
    );
    let h = CreateImageResponseHeaders::from_headers(&headers(&[
        ("x-ratelimit-remaining-images", "many"),
        ("x-ratelimit-reset-images", ""),
        ("x-cost", "cheap"),
        ("x-cached", "yes"),
        ("x-request-id", "not-a-uuid"),
        ("last-modified-at", "Wed, 11 Nov 2026 10:20:30 GMT"),
        ("x-day", "11/11/2026"),
        ("x-status", "unheard-of"),
        ("x-level", "7"),
        ("x-ids", "1,two,3"),
        ("x-statuses", "queued,lost"),
        ("x-content", "99999999999999999999"),
    ]));
    assert_eq!(h.x_ratelimit_remaining_images, None);
    // An empty value is still the (empty) text of a string header.
    assert_eq!(h.x_ratelimit_reset_images.as_deref(), Some(""));
    assert_eq!(h.x_cost, None);
    assert_eq!(h.x_cached, None);
    assert_eq!(h.x_request_id, None);
    assert_eq!(h.last_modified_at, None);
    assert_eq!(h.x_day, None);
    assert_eq!(h.x_status, None);
    assert_eq!(h.x_level, None);
    assert_eq!(h.x_ids, None);
    assert_eq!(h.x_statuses, None);
    assert_eq!(h.x_content, None);
    assert_eq!(h.location, None);
    // A value that is not UTF-8 is None as well.
    let mut map = HeaderMap::new();
    map.insert("location", HeaderValue::from_bytes(b"/caf\xe9").unwrap());
    assert_eq!(CreateImageResponseHeaders::from_headers(&map).location, None);
}

/// Header names are matched ignoring case, values are trimmed, a list spans every occurrence of its header and
/// skips empty items.
#[test]
fn names_ignore_case_and_lists_span_occurrences() {
    let h = CreateImageResponseHeaders::from_headers(&headers(&[
        ("X-RateLimit-Remaining-Images", " 5 "),
        ("X-IDS", "1,,2"),
        ("x-ids", "3"),
        ("X-Tags", ""),
    ]));
    assert_eq!(h.x_ratelimit_remaining_images, Some(5));
    assert_eq!(h.x_ids, Some(vec![1, 2, 3]));
    assert_eq!(h.x_tags, Some(vec![]));
}

/// `typed_headers` reads them from an `ApiResponse`; `From<&HeaderMap>` does the same.
#[test]
fn typed_headers_of_an_api_response() {
    let response: ApiResponse<Image> = ApiResponse::new(
        reqwest::StatusCode::NO_CONTENT,
        headers(&[("X-Deleted-Count", "3")]),
        String::new(),
    );
    let h: DeleteImageResponseHeaders = response.typed_headers();
    assert_eq!(h.x_deleted_count, Some(3));
    assert_eq!(DeleteImageResponseHeaders::from(&response.headers), h);
}

/// End to end: the `_response` function's headers, typed.
#[tokio::test]
async fn from_a_response_function() {
    let configuration = serve(
        "200 OK",
        "content-type: application/json\r\nX-RateLimit-Remaining-Images: 12\r\nX-Status: queued\r\n\
         Retry-After: 2\r\n",
        r#"{"url":"https://example.com/1.png"}"#,
    )
    .await;
    let response = images_api::create_image_response()
        .configuration(&configuration)
        .call()
        .await
        .unwrap();
    let h = response.typed_headers::<CreateImageResponseHeaders>();
    assert_eq!(h.x_ratelimit_remaining_images, Some(12));
    assert_eq!(h.x_status, Some(Status::Queued));
    assert_eq!(h.retry_after.as_deref(), Some("2"));
    assert_eq!(h.location, None);
    assert_eq!(response.json().unwrap().url, "https://example.com/1.png");
}
