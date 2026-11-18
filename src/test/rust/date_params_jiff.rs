//! What `date` and `date-time` parameters and form fields look like on the wire with `dateLibrary=jiff`, on the
//! crate generated from src/test/resources/date_params.yaml. `DateParamsTest` copies this file into that crate's
//! tests/ and runs cargo.
//! A one-shot HTTP server on a local port answers each call with 204 and hands back the request it read.

use jiff::civil::Date;
use jiff::Timestamp;
use date_params_fixture::apis::configuration::Configuration;
use date_params_fixture::apis::default_api;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

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

fn at(text: &str) -> Timestamp {
    text.parse().unwrap()
}

fn day(text: &str) -> Date {
    text.parse().unwrap()
}

/// The value of a header in the request head, by its name in any case.
fn header<'a>(request: &'a str, name: &str) -> Option<&'a str> {
    request.lines().find_map(|line| {
        let (key, value) = line.split_once(':')?;
        key.eq_ignore_ascii_case(name).then(|| value.trim())
    })
}

/// A date-time is RFC 3339 in UTC (`Z`), as a `jiff::Timestamp` displays it, percent-encoded in the path and the
/// query; a date is `YYYY-MM-DD`; arrays format each item.
#[tokio::test]
async fn dates_and_date_times_are_sent_as_rfc_3339() {
    let (configuration, request) = serve().await;
    default_api::get_days()
        .configuration(&configuration)
        .at(at("2026-01-02T03:04:05+01:00"))
        .day(day("2026-01-31"))
        .ats(vec![at("2026-01-02T03:04:05Z"), at("2026-02-03T04:05:06.5-05:30")])
        .days(vec![day("2026-01-31"), day("2026-02-01")])
        .from(at("2026-01-01T00:00:00Z"))
        .before(at("2026-03-01T12:00:00+02:00"))
        .on_day(day("2026-04-01"))
        .times(vec![at("2026-01-01T00:00:00Z"), at("2026-01-02T00:00:00Z")])
        .x_at(at("2026-05-06T07:08:09+01:00"))
        .until(at("2026-12-31T23:59:59Z"))
        .since_day(day("2026-06-01"))
        .times_csv(vec![at("2026-01-01T00:00:00Z"), at("2026-01-02T00:00:00+01:00")])
        .dates(vec![day("2026-07-01"), day("2026-07-02")])
        .x_until(at("2026-08-09T10:11:12-03:00"))
        .x_day(day("2026-09-10"))
        .x_times(vec![at("2026-01-01T00:00:00Z"), at("2026-01-02T00:00:00+01:00")])
        .call()
        .await
        .unwrap();
    let request = request.await.unwrap();
    let line = request.lines().next().unwrap();
    assert_eq!(
        line,
        "GET /days/2026-01-02T02%3A04%3A05Z/2026-01-31/\
         2026-01-02T03%3A04%3A05Z,2026-02-03T09%3A35%3A06.5Z/2026-01-31,2026-02-01\
         ?from=2026-01-01T00%3A00%3A00Z\
         &until=2026-12-31T23%3A59%3A59Z\
         &before=2026-03-01T10%3A00%3A00Z\
         &onDay=2026-04-01\
         &sinceDay=2026-06-01\
         &times=2026-01-01T00%3A00%3A00Z&times=2026-01-02T00%3A00%3A00Z\
         &timesCsv=2026-01-01T00%3A00%3A00Z%2C2026-01-01T23%3A00%3A00Z\
         &dates=2026-07-01&dates=2026-07-02 HTTP/1.1"
    );
    assert_eq!(header(&request, "x-at"), Some("2026-05-06T06:08:09Z"));
    assert_eq!(header(&request, "x-until"), Some("2026-08-09T13:11:12Z"));
    assert_eq!(header(&request, "x-day"), Some("2026-09-10"));
    assert_eq!(
        header(&request, "x-times"),
        Some("2026-01-01T00:00:00Z,2026-01-01T23:00:00Z")
    );
}

/// The body of a request: what follows the head.
fn body(request: &str) -> &str {
    &request[request.find("\r\n\r\n").unwrap() + 4..]
}

/// An urlencoded form field is written like a query parameter.
#[tokio::test]
async fn an_urlencoded_form_field_is_rfc_3339() {
    let (configuration, request) = serve().await;
    default_api::post_form()
        .configuration(&configuration)
        .at(at("2026-01-02T03:04:05+01:00"))
        .day(day("2026-01-31"))
        .until(at("2026-12-31T23:59:59Z"))
        .times(vec![at("2026-01-01T00:00:00Z"), at("2026-01-02T00:00:00+01:00")])
        .call()
        .await
        .unwrap();
    let request = request.await.unwrap();
    // the fields come from a HashMap: compare them in order
    let mut fields: Vec<&str> = body(&request).split('&').collect();
    fields.sort();
    assert_eq!(
        fields,
        vec![
            "at=2026-01-02T02%3A04%3A05Z",
            "day=2026-01-31",
            "times=2026-01-01T00%3A00%3A00Z",
            "times=2026-01-01T23%3A00%3A00Z",
            "until=2026-12-31T23%3A59%3A59Z",
        ]
    );
}

/// A multipart text part holds the same text.
#[tokio::test]
async fn a_multipart_text_part_is_rfc_3339() {
    let (configuration, request) = serve().await;
    default_api::post_upload()
        .configuration(&configuration)
        .at(at("2026-01-02T03:04:05+01:00"))
        .day(day("2026-01-31"))
        .until(at("2026-12-31T23:59:59Z"))
        .call()
        .await
        .unwrap();
    let request = request.await.unwrap();
    let body = body(&request);
    for (name, value) in [
        ("at", "2026-01-02T02:04:05Z"),
        ("until", "2026-12-31T23:59:59Z"),
        ("day", "2026-01-31"),
    ] {
        let part = format!("content-disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n");
        assert!(body.to_ascii_lowercase().contains(&part.to_ascii_lowercase()), "{part:?} not in {body}");
    }
}

/// A model with dates reads and writes RFC 3339 through jiff's serde support.
#[test]
fn a_timestamp_reads_any_offset_and_writes_utc() {
    let value: Timestamp = serde_json::from_str("\"2026-01-02T03:04:05.250+01:00\"").unwrap();
    assert_eq!(serde_json::to_string(&value).unwrap(), "\"2026-01-02T02:04:05.25Z\"");
    let date: Date = serde_json::from_str("\"2026-01-31\"").unwrap();
    assert_eq!(serde_json::to_string(&date).unwrap(), "\"2026-01-31\"");
}
