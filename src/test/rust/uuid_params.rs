//! What `format: uuid` parameters and form fields look like on the wire, on the crate generated from
//! src/test/resources/uuid_params.yaml. `UuidParamsTest` copies this file into that crate's tests/ and runs cargo.
//! A one-shot HTTP server on a local port answers each call with 204 and hands back the request it read.

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use uuid::Uuid;
use uuid_params_fixture::apis::configuration::Configuration;
use uuid_params_fixture::apis::default_api;

const A: &str = "67e55044-10b1-426f-9247-bb680e5fe0c8";
const B: &str = "a1a2a3a4-b1b2-c1c2-d1d2-d3d4d5d6d7d8";
const C: &str = "00000000-0000-0000-0000-00000000000c";

fn id(text: &str) -> Uuid {
    Uuid::parse_str(text).unwrap()
}

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

/// The body of a request: what follows the head.
fn body(request: &str) -> &str {
    &request[request.find("\r\n\r\n").unwrap() + 4..]
}

/// A uuid is sent hyphenated and lower case: as is in the path (it is URL-safe), in the query and in a header;
/// arrays join their items with `,` or repeat the parameter.
#[tokio::test]
async fn a_uuid_is_sent_hyphenated() {
    let (configuration, request) = serve().await;
    // upper case in, lower case out: the type, not the caller's text, decides the form
    let upper = id(&A.to_uppercase());
    default_api::get_item()
        .configuration(&configuration)
        .id(upper)
        .ids(vec![id(A), id(B)])
        .owner(id(B))
        .parent(id(C))
        .before(id(A))
        .tags(vec![id(A), id(B)])
        .tags_csv(vec![id(B), id(C)])
        .x_request_id(id(A))
        .x_trace_id(id(B))
        .x_span_ids(vec![id(A), id(C)])
        .call()
        .await
        .unwrap();
    let request = request.await.unwrap();
    assert_eq!(
        request.lines().next().unwrap(),
        format!(
            "GET /items/{A}/{A},{B}?owner={B}&parent={C}&before={A}&tags={A}&tags={B}&tagsCsv={B}%2C{C} HTTP/1.1"
        )
    );
    assert_eq!(header(&request, "x-request-id"), Some(A));
    assert_eq!(header(&request, "x-trace-id"), Some(B));
    assert_eq!(header(&request, "x-span-ids"), Some(format!("{A},{C}").as_str()));
}

/// Optional ones left out are not sent.
#[tokio::test]
async fn optional_ones_left_out_are_not_sent() {
    let (configuration, request) = serve().await;
    default_api::get_item()
        .configuration(&configuration)
        .id(id(A))
        .ids(vec![id(A)])
        .owner(id(B))
        .tags(vec![id(C)])
        .x_request_id(id(A))
        .call()
        .await
        .unwrap();
    let request = request.await.unwrap();
    assert_eq!(
        request.lines().next().unwrap(),
        format!("GET /items/{A}/{A}?owner={B}&tags={C} HTTP/1.1")
    );
    assert_eq!(header(&request, "x-trace-id"), None);
}

/// An urlencoded form field and a multipart text part hold the hyphenated text.
#[tokio::test]
async fn a_uuid_form_field_is_sent_hyphenated() {
    let (configuration, request) = serve().await;
    default_api::post_form()
        .configuration(&configuration)
        .id(id(A))
        .parent(id(B))
        .ids(vec![id(B), id(C)])
        .call()
        .await
        .unwrap();
    let request = request.await.unwrap();
    let mut fields: Vec<&str> = body(&request).split('&').collect();
    fields.sort();
    assert_eq!(
        fields,
        vec![
            format!("id={A}"),
            format!("ids={B}%2C{C}"),
            format!("parent={B}"),
        ]
    );

    let (configuration, request) = serve().await;
    default_api::post_upload()
        .configuration(&configuration)
        .id(id(A))
        .parent(id(B))
        .call()
        .await
        .unwrap();
    let request = request.await.unwrap();
    let body = body(&request).to_ascii_lowercase();
    for (name, value) in [("id", A), ("parent", B)] {
        let part = format!("content-disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n");
        assert!(body.contains(&part), "{part:?} not in {body}");
    }
}
