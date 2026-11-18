# Guide: the server (`targets`)

`timvw-rust` renders a client by default. The `targets` option renders the models alone, a server on
[axum](https://docs.rs/axum), or a client and a server in one crate. Both sides use the same models, so a server
generated from a spec writes the JSON its client reads. The design is in
[targets and a server](../design/targets-and-server.md); this guide covers what is implemented (its slices 1 and 2).

## Choosing the targets

```json
{ "targets": ["client", "server"] }
```

On the command line the entries are separated by `|` (a comma ends an `--additional-properties` value):
`--additional-properties=targets=client|server`.

| `targets` | The crate holds | Cargo features it adds |
| --- | --- | --- |
| `["models"]` | the models only: no `timvw-rust-runtime`, reqwest or axum | none |
| `["client"]` (the default) | the models and the client, as without the option | none |
| `["server"]` | the models and the server: axum and `http`, no `timvw-rust-runtime` | none |
| `["client", "server"]` | the models, the client and the server | `client` and `server`, both default |

`client` and `server` imply `models`. There is no `all`: a target added later would change what a configuration
renders. A model that holds a file (an `Upload`) still needs `timvw-rust-runtime`, without its HTTP client, in a crate
without the client.

### Options per target

Every option belongs to the models, to the client or to the server. An option of a target that is not in `targets`
fails generation and names its target:

```text
timvw-rust: option 'responseFunctions' applies to the client, and 'client' is not in targets [server]; remove the
option or add 'client' to targets
```

| Applies to | Options |
| --- | --- |
| The models (every target) | `stringIdSchemas`, `stringIdProperties`, `stringProperties`, `optionalProperties`, `fileProperties`, `useChrono`, `bonBuilders`, `titleNaming`, `deprecatedAttributes`, `validateMethods`, `apiFeatures`, `apiFeaturesFile`, `pruneAllHelpers`, `unionPlanFile`, `preprocessedSpecFile`, the crate options |
| The client | `runtimeVersion`, `runtimePath`, `reqwestDefaultFeatures`, `responseFunctions`, `supportMultipleResponses`, `useSerdePathToError`, `contentTypeMethods`, `typedEventStreams`, `typedEnumParameters`, `deepObjectQueryParameters`, `resetQueryParameterStyle`, `multipartHelper`, `userAgentFromPackage`, `apiLintAllows`, `webhookModule`, `webhookModuleReexport` |
| The server | `serverFramework` (only `axum`), `serverValidation` (only `false` for now) |

`validateMethods` generates the models' `Validate`, which a server's validation will call, so it belongs to the
models. `webhookModule` and `webhookModuleReexport` place the client's webhook functions; the server does not send
webhooks yet.

## The server

For each API module (a tag, as for the client: `pets_api`) the `server` module has a trait with a method per
operation, a request struct and a response enum per operation; `server::router` serves an implementation of every
trait. From [src/test/resources/server.yaml](../../src/test/resources/server.yaml):

<!-- not compiled: generated code (src/server.rs of a crate generated from src/test/resources/server.yaml) -->
```rust
pub trait PetsApi: Send + Sync {
    /// `GET /pets`: Lists the pets.
    fn list_pets(
        &self,
        request: ListPetsRequest,
    ) -> impl std::future::Future<Output = Result<ListPetsResponse, super::ServerError>> + Send;
    // ...
}

pub struct ListPetsRequest {
    /// `limit`, a query parameter: How many pets to return.
    pub limit: Option<i32>,
    /// `kind`, a query parameter
    pub kind: Option<models::Kind>,
    /// `tags`, a query parameter: Repeated, one pair per tag.
    pub tags: Option<Vec<String>>,
    /// The request's headers, with its header and cookie parameters and credentials.
    pub headers: http::HeaderMap,
}

pub enum ListPetsResponse {
    /// `200`: the pets
    Status200 { body: Vec<models::Pet>, headers: crate::headers::ListPetsResponseHeaders },
    /// `4XX`: a client error
    Status4XX { status: http::StatusCode, body: models::Error, headers: crate::headers::ListPetsErrorHeaders },
}
```

- **The trait** has a method per operation that returns `impl Future<Output = Result<_, ServerError>> + Send`, so an
  implementation writes `async fn` (Rust 1.75 and later). The future must be `Send` for axum: an implementation cannot
  hold non-`Send` state across an `.await`.
- **The request struct** holds the path and query parameters as typed fields (an optional one as an `Option`), the
  body (see [bodies](#request-and-response-bodies)), and the request's `HeaderMap`. Header and cookie parameters,
  and credentials, are read from the headers; authentication is axum middleware's job for now.
- **The response enum** has a variant per status the spec declares: `Status200(body)` when the body is all it holds,
  `Status204` without a body, a `status` field for a range (`Status4XX`) and for `Default`, and a `headers` field
  where the response declares headers. `IntoResponse` writes the status, the headers and the body.
- **Typed headers** are the `<Operation>ResponseHeaders` (successes) and `<Operation>ErrorHeaders` (errors) structs
  of `responseFunctions`, in a module of their own, `headers`, which the server writes (`write_to`, `to_header_map`)
  and the client reads (`from_headers`). In a crate with the client, `apis::<module>` re-exports them.
- **ServerError** is an implementation's error: any `std::error::Error` converts into it with `?`, and
  `ServerError::new("...")` makes one from a message.

### Serving it

<!-- not compiled: a sketch; src/test/rust/server.rs implements the traits of server.yaml in full -->
```rust
use my_api::server::{self, ErrorHandler, PetsApi, ServerError};
use my_api::server::pets_api::{ListPetsRequest, ListPetsResponse};

struct MyApi;

impl ErrorHandler for MyApi {}

impl PetsApi for MyApi {
    async fn list_pets(&self, request: ListPetsRequest) -> Result<ListPetsResponse, ServerError> {
        let pets = load_pets(request.limit).await?;
        Ok(ListPetsResponse::Status200 { body: pets, headers: Default::default() })
    }
    // ... every other operation of the trait
}

#[tokio::main]
async fn main() {
    let app: axum::Router = server::router(MyApi);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:8080").await.unwrap();
    axum::serve(listener, app).await.unwrap();
}
```

The generated crate depends on axum without default features; `axum::serve` needs axum's `tokio` and `http1` (or
`http2`) features in your own `Cargo.toml`. The router is an `axum::Router`: add layers (tracing, timeouts,
authentication) and other routes to it as usual.

### Request and response bodies

| Media type | Request body | Response body |
| --- | --- | --- |
| JSON (`application/json`, `application/*+json`) | its model | its model |
| `text/event-stream` | not served | `server::EventStream<Event>` |
| `text/*` | `String` | `String`, sent as the declared media type |
| `multipart/form-data` | `<Operation>Form`, a field per property | as anything else |
| anything else (`application/octet-stream`, `image/png`, `audio/*`, ...) | `axum::body::Bytes` | `server::Binary` |

An optional body is an `Option`: `None` when the request has none (for JSON also `null`, for multipart a request
that is not `multipart/*`). Bodies are read whole, within axum's limit of 2 MB; raise it with
`router.layer(axum::extract::DefaultBodyLimit::max(...))`.

A status that declares more than one kind of content has a variant for each, the JSON one first and unsuffixed, the
others suffixed `Stream`, `Text` or `Binary`. The implementation picks one, from the request (its `Accept` header, or
a `stream` field of its body):

<!-- not compiled: generated code (src/server.rs of a crate generated from src/test/resources/server.yaml) -->
```rust
pub enum PetFeedResponse {
    /// `200`, `application/json`: the pets, as a list or as a stream
    Status200(Vec<models::Pet>),
    /// `200`, `text/event-stream`: the pets, as a list or as a stream
    Status200Stream(super::EventStream<models::Pet>),
}
```

**Server-sent events.** An `EventStream<T>` writes each event as a `data:` line holding its JSON, which is what the
client's [typed event streams](../options.md#typedeventstreams) decode. `EventStream::new(stream)` takes a
`Stream<Item = T>`, `try_new` a `Stream<Item = Result<T, ServerError>>`, and `from_events` an iterator;
`.done()` ends the stream with `data: [DONE]`, for clients that wait for it. An error of the stream ends the response
abruptly, so the client sees the body fail rather than end:

<!-- not compiled: a sketch -->
```rust
async fn pet_feed(&self, request: PetFeedRequest) -> Result<PetFeedResponse, ServerError> {
    let pets = self.pets().await?;
    Ok(PetFeedResponse::Status200Stream(EventStream::from_events(pets).done()))
}
```

**Files and other binary content.** A `Binary` is `Binary::bytes(bytes)` (sent with a `Content-Length`) or
`Binary::stream(stream)` (chunked), and converts from `Bytes`, `Vec<u8>` and `&'static [u8]`. It is sent as the
media type the spec declares, or `application/octet-stream` for a range such as `audio/*`; `.content_type("audio/wav")`
sets another, which a range or a response with several binary types needs.

**Multipart forms.** `<Operation>Form` has a field per property of the body's schema: a `server::FilePart` (its
`bytes`, `file_name` and `content_type`) per file, `Vec<FilePart>` for a list of files, the typed value of a text part
(`String`, numbers, enums, ...), a `Vec` for a list (one part per item, or one part holding a JSON array: the client
sends the latter without [`multipartHelper`](../options.md#multiparthelper)), and the model of a JSON part. A part
that does not decode is a `400` whose `location` is `body` and `name` the field.

### Requests that do not decode, and errors

A path or query parameter that is missing or does not parse as its type, or a body that does not decode (JSON that is
not of its model, text that is not UTF-8, a multipart field that is missing or does not parse), is answered without
calling the implementation, by `ErrorHandler::invalid_request`. An implementation's `Err` is
answered by `ErrorHandler::server_error`. Both have a default:

| | Status | Body |
| --- | --- | --- |
| `invalid_request` | `400` | ``{"error": {"message": "query parameter `limit`: invalid value 'x'", "location": "query", "name": "limit"}}`` |
| `server_error` | `500` | `{"error": {"message": "internal server error"}}`; the error is not shown to the caller |

Implement either method to answer otherwise; `RequestError` has the `location` (`path`, `query` or `body`), the
parameter's `name` and the `message`.

### What the server does not serve yet

An operation the server cannot serve is left out of the trait and the router, with a comment in `src/server.rs`, a
line in the crate's README and a warning when generating:

```text
timvw-rust: server: search_pets (POST /pets/search) is not served: its request body is 'application/x-www-form-urlencoded'
```

Left out for now: `application/x-www-form-urlencoded` bodies, a request body that declares media types of more than
one kind (JSON and multipart, say), a multipart field that is a union with a file variant, a query parameter in
deepObject style or of an object type, and a path parameter that is not a whole path segment
(`/files/{name}.json`). Webhooks are the client's: the server does not send them. `modelsCrate`, authentication and
validation are the next slices of the design.

## Cargo features

With both targets, the crate has the features `client` (the `apis` module, `timvw-rust-runtime`) and `server` (the
`server` module, axum; `futures-util` when a response is streamed, axum's `multipart` when a body is a multipart
form), both default. A server that does not need the client:

```toml
my-api = { version = "1", default-features = false, features = ["server"] }
```

`native-tls`, `rustls`, `retry` and `tracing` configure the client and do nothing without it. The `headers` module
compiles with either target; the models with neither.

With [`apiFeatures`](api-features.md) as well, a group's client functions compile with `<group>` and `client`, its
server operations (trait methods, request and response types, routes) with `<group>` and `server`; `<group>-types`
and `types` stay models only. A trait keeps one method per operation of the enabled groups, so an implementation
gates its methods with the same features. With a single target there are no target features, and the gates are those
of `apiFeatures` alone.

## One crate or two

`["client", "server"]` in one crate suits a small API, and tests where the client calls the server
([src/test/rust/server.rs](../../src/test/rust/server.rs) does). For a large spec, a server crate of its own with the
models of an existing client crate (`modelsCrate`) is planned.
