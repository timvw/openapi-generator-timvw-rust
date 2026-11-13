//! `ProviderLayer`: requests built like a generated client's, moved to another provider.

use std::sync::{Arc, Mutex};

use timvw_rust_runtime::http::header::AUTHORIZATION;
use timvw_rust_runtime::provider::ProviderLayer;
use timvw_rust_runtime::{http, Body, BoxError, Client, Method, RequestOptions, StatusCode};

const GENERATED_BASE: &str = "https://api.openai.com/v1";

/// What a service saw: method, URL and headers of each request.
type Seen = Arc<Mutex<Vec<(Method, String, http::HeaderMap)>>>;

/// A client that records each request and answers `200`.
fn recording() -> (Client, Seen) {
    let seen = Seen::default();
    let record = seen.clone();
    let service = tower::service_fn(move |request: http::Request<Body>| {
        record.lock().unwrap().push((
            request.method().clone(),
            request.uri().to_string(),
            request.headers().clone(),
        ));
        async { Ok::<_, BoxError>(http::Response::new(Body::from("ok"))) }
    });
    (Client::from_service(service), seen)
}

/// Sends a request the way a generated operation builds it: the base path, a path, query parameters, its own
/// authentication, and the call's options last.
async fn generated_call(client: &Client, options: &RequestOptions) -> StatusCode {
    client
        .request(Method::POST, &format!("{GENERATED_BASE}/chat/completions"))
        .query(&[("stream", "false"), ("note", "a b&c")])
        .bearer_auth("sk-generated")
        .header("x-generated", "1")
        .json(&serde_json::json!({"model": "m"}))
        .options(options)
        .send()
        .await
        .unwrap()
        .status()
}

#[tokio::test]
async fn an_openrouter_like_provider() {
    let (client, seen) = recording();
    let provider = ProviderLayer::new("https://router.example/api/v1")
        .unwrap()
        .rewrite_from(GENERATED_BASE)
        .unwrap()
        .sensitive_header(AUTHORIZATION, "Bearer sk-router")
        .unwrap()
        .header("x-title", "my app")
        .unwrap();
    let client = client.layer(provider);
    let options = RequestOptions::new().query(&[("trace", "1")]).unwrap();
    assert_eq!(generated_call(&client, &options).await, StatusCode::OK);

    let seen = seen.lock().unwrap();
    let (method, url, headers) = &seen[0];
    assert_eq!(method, Method::POST);
    assert_eq!(
        url,
        "https://router.example/api/v1/chat/completions?stream=false&note=a+b%26c&trace=1"
    );
    assert_eq!(
        headers.get_all(AUTHORIZATION).iter().collect::<Vec<_>>(),
        ["Bearer sk-router"]
    );
    assert!(headers[AUTHORIZATION].is_sensitive());
    assert_eq!(headers["x-title"], "my app");
    assert_eq!(headers["x-generated"], "1");
    assert_eq!(headers["content-type"], "application/json");
}

#[tokio::test]
async fn an_azure_like_provider() {
    let (client, seen) = recording();
    let provider = ProviderLayer::new("https://my-resource.example.com/openai")
        .unwrap()
        .rewrite_from(GENERATED_BASE)
        .unwrap()
        .sensitive_header("api-key", "azure-secret")
        .unwrap()
        .query(&[("api-version", "2024-10-21")])
        .unwrap()
        .map_path(|path| format!("/deployments/gpt-4o{path}"));
    let client = client.layer(provider);
    // A call that sets api-version itself still sends the provider's, once.
    let options = RequestOptions::new()
        .query(&[("api-version", "1999-01-01")])
        .unwrap();
    assert_eq!(generated_call(&client, &options).await, StatusCode::OK);

    let seen = seen.lock().unwrap();
    let (_, url, headers) = &seen[0];
    assert_eq!(
        url,
        "https://my-resource.example.com/openai/deployments/gpt-4o/chat/completions?stream=false&note=a+b%26c&api-version=2024-10-21"
    );
    assert_eq!(headers["api-key"], "azure-secret");
    assert!(headers["api-key"].is_sensitive());
    assert!(!format!("{headers:?}").contains("azure-secret"));
    // The generated authentication is kept unless the provider replaces it.
    assert_eq!(headers[AUTHORIZATION], "Bearer sk-generated");
}

