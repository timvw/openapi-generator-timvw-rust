//! How parameters are serialized on the wire, on the crate generated from src/test/resources/param_serialization.yaml.
//! `ParamSerializationTest` copies this file into that crate's tests/ and runs cargo. A one-shot HTTP server on a local
//! port answers each call with 204 and hands back the request it read.

use param_serialization_fixture::apis::configuration::Configuration;
use param_serialization_fixture::apis::default_api;
use param_serialization_fixture::models;
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

/// Path arrays encode each item (a space is `%20`, a `/` `%2F`) and join them with `,`, integers too; an enum and a
/// struct (its JSON) are URL-encoded in the path too; a deepObject
/// model is `name[field]=value` (fields in serde_json's order); pipe- and space-delimited arrays are joined with `|` and ` ` (then form-encoded); an
/// array of arrays sends each inner array as its JSON; a pipe-delimited array with `explode: true` is repeated; a
/// header array of integers joins their text.
#[tokio::test]
async fn query_path_and_header_parameters() {
    let (configuration, request) = serve().await;
    let filter = models::Filter {
        name: Some("a b".to_owned()),
        min: Some(3),
    };
    default_api::get_items()
        .configuration(&configuration)
        .names(vec!["a b".to_owned(), "c/d".to_owned()])
        .nums(vec![1, 22])
        .status(models::Status::CSlashD)
        .scope(models::Filter {
            name: None,
            min: Some(1),
        })
        .filter(filter)
        .pipes(vec!["x".to_owned(), "y".to_owned()])
        .spaces(vec!["x".to_owned(), "y".to_owned()])
        .pipes_exploded(vec!["x".to_owned(), "y".to_owned()])
        .matrix(vec![vec![1, 2], vec![3]])
        .tags(vec!["t1".to_owned(), "t2".to_owned()])
        .x_nums(vec![4, 5])
        .call()
        .await
        .unwrap();
    let request = request.await.unwrap();
    assert_eq!(
        request.lines().next().unwrap(),
        "GET /items/a%20b,c%2Fd/1,22/c%2Fd/%7B%22min%22%3A1%7D\
         ?filter%5Bmin%5D=3&filter%5Bname%5D=a+b\
         &pipes=x%7Cy&spaces=x+y&pipesExploded=x&pipesExploded=y\
         &matrix=%5B1%2C2%5D&matrix=%5B3%5D\
         &tags=t1&tags=t2 HTTP/1.1"
    );
    assert_eq!(header(&request, "x-nums"), Some("4,5"));
}

/// An urlencoded form array is a pair per item (`style: form, explode: true`), or one joined pair where the
/// encoding says `explode: false` (joined with `,`, or `|` for a pipeDelimited encoding); a required nullable field
/// left `None` is sent empty.
#[tokio::test]
async fn urlencoded_form_fields() {
    let (configuration, request) = serve().await;
    default_api::post_form()
        .configuration(&configuration)
        .tags(vec!["a".to_owned(), "b".to_owned()])
        .labels(vec!["c".to_owned(), "d".to_owned()])
        .bars(vec!["e".to_owned(), "f".to_owned()])
        .call()
        .await
        .unwrap();
    let request = request.await.unwrap();
    assert_eq!(body(&request), "note=&tags=a&tags=b&labels=c%2Cd&bars=e%7Cf");

    let (configuration, request) = serve().await;
    default_api::post_form()
        .configuration(&configuration)
        .note("n")
        .call()
        .await
        .unwrap();
    assert_eq!(body(&request.await.unwrap()), "note=n");
}

/// A JSON body that is an array of uuids is named after them (`uuid`) and sent as a JSON array.
#[tokio::test]
async fn an_array_of_uuids_body() {
    let (configuration, request) = serve().await;
    let id = uuid::Uuid::parse_str("67e55044-10b1-426f-9247-bb680e5fe0c8").unwrap();
    default_api::post_ids()
        .configuration(&configuration)
        .uuid(&[id])
        .call()
        .await
        .unwrap();
    assert_eq!(
        body(&request.await.unwrap()),
        "[\"67e55044-10b1-426f-9247-bb680e5fe0c8\"]"
    );
}

/// A cycle through an inline union (Top -> TopChild -> Leaf -> Top) is boxed, so the types have a size and decode.
#[test]
fn a_cycle_through_a_union_decodes() {
    let top: models::Top = serde_json::from_str(r#"{"child":{"top":{"child":{"value":"v"}}}}"#).unwrap();
    let Some(child) = top.child else { panic!("no child") };
    let models::TopChild::Leaf(leaf) = *child else { panic!("not a leaf") };
    let Some(inner) = leaf.top.child else { panic!("no inner child") };
    assert!(matches!(*inner, models::TopChild::Other(_)));
}
