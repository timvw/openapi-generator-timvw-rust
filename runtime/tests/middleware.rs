//! Middleware on a client: logging, headers, an async layer of your own, limits, and tower-http's layers.
#![cfg(all(feature = "reqwest", feature = "retry", feature = "tracing"))]

use std::future::Future;
use std::io::Write;
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll};
use std::time::Duration;

use timvw_rust_runtime::http::{self, HeaderValue};
use timvw_rust_runtime::retry::RetryPolicy;
use timvw_rust_runtime::trace::TraceLayer;
use timvw_rust_runtime::{Body, Client, Method, StatusCode};
use tower::{Layer, Service, ServiceBuilder};
use wiremock::matchers::{header, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

/// Collects what a `tracing` subscriber writes.
#[derive(Clone, Default)]
struct Logs(Arc<Mutex<Vec<u8>>>);

impl Write for Logs {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.0.lock().unwrap().extend_from_slice(buf);
        Ok(buf.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

impl Logs {
    fn text(&self) -> String {
        String::from_utf8(self.0.lock().unwrap().clone()).unwrap()
    }
}

async fn get(client: &Client, url: String) -> StatusCode {
    client
        .request(Method::GET, &url)
        .send()
        .await
        .unwrap()
        .status()
}

#[tokio::test]
async fn the_trace_layer_logs_requests_and_retries_without_the_query() {
    let server = MockServer::start().await;
    Mock::given(path("/items"))
        .respond_with(ResponseTemplate::new(503))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    Mock::given(path("/items"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&server)
        .await;

    let logs = Logs::default();
    let writer = logs.clone();
    let subscriber = tracing_subscriber::fmt()
        .with_max_level(tracing::Level::DEBUG)
        .with_ansi(false)
        .with_writer(move || writer.clone())
        .finish();
    let _guard = tracing::subscriber::set_default(subscriber);

    let client = Client::default()
        .layer(TraceLayer::new())
        .layer(tower::retry::RetryLayer::new(
            RetryPolicy::new(2).base_delay(Duration::from_millis(1)),
        ));
    let status = get(&client, format!("{}/items?api_key=secret", server.uri())).await;
    assert_eq!(status, StatusCode::OK);

    let text = logs.text();
    assert!(text.contains("http.request.method=GET"), "{text}");
    assert!(
        text.contains(&format!("url.full={}/items", server.uri())),
        "{text}"
    );
    assert!(
        text.contains("WARN") && text.contains("status=503"),
        "{text}"
    );
    assert!(
        text.contains("retrying") && text.contains("attempt=1"),
        "{text}"
    );
    assert!(text.contains("status=200"), "{text}");
    assert!(!text.contains("secret"), "{text}");
}

#[tokio::test]
async fn a_header_on_every_request() {
    let server = MockServer::start().await;
    Mock::given(header("x-team", "search"))
        .and(header("x-request-source", "batch"))
        .respond_with(ResponseTemplate::new(204))
        .mount(&server)
        .await;

    let client = Client::default()
        .layer(tower::util::MapRequestLayer::new(
            |mut request: http::Request<Body>| {
                request
                    .headers_mut()
                    .insert("x-team", HeaderValue::from_static("search"));
                request
            },
        ))
        .layer(tower_http::set_header::SetRequestHeaderLayer::overriding(
            http::header::HeaderName::from_static("x-request-source"),
            HeaderValue::from_static("batch"),
        ));
    assert_eq!(get(&client, server.uri()).await, StatusCode::NO_CONTENT);
}

/// A token that may need fetching before a request: the shape of a layer of your own that does async work.
#[derive(Clone)]
struct BearerTokenLayer {
    token: Arc<tokio::sync::Mutex<Option<String>>>,
}

impl<S> Layer<S> for BearerTokenLayer {
    type Service = BearerToken<S>;
    fn layer(&self, inner: S) -> Self::Service {
        BearerToken {
            inner,
            token: self.token.clone(),
        }
    }
}

#[derive(Clone)]
struct BearerToken<S> {
    inner: S,
    token: Arc<tokio::sync::Mutex<Option<String>>>,
}

impl<S> Service<http::Request<Body>> for BearerToken<S>
where
    S: Service<http::Request<Body>> + Clone + Send + 'static,
    S::Future: Send,
{
    type Response = S::Response;
    type Error = S::Error;
    type Future = Pin<Box<dyn Future<Output = Result<S::Response, S::Error>> + Send>>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), S::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, mut request: http::Request<Body>) -> Self::Future {
        // The clone that was polled ready is the one that calls.
        let clone = self.inner.clone();
        let mut inner = std::mem::replace(&mut self.inner, clone);
        let token = self.token.clone();
        Box::pin(async move {
            let token = token
                .lock()
                .await
                .get_or_insert_with(|| "fetched-token".to_owned())
                .clone();
            let value = HeaderValue::from_str(&format!("Bearer {token}")).unwrap();
            request
                .headers_mut()
                .insert(http::header::AUTHORIZATION, value);
            inner.call(request).await
        })
    }
}

#[tokio::test]
async fn a_layer_of_your_own_can_do_async_work() {
    let server = MockServer::start().await;
    Mock::given(header("authorization", "Bearer fetched-token"))
        .respond_with(ResponseTemplate::new(200))
        .expect(2)
        .mount(&server)
        .await;

    let client = Client::default().layer(BearerTokenLayer {
        token: Arc::default(),
    });
    assert_eq!(get(&client, server.uri()).await, StatusCode::OK);
    assert_eq!(get(&client, server.uri()).await, StatusCode::OK);
}

#[tokio::test]
async fn concurrency_and_rate_limits() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(200).set_delay(Duration::from_millis(20)))
        .mount(&server)
        .await;

    // ConcurrencyLimit is Clone but not Sync; a Client takes it anyway, and its clones share the limit.
    let client = Client::default().layer(tower::limit::ConcurrencyLimitLayer::new(2));
    let started = std::time::Instant::now();
    let requests = (0..4).map(|_| get(&client, server.uri()));
    for status in futures_util::future::join_all(requests).await {
        assert_eq!(status, StatusCode::OK);
    }
    assert!(started.elapsed() >= Duration::from_millis(40));

    // RateLimit is not Clone: a Buffer in front of it shares one limiter between the client's clones.
    let client = Client::default().layer(
        ServiceBuilder::new()
            .buffer(64)
            .rate_limit(2, Duration::from_millis(100)),
    );
    let started = std::time::Instant::now();
    let requests = (0..3).map(|_| get(&client, server.uri()));
    for status in futures_util::future::join_all(requests).await {
        assert_eq!(status, StatusCode::OK);
    }
    assert!(started.elapsed() >= Duration::from_millis(100));
}

#[tokio::test]
async fn tower_http_trace_layer_fits() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(200).set_body_string("ok"))
        .mount(&server)
        .await;

    let client = Client::default().layer(tower_http::trace::TraceLayer::new_for_http());
    let response = client
        .request(Method::GET, &server.uri())
        .send()
        .await
        .unwrap();
    assert_eq!(response.text().await.unwrap(), "ok");
}
