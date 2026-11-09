//! What cookie parameters look like on the wire, on the crate generated from src/test/resources/cookie_params.yaml.
//! `CookieParamsTest` copies this file into that crate's tests/ and runs cargo. A one-shot HTTP server on a local port
//! answers each call with 204 and hands back the request it read.

use cookie_params_fixture::apis::configuration::{ApiKey, Configuration};
use cookie_params_fixture::apis::default_api;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

const ID: &str = "67e55044-10b1-426f-9247-bb680e5fe0c8";

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

fn with_key(mut configuration: Configuration, key: &str) -> Configuration {
    configuration.api_key = Some(ApiKey {
        prefix: None,
        key: key.to_owned(),
    });
    configuration
}

/// Every cookie parameter that is set, then the api key, in one `Cookie` header: `name=value` pairs joined with `; `.
/// What is not a cookie-octet (space, `"`, `,`, `;`, `\`, controls, non-ASCII) and `%` are percent-encoded; an
/// exploded array is a pair per item, an array with `explode: false` one pair with the items joined by `,`.
#[tokio::test]
async fn the_cookies_go_out_in_one_header() {
    let (configuration, request) = serve().await;
    let configuration = with_key(configuration, "k%ey");
    default_api::get_items()
        .configuration(&configuration)
        .session("a b;c\"d")
        .theme("dark")
        .at(chrono::DateTime::parse_from_rfc3339("2026-01-02T03:04:05+01:00").unwrap())
        .id(uuid::Uuid::parse_str(ID).unwrap())
        .tags(vec!["x".to_owned(), "y z".to_owned()])
        .ids(vec!["1".to_owned(), "2,3".to_owned()])
        .call()
        .await
        .unwrap();
    let request = request.await.unwrap();
    let cookies: Vec<&str> = request
        .lines()
        .filter(|line| line.to_ascii_lowercase().starts_with("cookie:"))
        .collect();
    assert_eq!(cookies.len(), 1, "{request}");
    assert_eq!(
        header(&request, "cookie"),
        Some(
            format!(
                "session=a%20b%3Bc%22d; theme=dark; at=2026-01-02T03:04:05+01:00; id={ID}; tags=x; tags=y%20z; \
                 ids=1,2%2C3; api_token=k%25ey"
            )
            .as_str()
        )
    );
}

/// Optional cookies left out are not sent; with no api key either, there is no `Cookie` header.
#[tokio::test]
async fn unset_ones_are_left_out() {
    let (configuration, request) = serve().await;
    default_api::get_items()
        .configuration(&configuration)
        .session("s")
        .call()
        .await
        .unwrap();
    assert_eq!(header(&request.await.unwrap(), "cookie"), Some("session=s"));

    let (configuration, request) = serve().await;
    default_api::get_prefs()
        .configuration(&configuration)
        .call()
        .await
        .unwrap();
    assert_eq!(header(&request.await.unwrap(), "cookie"), None);
}

/// An api key sent as a cookie goes out alone where the operation has no cookie parameter, and not at all where the
/// operation does not use that security scheme.
#[tokio::test]
async fn an_api_key_cookie() {
    let (configuration, request) = serve().await;
    let configuration = with_key(configuration, "secret");
    default_api::get_keyed()
        .configuration(&configuration)
        .call()
        .await
        .unwrap();
    assert_eq!(header(&request.await.unwrap(), "cookie"), Some("api_token=secret"));

    let (configuration, request) = serve().await;
    let configuration = with_key(configuration, "secret");
    default_api::get_plain()
        .configuration(&configuration)
        .call()
        .await
        .unwrap();
    assert_eq!(header(&request.await.unwrap(), "cookie"), None);
}

/// The encoding on its own: cookie-octets stay, everything else becomes `%XX` (UTF-8 bytes).
#[test]
fn cookie_encode_keeps_cookie_octets() {
    use cookie_params_fixture::apis::cookie_encode;
    assert_eq!(cookie_encode("abc-._~!#$&'()*+/:<=>?@[]^`{|}"), "abc-._~!#$&'()*+/:<=>?@[]^`{|}");
    assert_eq!(cookie_encode(" \",;\\%\u{7f}\té"), "%20%22%2C%3B%5C%25%7F%09%C3%A9");
}
