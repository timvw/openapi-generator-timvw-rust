# Guide: the HTTP stack (`httpStack`)

A generated client sends its requests through `Configuration.client`. What that is depends on `httpStack`:

| `httpStack` | `Configuration.client` | Since |
| --- | --- | --- |
| `tower` (default) | `timvw_rust_runtime::Client`: a [tower](https://docs.rs/tower) service, reqwest unless replaced | 0.25 |
| `reqwest-middleware` | `reqwest_middleware::ClientWithMiddleware` | up to 0.24 the only stack |

The generated functions are the same with both: the same arguments, the same models, the same `ResponseContent`. What
differs is how you configure sending, the variants of `apis::Error` that report a failure to send, and, with `tower`,
[headers and query parameters for one call](#headers-and-query-parameters-for-one-call).

The examples use [`docs_examples.yaml`](../../src/test/resources/docs_examples.yaml), generated as `my_client`. They
are compiled by the tests.

## `tower`: the runtime crate

With `httpStack=tower` the crate depends on [`timvw-rust-runtime`](../../runtime/README.md), released from this
repository next to the generator. Its `Client` is a tower service from `http::Request<Body>` to `http::Response<Body>`:

- `Client::default()` sends with reqwest (the crate's default features `native-tls` or `rustls` choose its TLS) and
  retries transient failures.
- `client.layer(layer)` wraps it in a tower layer: `tower::retry`, `tower::timeout`, rate limits, `tower-http`'s
  tracing, or a layer of your own. Layers apply from the inside out: the last one added sees a request first.
- `Client::from_service(service)` sends with any other service: a hyper client, a service that records requests, or a
  mock in tests.
- `Client::from_reqwest(client)` sends with a `reqwest::Client` you configured (proxy, TLS, timeouts).

Retries are on by default (feature `retry`): `Client::default()`, and so `Configuration::new()`, retries with
`RetryPolicy::default()`. `RetryPolicy` retries `429`, `408` and `5xx` responses
and failed connections, waits as long as `Retry-After` (or `retry-after-ms`) asks, up to 60 s, or else backs off
exponentially with jitter, and does not retry a request with a body that can be read only once (a stream). A `429` that
will not pass by waiting, such as OpenAI's `insufficient_quota`, is not retried once you turn on
`timvw_rust_runtime::retry::RetryLayer::permanent_429` (opt-in, since what makes a `429` permanent differs between
APIs; see [permanent `429`s](../../runtime/README.md#permanent-429s-an-exhausted-quota)). A body held in memory, which is every JSON, form and text body, is sent again, and
so is a file: it is opened again for each attempt (see [uploads and downloads](#uploads-and-downloads)). Layers added to `Client::default()` wrap its retries; for
another policy, or none, start from `Client::from_service(ReqwestService::default())`:

```rust
use std::time::Duration;
use timvw_rust_runtime::retry::RetryPolicy;
use timvw_rust_runtime::tower::retry::RetryLayer;
use timvw_rust_runtime::{Client, ReqwestService};

let mut configuration = Configuration::new(); // retries with RetryPolicy::default()
let policy = RetryPolicy::new(5).max_delay(Duration::from_secs(30));
configuration.client = Client::from_service(ReqwestService::default()).layer(RetryLayer::new(policy));
```

Logging (feature `tracing`), headers, authentication, timeouts and rate limits are layers too. The
[runtime's guide](../../runtime/README.md) covers each:
[retries](../../runtime/README.md#retries), [timeouts](../../runtime/README.md#timeouts),
[logging](../../runtime/README.md#logging), [middleware](../../runtime/README.md#middleware) (a header on every
request, a layer of your own that fetches a token),
[concurrency and rate limits](../../runtime/README.md#concurrency-and-rate-limits).

A service of your own answers without a network, for tests:

```rust
use timvw_rust_runtime::http;
use timvw_rust_runtime::{Body, BoxError, Client};

let service = timvw_rust_runtime::tower::service_fn(|_request: http::Request<Body>| async {
    let page = r#"{"data":[{"id":"1","name":"Lamp"}],"has_more":false}"#;
    Ok::<_, BoxError>(http::Response::builder().header("content-type", "application/json").body(Body::from(page))?)
});
let mut configuration = Configuration::new();
configuration.client = Client::from_service(service);
let page = items_api::list_items().configuration(&configuration).call().await?;
assert_eq!(page.data[0].name, "Lamp");
```

### Headers and query parameters for one call

A layer changes every request. For one call, every operation's builder takes `.options(&options)`: a
`RequestOptions` (`apis::RequestOptions`, the runtime's) with extra headers and query parameters. They are applied
after everything the function sets, parameters, authentication, `Content-Type` and `Accept` included: each header
replaces the values the request has under its name, and the query parameters are appended to the generated ones.
`header` and `query` check their input, and fail with an `Error` whose `is_builder()` is true:

```rust
use my_client::apis::RequestOptions;

let options = RequestOptions::new()
    .header("x-request-source", "batch")? // replaces a generated header of that name
    .query(&[("trace", "1")])?; // after the generated query
let page = items_api::list_items()
    .configuration(&configuration)
    .limit(10)
    .options(&options)
    .call()
    .await?;
```

Every method of an operation takes them: the `_response` twins, the methods of `contentTypeMethods` (multipart
included) and the `_stream` methods of `typedEventStreams`. When an operation has a parameter named `options`, the
setter is `.request_options(..)`. One `RequestOptions` can be reused for many calls. There is no such argument with
`bonBuilders=false`, nor with `httpStack=reqwest-middleware`.

To send or decode types of your own, call `configuration.client` directly, with the same layers; for a
`text/event-stream` response, `response.events().json::<T>()` decodes each event. The runtime's guide shows
[how](../../runtime/README.md#your-own-types-and-server-sent-events). A generated `apis::sse` re-exports the runtime's
parser (`Decoder`, `Event`, `Events`).

A failure to send is `Error::Http(timvw_rust_runtime::Error)`: `is_builder()` (the request could not be built),
`is_request()` (the service failed; `is_timeout()` and `is_connect()` narrow it down) or `is_body()` (reading the
response failed). Its `source()` is the service's own error, a `reqwest::Error` or a layer's.

The generated code builds requests with `timvw_rust_runtime::RequestBuilder` and reads them with
`timvw_rust_runtime::Response`, which have the methods of reqwest's that the code uses, so it reads as before. Status
codes and headers are the `http` crate's types (`timvw_rust_runtime::StatusCode`, `timvw_rust_runtime::header`), the
same types reqwest re-exports.

### Credentials

The secrets of `Configuration` are `secrecy::SecretString`s, as async-openai keeps its key:
`bearer_access_token`, `oauth_access_token`, the password of `basic_auth` and the `key` of `api_key` (the username and
the key's `prefix` stay `String`s). `configuration::SecretString` and `configuration::ExposeSecret` re-export the
runtime's. A `SecretString` is made from a `String` or a `&str` (`.into()` or `SecretString::from`), prints as
`[REDACTED]`, and is zeroed in memory when dropped:

```rust
use my_client::apis::configuration::{ApiKey, ExposeSecret, SecretString};

let mut configuration = Configuration::new();
configuration.bearer_access_token = Some(SecretString::from(std::env::var("API_TOKEN")?));
configuration.basic_auth = Some(("user".to_owned(), Some("password".into())));
configuration.api_key = Some(ApiKey { prefix: None, key: "sk-123".into() });
println!("{configuration:?}"); // the secrets print as [REDACTED]
let key: &str = configuration.api_key.as_ref().unwrap().key.expose_secret();
```

The generated code exposes a secret only where it builds the request, and sends an `Authorization`, api-key or
`Cookie` header marked sensitive (`HeaderValue::set_sensitive`), so `Debug` of the request's headers and HTTP/2 header
compression leave it out. An api key in a query parameter is part of the URL. With `httpStack=reqwest-middleware` the
credentials are `String`s, as before 0.26.

### Uploads and downloads

A file parameter (`format: binary`, as a multipart part or as the whole body) is a `timvw_rust_runtime::Upload`, and
its builder setter takes anything that converts into one: a `PathBuf` or a `&Path` (a file, streamed from disk with
tokio), a `Vec<u8>` or `Bytes` (held in memory), or an `Upload` made from a reader or a stream. A file is sent with a
`Content-Length` (its length is read from its metadata before the request is built; a missing file fails then, with
`Error::Http` whose `is_io()` is true), its multipart part is named after the file and typed from its extension
(`image/png`), and a retry opens it again. `Upload::bytes(..).file_name(..).mime(..)` names and types bytes; a part
without a name is named `file`:

```rust
use std::path::PathBuf;
use timvw_rust_runtime::Upload;

// A path: streamed from disk, opened again if the request is retried.
items_api::upload_item_image()
    .configuration(&configuration)
    .id("1")
    .image(PathBuf::from("lamp.png"))
    .call()
    .await?;

// Bytes held in memory, with a file name and a media type of your own.
let png: Vec<u8> = std::fs::read("lamp.png")?;
items_api::upload_item_image()
    .configuration(&configuration)
    .id("1")
    .image(Upload::bytes(png).file_name("lamp.png").mime("image/png"))
    .call()
    .await?;

// A binary response, written to a file as it arrives.
let response = items_api::get_item_image().configuration(&configuration).id("1").call().await?;
response.save_to("lamp-copy.png").await?;
```

`Upload::reader` and `Upload::stream` are read once: a request with one is not retried, unless
`.buffer_for_retry(limit)` lets it be held in memory when it is no longer than `limit`. `Upload::from_fn` makes a
fresh stream for every attempt. `Upload::path(..).slice(range)` sends a part of a file, for APIs that take a large file
in parts. A binary response is a `timvw_rust_runtime::Response`: `save_to(path)` streams it into a temporary file next
to `path` and renames it once complete, so `path` never holds half a download; `into_async_read()` reads it as a
tokio `AsyncRead`. The runtime's guide has [the details](../../runtime/README.md#uploads-and-downloads).

With `httpStack=reqwest-middleware` file parameters stay `std::path::PathBuf`s.

### The runtime version

The generated `Cargo.toml` asks for `timvw-rust-runtime = "^<runtimeVersion>"` (default: the release this generator
version renders for). A runtime fix reaches a client with `cargo update`, without generating again. To build against an
unreleased runtime, `runtimePath` adds a path dependency next to the version; the generator's own tests and CI do this
with the `runtime/` directory of the same commit.

### Features of the generated crate

| Feature | Default | What it enables |
| --- | --- | --- |
| `native-tls` or `rustls` | `native-tls` (`reqwestDefaultFeatures`) | reqwest, with that TLS, as `Client::default()` |
| `retry` | yes | `timvw_rust_runtime::retry` |
| `tracing` | no | `timvw_rust_runtime::trace::TraceLayer`: logs each request and retry with `tracing` |

A crate with a file parameter depends on the runtime with its `fs` feature (files read with tokio, `Response::save_to`).

Without `native-tls` and `rustls`, `Client::default()` fails every request; set a client with `Client::from_service`.

## `reqwest-middleware`

`httpStack=reqwest-middleware` renders what 0.24 rendered: `Configuration.client` is a
`reqwest_middleware::ClientWithMiddleware`, `apis::Error` has `Reqwest(reqwest::Error)` and
`ReqwestMiddleware(reqwest_middleware::Error)`, and the crate depends on `reqwest` and `reqwest-middleware`. Use it to
keep middleware written for reqwest-middleware, such as `reqwest-retry` or `reqwest-tracing`, until you move them to
tower layers.

## Moving from `reqwest-middleware` to `tower`

| With `reqwest-middleware` | With `tower` |
| --- | --- |
| `reqwest_middleware::ClientBuilder::new(reqwest_client).build()` | `timvw_rust_runtime::Client::from_reqwest(reqwest_client)` |
| `.with(RetryTransientMiddleware::new_with_policy(..))` | nothing: `Client::default()` retries; for another policy `Client::from_service(ReqwestService::default()).layer(RetryLayer::new(policy))` |
| `.with(TracingMiddleware::default())` | `.layer(tower_http::trace::TraceLayer::new_for_http())` |
| a `reqwest_middleware::Middleware` of your own | a `tower::Layer` of your own (or `tower::ServiceBuilder::map_request`) |
| `Error::Reqwest(e)`, `Error::ReqwestMiddleware(e)` | `Error::Http(e)` |
| `reqwest::StatusCode`, `reqwest::header::*` | `timvw_rust_runtime::StatusCode`, `timvw_rust_runtime::header::*` (the same types) |
| a file response is a `reqwest::Response` | a file response is a `timvw_rust_runtime::Response` (`status()`, `headers()`, `bytes()`, `bytes_stream()`) |
