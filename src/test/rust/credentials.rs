//! What the credentials of a `Configuration` look like on the wire, on the crate generated from
//! src/test/resources/credentials.yaml. `CredentialsTest` copies this file into that crate's tests/ and runs cargo. A
//! one-shot HTTP server on a local port answers each call with 204 and hands back the request it read.

use credentials_fixture::apis::configuration::{ApiKey, Configuration, ExposeSecret, SecretString};
use credentials_fixture::apis::default_api;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

/// Serves one request with 204; returns the request head.
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
            if n == 0 || String::from_utf8_lossy(&request).contains("\r\n\r\n") {
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

/// A configuration with every credential set, from `&str`, `String` and `SecretString::from`.
fn with_every_credential(mut configuration: Configuration) -> Configuration {
    configuration.bearer_access_token = Some("bearer-secret".into());
    configuration.oauth_access_token = Some(SecretString::from("oauth-secret".to_owned()));
    configuration.basic_auth = Some(("user".to_owned(), Some("basic-secret".into())));
    configuration.api_key = Some(ApiKey {
        prefix: Some("Token".to_owned()),
        key: "key-secret".into(),
    });
    configuration
}

#[test]
fn debug_redacts_every_secret() {
    let configuration = with_every_credential(Configuration::new());
    let debug = format!("{configuration:?}");
    for secret in [
        "bearer-secret",
        "oauth-secret",
        "basic-secret",
        "key-secret",
    ] {
        assert!(!debug.contains(secret), "{secret} in {debug}");
    }
    assert!(debug.contains("user"), "{debug}");
    assert!(debug.contains("Token"), "{debug}");
    let key = &configuration.api_key.as_ref().unwrap().key;
    assert_eq!(key.expose_secret(), "key-secret");
}

#[tokio::test]
async fn bearer_goes_out_as_authorization() {
    let (configuration, request) = serve().await;
    let configuration = with_every_credential(configuration);
    default_api::with_bearer()
        .configuration(&configuration)
        .call()
        .await
        .unwrap();
    let request = request.await.unwrap();
    assert_eq!(
        header(&request, "authorization"),
        Some("Bearer bearer-secret"),
        "{request}"
    );
}

#[tokio::test]
async fn oauth_goes_out_as_a_bearer_token() {
    let (configuration, request) = serve().await;
    let configuration = with_every_credential(configuration);
    default_api::with_oauth()
        .configuration(&configuration)
        .call()
        .await
        .unwrap();
    let request = request.await.unwrap();
    assert_eq!(
        header(&request, "authorization"),
        Some("Bearer oauth-secret"),
        "{request}"
    );
}

#[tokio::test]
async fn basic_goes_out_as_authorization() {
    let (configuration, request) = serve().await;
    let configuration = with_every_credential(configuration);
    default_api::with_basic()
        .configuration(&configuration)
        .call()
        .await
        .unwrap();
    let request = request.await.unwrap();
    // base64("user:basic-secret")
    assert_eq!(
        header(&request, "authorization"),
        Some("Basic dXNlcjpiYXNpYy1zZWNyZXQ="),
        "{request}"
    );
}

#[tokio::test]
async fn basic_without_a_password() {
    let (mut configuration, request) = serve().await;
    configuration.basic_auth = Some(("user".to_owned(), None));
    default_api::with_basic()
        .configuration(&configuration)
        .call()
        .await
        .unwrap();
    let request = request.await.unwrap();
    // base64("user:")
    assert_eq!(
        header(&request, "authorization"),
        Some("Basic dXNlcjo="),
        "{request}"
    );
}

#[tokio::test]
async fn an_api_key_goes_out_in_its_header() {
    let (configuration, request) = serve().await;
    let configuration = with_every_credential(configuration);
    default_api::with_header_key()
        .configuration(&configuration)
        .call()
        .await
        .unwrap();
    let request = request.await.unwrap();
    assert_eq!(
        header(&request, "x-api-key"),
        Some("Token key-secret"),
        "{request}"
    );
}

#[tokio::test]
async fn an_api_key_goes_out_in_its_query_parameter() {
    let (configuration, request) = serve().await;
    let configuration = with_every_credential(configuration);
    default_api::with_query_key()
        .configuration(&configuration)
        .call()
        .await
        .unwrap();
    let request = request.await.unwrap();
    assert!(
        request.starts_with("GET /query-key?api_key=Token+key-secret "),
        "{request}"
    );
}

#[tokio::test]
async fn an_api_key_goes_out_in_its_cookie() {
    let (configuration, request) = serve().await;
    let configuration = with_every_credential(configuration);
    default_api::with_cookie_key()
        .configuration(&configuration)
        .call()
        .await
        .unwrap();
    let request = request.await.unwrap();
    assert_eq!(
        header(&request, "cookie"),
        Some("api_token=Token%20key-secret"),
        "{request}"
    );
}