#[tokio::test]
async fn with_base_path_set_to_the_provider_no_rewrite_is_needed() {
    let (client, seen) = recording();
    let client = client.layer(
        ProviderLayer::new("http://localhost:11434/v1")
            .unwrap()
            .header("x-provider", "local")
            .unwrap(),
    );
    // configuration.base_path = "http://localhost:11434/v1"
    let status = client
        .request(Method::GET, "http://localhost:11434/v1/models?limit=2")
        .send()
        .await
        .unwrap()
        .status();
    assert_eq!(status, StatusCode::OK);
    let seen = seen.lock().unwrap();
    assert_eq!(seen[0].1, "http://localhost:11434/v1/models?limit=2");
    assert_eq!(seen[0].2["x-provider"], "local");
}

#[tokio::test]
async fn other_hosts_get_neither_the_rewrite_nor_the_credentials() {
    let (client, seen) = recording();
    let client = client.layer(
        ProviderLayer::new("https://router.example/api/v1")
            .unwrap()
            .rewrite_from(GENERATED_BASE)
            .unwrap()
            .sensitive_header("api-key", "secret")
            .unwrap()
            .query(&[("api-version", "1")])
            .unwrap(),
    );
    client
        .request(Method::GET, "https://files.example.com/v1/download?sig=x")
        .send()
        .await
        .unwrap();
    let seen = seen.lock().unwrap();
    assert_eq!(seen[0].1, "https://files.example.com/v1/download?sig=x");
    assert!(seen[0].2.get("api-key").is_none());
}

#[tokio::test]
async fn a_mapped_path_is_percent_encoded() {
    let (client, seen) = recording();
    let client = client.layer(
        ProviderLayer::new("https://router.example")
            .unwrap()
            .map_path(|_| "/a b?c".to_owned()),
    );
    client
        .request(Method::GET, "https://router.example/x?q=1")
        .send()
        .await
        .unwrap();
    assert_eq!(
        seen.lock().unwrap()[0].1,
        "https://router.example/a%20b%3Fc?q=1"
    );
}

#[cfg(all(feature = "reqwest", feature = "retry"))]
#[tokio::test]
async fn on_a_reqwest_client_it_rewrites_once_before_the_retries() {
    use std::time::Duration;
    use timvw_rust_runtime::retry::{RetryLayer, RetryPolicy};
    use timvw_rust_runtime::ReqwestService;
    use wiremock::matchers::{header, method, path, query_param};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/openai/deployments/d/chat/completions"))
        .and(query_param("api-version", "2024-10-21"))
        .and(query_param("stream", "false"))
        .and(header("api-key", "secret"))
        .respond_with(ResponseTemplate::new(503))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/openai/deployments/d/chat/completions"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&server)
        .await;

    let provider = ProviderLayer::new(&format!("{}/openai", server.uri()))
        .unwrap()
        .rewrite_from(GENERATED_BASE)
        .unwrap()
        .sensitive_header("api-key", "secret")
        .unwrap()
        .query(&[("api-version", "2024-10-21")])
        .unwrap()
        .map_path(|path| format!("/deployments/d{path}"));
    let client = Client::from_service(ReqwestService::default())
        .layer(RetryLayer::new(
            RetryPolicy::default().base_delay(Duration::from_millis(1)),
        ))
        .layer(provider);
    assert_eq!(
        generated_call(&client, &RequestOptions::new()).await,
        StatusCode::OK
    );
    let requests = server.received_requests().await.unwrap();
    assert_eq!(requests.len(), 2);
    for request in requests {
        assert_eq!(request.url.path(), "/openai/deployments/d/chat/completions");
        assert_eq!(
            request.url.query(),
            Some("stream=false&note=a+b%26c&api-version=2024-10-21")
        );
    }
}
