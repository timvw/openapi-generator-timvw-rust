//! The typed response headers of `responseFunctions` at run time, successes and errors, on the crate generated from
//! src/test/resources/response_headers.yaml. `ResponseHeadersTest` copies this file into that crate's tests/ and runs
//! cargo. A one-shot HTTP server on a local port answers the end-to-end calls.

use response_headers::apis::configuration::Configuration;
use response_headers::apis::images_api::{
    self, CreateImageError, CreateImageErrorHeaders, CreateImageResponseHeaders, DeleteImageResponseHeaders,
    DownloadImageErrorHeaders, GetImageErrorHeaders,
};
use response_headers::apis::{ApiResponse, Error, ResponseContent};
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

/// The error headers: the union of what the error statuses declare (`429`, `503`, `default`), each an `Option`.
#[test]
fn error_headers_are_the_union_of_the_error_statuses() {
    let h = CreateImageErrorHeaders::from_headers(&headers(&[
        ("Retry-After", "30"),
        ("x-maintenance-until", "2026-11-11T12:00:00Z"),
        ("x-request-id", "6f1c2a7e-3b4d-4c8e-9f00-1a2b3c4d5e6f"),
        ("x-trace-id", " abc "),
        ("connection", "close"),
    ]));
    assert_eq!(h.retry_after, Some(30));
    assert_eq!(h.x_maintenance_until.unwrap().to_rfc3339(), "2026-11-11T12:00:00+00:00");
    assert_eq!(
        h.x_request_id,
        Some("6f1c2a7e-3b4d-4c8e-9f00-1a2b3c4d5e6f".parse::<uuid::Uuid>().unwrap())
    );
    assert_eq!(h.x_trace_id.as_deref(), Some("abc"));
    // A 429 has only its own header; the others are None, and nothing that does not parse fails.
    let only = CreateImageErrorHeaders::from_headers(&headers(&[("retry-after", "soon"), ("x-request-id", "nope")]));
    assert_eq!(only, CreateImageErrorHeaders::default());
    // Retry-After is an integer in the 429 and a string in the 404: text.
    let get = GetImageErrorHeaders::from_headers(&headers(&[("retry-after", "Wed, 11 Nov 2026 10:20:30 GMT")]));
    assert_eq!(get.retry_after.as_deref(), Some("Wed, 11 Nov 2026 10:20:30 GMT"));
    // Lists and enums, as for a success.
    let download = DownloadImageErrorHeaders::from_headers(&headers(&[("x-missing-ids", "4, 5"), ("x-status", "done")]));
    assert_eq!(download.x_missing_ids, Some(vec![4, 5]));
    assert_eq!(download.x_status, Some(Status::Done));
}

/// `ResponseContent::typed_headers` reads them from an error; `From<&HeaderMap>` does the same.
#[test]
fn typed_headers_of_a_response_content() {
    let content: ResponseContent<CreateImageError> = ResponseContent {
        status: reqwest::StatusCode::SERVICE_UNAVAILABLE,
        content: String::new(),
        entity: None,
        headers: headers(&[("x-trace-id", "t-1")]),
    };
    let h: CreateImageErrorHeaders = content.typed_headers();
    assert_eq!(h.x_trace_id.as_deref(), Some("t-1"));
    assert_eq!(CreateImageErrorHeaders::from(&content.headers), h);
}

/// End to end: an error status of the operation's function, its declared headers typed.
#[tokio::test]
async fn from_an_error_response() {
    let configuration = serve(
        "429 Too Many Requests",
        "content-type: application/json\r\nRetry-After: 7\r\nX-Trace-Id: t-9\r\n",
        r#"{"detail":"slow down"}"#,
    )
    .await;
    let error = images_api::create_image()
        .configuration(&configuration)
        .call()
        .await
        .unwrap_err();
    let Error::ResponseError(content) = error else {
        panic!("not a response error: {error:?}");
    };
    assert_eq!(content.status, reqwest::StatusCode::TOO_MANY_REQUESTS);
    let h = content.typed_headers::<CreateImageErrorHeaders>();
    assert_eq!(h.retry_after, Some(7));
    assert_eq!(h.x_trace_id.as_deref(), Some("t-9"));
    assert_eq!(h.x_maintenance_until, None);
}

/// A file operation's error is a `ResponseContent` too, so its declared error headers are typed as well.
#[tokio::test]
async fn from_a_file_operation_error() {
    let configuration = serve("404 Not Found", "X-Missing-Ids: 1,2\r\nX-Status: queued\r\n", "").await;
    let error = images_api::download_image()
        .configuration(&configuration)
        .id("1")
        .call()
        .await
        .unwrap_err();
    let Error::ResponseError(content) = error else {
        panic!("not a response error: {error:?}");
    };
    let h = content.typed_headers::<DownloadImageErrorHeaders>();
    assert_eq!(h.x_missing_ids, Some(vec![1, 2]));
    assert_eq!(h.x_status, Some(Status::Queued));
}
