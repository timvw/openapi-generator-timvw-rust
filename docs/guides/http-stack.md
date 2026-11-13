# Guide: the HTTP stack

A generated client sends its requests through `Configuration.client`, a `timvw_rust_runtime::Client`: a
[tower](https://docs.rs/tower) service, reqwest unless replaced. It has been the default since 0.25 and is the only
stack since 0.29, which removed `httpStack=reqwest-middleware`
([moving off it](migration.md#from-028-to-029)).

The examples use [`docs_examples.yaml`](../../src/test/resources/docs_examples.yaml), generated as `my_client`. They
are compiled by the tests.

## The runtime crate

The crate depends on [`timvw-rust-runtime`](../../runtime/README.md), released from this
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
and failed connections, waits until the time `Retry-After` (seconds or an HTTP date) or `retry-after-ms` gives, up to
60 s, or else backs off exponentially with jitter (`RetryPolicy::server_directed_429(true)` retries a `429` only when
the server gives a time), and does not retry a request with a body that can be read only once (a stream). A `429` that
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

To send the same API to another provider (an OpenAI-compatible service, a proxy, a cloud deployment), add a
`timvw_rust_runtime::ProviderLayer` to `configuration.client`: it moves requests from the generated `base_path` (declared
with `rewrite_from`) to the provider's base URL, and sets the provider's headers, query parameters (an `api-version`)
and path (a deployment). Or set `configuration.base_path` to the provider and use the layer for the rest only. See
[providers](../../runtime/README.md#providers).

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
`RequestOptions` (`apis::RequestOptions`, the runtime's) with extra headers and query parameters, and another base URL.
They are applied after everything the function sets, parameters, authentication, `Content-Type` and `Accept`
included: each header replaces the values the request has under its name, and the query parameters are appended to
the generated ones. `header`, `query` and `base_url` check their input, and fail with an `Error` whose `is_builder()`
is true:

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
setter is `.request_options(..)`. One `RequestOptions` can be reused for many calls. With `bonBuilders=false` (since
0.29) the options are each function's last argument, `None` for none:

<!-- not compiled: bonBuilders=false -->
```rust
let page = items_api::list_items(&configuration, Some(10), Some(&options)).await?;
let page = items_api::list_items(&configuration, Some(10), None).await?;
```

`RequestOptions::base_url` (runtime 0.4) sends one call to another base URL: it replaces `configuration.base_path`
(scheme, host, port and path prefix), and the operation's own path and query are kept, so
`base_url("https://eu.api.example.com/v2")` sends `GET {base_path}/items?limit=10` to
`https://eu.api.example.com/v2/items?limit=10`. There is no per-call path: the operation decides it.

Per-call options win over a `ProviderLayer` (runtime 0.4): the layer does not replace a header or query parameter the
options set (with an `api-version` in both, the call's own is sent, once), and it does not move a call the options sent
to a base URL of their own. When that base is the provider's (or under it), the layer still maps the path and adds its
headers and query; any other base gets nothing from it, so the provider's credentials stay with the provider.

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
compression leave it out. An api key in a query parameter is part of the URL.

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

A multipart union with a file variant (`multipartHelper`, such as a `oneOf` of a file and a JSON object) holds an
`Upload` in that variant too, and `Vec<Upload>` for an array of files. Its builder setter takes anything that converts
into the union: an `Upload` or a `PathBuf` (the file variant), or the union itself. `Union::file(..)` and
`Union::files(..)` build the file variants from anything that converts into an `Upload` (in openai-client-base):

<!-- not compiled: openai-client-base's API (src/test/rust/multipart_unions.rs runs the same calls on a fixture) -->
```rust
// A video from bytes held in memory; the other variant, an object, is still a JSON part.
videos_api::create_video_edit()
    .configuration(&configuration)
    .video(Upload::bytes(mp4).file_name("clip.mp4").mime("video/mp4"))
    .prompt("slower")
    .call()
    .await?;
// An array of files: a part each.
skills_api::create_skill()
    .configuration(&configuration)
    .files(models::CreateSkillBodyFiles::files([PathBuf::from("SKILL.md"), PathBuf::from("main.py")]))
    .call()
    .await?;
```

The file variants are `#[serde(skip)]`: an upload has no JSON form, so serializing a union (or a model holding it) that
holds a file is an error, and JSON never decodes to one. The unions are request bodies, sent part by part.

A model's file field (since 0.29) holds an `Upload` too: `Option<Upload>`, `Vec<Upload>` for an array of files, or in a
map. That is a multipart body schema that is a model as well (openai-client-base's `CreateContainerFileBody`, also a
JSON body), or any other schema with a `format: binary` property. The field is `#[serde(skip)]`: the model's JSON leaves
it out, and JSON never sets it (`None`, empty). A required field holding one upload is left out of the JSON too, and
decoding the model from JSON fails. `new` takes anything that converts into an upload for a required file (a `PathBuf`,
bytes), and with `bonBuilders` the setters convert, as for parameters:

<!-- not compiled: openai-client-base's model -->
```rust
use std::path::PathBuf;

let body = models::CreateContainerFileBody::builder().file(PathBuf::from("data.csv")).build();
assert_eq!(serde_json::to_string(&body)?, "{}"); // the file is no JSON
```

### Typed event streams

With `typedEventStreams`, a `text/event-stream` response is an `apis::sse::EventStream<T, E>`, whose
`idle_timeout(duration)` ends a stream that stalls (no bytes, not even a `: keep-alive` comment, for `duration`) with
an `Error::Http` whose `is_timeout()` is true. There is no limit by default. The crate depends on the runtime's `time`
feature for it (tokio's timer natively, the host's `setTimeout` on WebAssembly).

### WebAssembly

A tower client builds for `wasm32-unknown-unknown` (a browser, a worker, Node.js) as it is, with its default features:

```sh
cargo build --target wasm32-unknown-unknown
```

The runtime does what differs, behind `#[cfg(target_family = "wasm")]`: reqwest sends with the host's `fetch`, retries
back off and honour `Retry-After` with the host's timer, nothing needs to be `Send` (the bounds are the runtime's
`MaybeSend` and `MaybeSync`, and `EventStream::from_bytes` takes them too), and tokio is not a dependency. TLS is the
host's: `native-tls` and `rustls` select nothing there. There is no file system: give a file parameter bytes
(`Upload::bytes`, a `Vec<u8>`) or a stream (`Upload::stream`, `Upload::from_fn`), and so does the file variant of a
multipart union; a `PathBuf` compiles but fails when the request is built, with an `Error::Http` whose `is_io()` is
true. A streamed upload is read into memory before `fetch` sends it. `fetch` has no timeout: add the runtime's
`timeout::TimeoutLayer` to `configuration.client` (runtime 0.3.1, feature `time`, which the `retry` feature turns on),
which waits with the host's timer; tower's `TimeoutLayer` needs tokio's timer and does not work there
([timeouts](../../runtime/README.md#timeouts)). A crate with `uuid`s depends on uuid's `js`
feature there (randomness from `crypto.getRandomValues`). The runtime's guide has
[the details](../../runtime/README.md#webassembly).

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

A crate with a file parameter depends on the runtime with its `fs` feature (files read with tokio, `Response::save_to`),
one with typed event streams with its `time` feature (`EventStream::idle_timeout`). Neither brings tokio or files to
a [WebAssembly](#webassembly) build.

With `apiFeatures`, an API module whose functions are gated one by one (a group that takes part of a tag) allows unused
imports itself, as a feature set may compile none of the functions that use one: a crate that keeps a `lib.rs` of its
own needs no `allow(unused_imports)`.

Without `native-tls` and `rustls`, `Client::default()` fails every request; set a client with `Client::from_service`.
