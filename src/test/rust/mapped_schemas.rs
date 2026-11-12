//! What a schema mapped to a type of the crate's own does on the wire, on the crate generated from
//! src/test/resources/mapped_schemas.yaml with `--schema-mappings Filter=crate::custom::Filter,Circle=crate::custom::Circle,Token=crate::custom::Token`.
//! `MappedSchemasTest` copies this file into that crate's tests/ and runs cargo. A one-shot HTTP server on a local
//! port answers each call with 200 and a JSON body, and hands back the request it read.

use mapped_schemas::apis::configuration::Configuration;
use mapped_schemas::apis::default_api;
use mapped_schemas::custom::{Circle, Filter, Token};
use mapped_schemas::models::{Choice, Mode, Shape};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

/// Serves one request with 200 and `body` as JSON; returns the request.
async fn serve(body: &'static str) -> (Configuration, tokio::task::JoinHandle<String>) {
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
        let response = format!(
            "HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}",
            body.len()
        );
        socket.write_all(response.as_bytes()).await.unwrap();
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

/// The crate's own Filter is the body, the JSON query parameter, the header (as its JSON, as a generated struct's
/// would be), each item of the array query parameter (as its JSON) and the response.
#[tokio::test]
async fn the_mapped_type_is_sent_and_read_as_json() {
    let (configuration, request) = serve(r#"{"name":"back","mode":"prefix"}"#).await;
    let back = default_api::search()
        .configuration(&configuration)
        .filter2(&Filter::exact("body"))
        .filter(Filter::exact("query"))
        .x_filter(Filter::exact("header"))
        .filters(vec![Filter::exact("a"), Filter { name: "b".into(), mode: None }])
        .call()
        .await
        .unwrap();
    assert_eq!(back, Filter { name: "back".into(), mode: Some(Mode::Prefix) });
    let request = request.await.unwrap();
    let query = request.lines().next().unwrap();
    assert!(
        query.starts_with(
            "POST /search?filter=%7B%22name%22%3A%22query%22%2C%22mode%22%3A%22exact%22%7D\
             &filters=%7B%22name%22%3A%22a%22%2C%22mode%22%3A%22exact%22%7D\
             &filters=%7B%22name%22%3A%22b%22%7D "
        ),
        "{query}"
    );
    assert_eq!(header(&request, "x-filter"), Some(r#"{"name":"header","mode":"exact"}"#));
    assert_eq!(body(&request), r#"{"name":"body","mode":"exact"}"#);
}

/// A field, an array item and a map value of the mapped type decode into it.
#[tokio::test]
async fn fields_items_and_map_values_are_the_mapped_type() {
    let (configuration, _request) = serve(
        r#"{"filter":{"name":"f"},"filters":[{"name":"a","mode":"exact"}],"byName":{"b":{"name":"b"}}}"#,
    )
    .await;
    let result = default_api::list_results()
        .configuration(&configuration)
        .token(Token("a b".into()))
        .call()
        .await
        .unwrap();
    let filter: Filter = result.filter;
    assert_eq!(filter.name, "f");
    assert_eq!(result.filters.unwrap(), vec![Filter::exact("a")]);
    assert_eq!(result.by_name.unwrap()["b"], Filter { name: "b".into(), mode: None });
}

/// A union variant of the mapped type: untagged, it is tried as any struct is; tagged, its tag value selects it.
#[tokio::test]
async fn union_variants_hold_the_mapped_type() {
    let (configuration, request) = serve(r#"[{"name":"x"}]"#).await;
    let filters = default_api::choose()
        .configuration(&configuration)
        .choice(&Choice::Filter(Filter::exact("c")))
        .call()
        .await
        .unwrap();
    assert_eq!(filters, vec![Filter { name: "x".into(), mode: None }]);
    assert_eq!(body(&request.await.unwrap()), r#"{"name":"c","mode":"exact"}"#);
    assert_eq!(serde_json::from_str::<Choice>(r#""text""#).unwrap(), Choice::Text("text".into()));

    let (configuration, _request) = serve(r#"{"kind":"circle","radius":2.5}"#).await;
    let shape = default_api::get_shape()
        .configuration(&configuration)
        .call()
        .await
        .unwrap();
    assert_eq!(shape, Shape::Circle(Circle { kind: "circle".into(), radius: 2.5 }));
}

/// A type in place of a string schema is sent with `Display` in the path, the query and a header, and as its JSON in
/// a body; a response decodes into it.
#[tokio::test]
async fn a_mapped_scalar_is_sent_with_display() {
    let (configuration, request) = serve(r#"{"filter":{"name":"f"},"next":"n"}"#).await;
    let result = default_api::list_results()
        .configuration(&configuration)
        .token(Token("a/b c".into()))
        .after(Token("x y".into()))
        .x_token(Token("h".into()))
        .call()
        .await
        .unwrap();
    assert_eq!(result.next, Some(Token("n".into())));
    let request = request.await.unwrap();
    assert_eq!(request.lines().next().unwrap(), "GET /results/a%2Fb%20c?after=x+y HTTP/1.1");
    assert_eq!(header(&request, "x-token"), Some("h"));

    let (configuration, request) = serve(r#""back""#).await;
    let back = default_api::put_token()
        .configuration(&configuration)
        .body(&Token("t".into()))
        .tokens(vec![Token("a".into()), Token("b".into())])
        .call()
        .await
        .unwrap();
    assert_eq!(back, Token("back".into()));
    let request = request.await.unwrap();
    assert_eq!(request.lines().next().unwrap(), "PUT /tokens?tokens=a&tokens=b HTTP/1.1");
    assert_eq!(body(&request), r#""t""#);
}
