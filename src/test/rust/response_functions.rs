//! The `_response` functions of `responseFunctions` at run time, on the crate generated from
//! src/test/resources/response_functions.yaml. `ResponseFunctionsTest` copies this file into that crate's tests/ and
//! runs cargo. A one-shot HTTP server on a local port answers each call.

use std::borrow::Cow;

use response_functions::apis::configuration::Configuration;
use response_functions::apis::{items_api, ApiResponse, Error};
use response_functions::models::Item;
use serde::Deserialize;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

const ITEM: &str = r#"{"id":"item-1","name":"first","tags":["a","b"],"owner":{"name":"ann"}}"#;

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

async fn get_item(configuration: &Configuration) -> ApiResponse<Item> {
    items_api::get_item_response()
        .configuration(configuration)
        .id("item-1")
        .call()
        .await
        .unwrap()
}

/// `json()` decodes what the operation's own function returns.
#[tokio::test]
async fn json_is_the_generated_type() {
    let configuration = serve(
        "200 OK",
        "content-type: application/json\r\n",
        ITEM,
    )
    .await;
    let response = get_item(&configuration).await;
    let item: Item = response.json().unwrap();
    assert_eq!(item.name, "first");
    assert_eq!(item.tags, Some(vec!["a".to_owned(), "b".to_owned()]));

    let configuration = serve("200 OK", "content-type: application/json\r\n", ITEM).await;
    let own = items_api::get_item()
        .configuration(&configuration)
        .id("item-1")
        .call()
        .await
        .unwrap();
    assert_eq!(own, item);
}

/// `json_as()` decodes into a type of the caller's: only the fields it names.
#[tokio::test]
async fn json_as_is_an_owned_subset() {
    #[derive(Deserialize)]
    struct Owner {
        name: String,
    }
    #[derive(Deserialize)]
    struct Subset {
        id: String,
        owner: Owner,
    }
    let configuration = serve("200 OK", "content-type: application/json\r\n", ITEM).await;
    let subset: Subset = get_item(&configuration).await.json_as().unwrap();
    assert_eq!(subset.id, "item-1");
    assert_eq!(subset.owner.name, "ann");
}

/// `json_borrowed()` decodes into a type that borrows from the body.
#[tokio::test]
async fn json_borrowed_borrows_from_the_body() {
    #[derive(Deserialize)]
    struct Names<'a> {
        id: &'a str,
        #[serde(borrow)]
        name: Cow<'a, str>,
    }
    let configuration = serve("200 OK", "content-type: application/json\r\n", ITEM).await;
    let response = get_item(&configuration).await;
    let names: Names = response.json_borrowed().unwrap();
    assert_eq!(names.id, "item-1");
    assert!(response.text().contains(names.id));
    assert_eq!(names.name, "first");
    assert!(matches!(names.name, Cow::Borrowed(_)));
}

/// `text()`, `status` and `headers` are the response's.
#[tokio::test]
async fn text_status_and_headers() {
    let configuration = serve(
        "203 Non-Authoritative Information",
        "content-type: application/json\r\nx-request-id: req-7\r\n",
        ITEM,
    )
    .await;
    let response = get_item(&configuration).await;
    assert_eq!(response.text(), ITEM);
    assert_eq!(response.status, timvw_rust_runtime::StatusCode::NON_AUTHORITATIVE_INFORMATION);
    assert_eq!(response.headers["x-request-id"], "req-7");
    assert_eq!(response.headers["content-type"], "application/json");

    // A success without a body: the status and headers are what is there.
    let configuration = serve("204 No Content", "location: /items/item-1\r\n", "").await;
    let deleted = items_api::delete_item_response()
        .configuration(&configuration)
        .id("item-1")
        .call()
        .await
        .unwrap();
    assert_eq!(deleted.status, timvw_rust_runtime::StatusCode::NO_CONTENT);
    assert_eq!(deleted.headers["location"], "/items/item-1");
    assert_eq!(deleted.text(), "");

    // A text body: read it with text(); the operation's own function returns it as a String.
    let configuration = serve("200 OK", "content-type: text/plain\r\n", "1.2.3").await;
    let version = items_api::get_version_response()
        .configuration(&configuration)
        .call()
        .await
        .unwrap();
    assert_eq!(version.text(), "1.2.3");
}

/// The body is not checked against the content type: decoding it is the caller's choice, and fails as serde's.
#[tokio::test]
async fn a_body_that_is_not_json_fails_to_decode() {
    let configuration = serve("200 OK", "content-type: text/html\r\n", "<html></html>").await;
    let response = get_item(&configuration).await;
    assert!(response.json().is_err());
    assert_eq!(response.text(), "<html></html>");
}

/// An error status is the operation's typed error, as from the operation's own function.
#[tokio::test]
async fn an_error_status_is_the_typed_error() {
    let configuration = serve(
        "404 Not Found",
        "content-type: application/json\r\n",
        r#"{"detail":"no such item"}"#,
    )
    .await;
    let error = items_api::get_item_response()
        .configuration(&configuration)
        .id("item-2")
        .call()
        .await
        .unwrap_err();
    match error {
        Error::ResponseError(content) => {
            assert_eq!(content.status, timvw_rust_runtime::StatusCode::NOT_FOUND);
            match content.entity {
                Some(items_api::GetItemError::Status404(problem)) => {
                    assert_eq!(problem.detail, "no such item")
                }
                other => panic!("{other:?}"),
            }
        }
        other => panic!("{other:?}"),
    }
}

