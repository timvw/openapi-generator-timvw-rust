# Guide: the server (`targets`)

`timvw-rust` renders a client by default. The `targets` option renders the models alone, a server on
[axum](https://docs.rs/axum), or a client and a server in one crate. Both sides use the same models, so a server
generated from a spec writes the JSON its client reads. The design is in
[targets and a server](../design/targets-and-server.md); this guide covers what is implemented (its slice 1).

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
  JSON body as its model (`Option` when the body is optional; `null` is no body), and the request's `HeaderMap`.
  Header and cookie parameters, and credentials, are read from the headers; authentication is axum middleware's job
  for now.
- **The response enum** has a variant per status the spec declares: `Status200(body)` when the body is all it holds,
  `Status204` without a body, a `status` field for a range (`Status4XX`) and for `Default`, and a `headers` field
  where the response declares headers. `IntoResponse` writes the status, the headers and the JSON body.
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

### Requests that do not decode, and errors

A path or query parameter that is missing or does not parse as its type, or a body that is not the JSON of its model,
is answered without calling the implementation, by `ErrorHandler::invalid_request`. An implementation's `Err` is
answered by `ErrorHandler::server_error`. Both have a default:

| | Status | Body |
| --- | --- | --- |
| `invalid_request` | `400` | ``{"error": {"message": "query parameter `limit`: invalid value 'x'", "location": "query", "name": "limit"}}`` |
| `server_error` | `500` | `{"error": {"message": "internal server error"}}`; the error is not shown to the caller |

Implement either method to answer otherwise; `RequestError` has the `location` (`path`, `query` or `body`), the
parameter's `name` and the `message`.

### What the server does not serve yet

The server serves operations whose request body (if any) and responses are JSON, or have no content. An operation
with another media type is left out of the trait and the router, with a comment in `src/server.rs`, a line in the
crate's README and a warning when generating:

```text
timvw-rust: server: upload_photo (POST /pets/{petId}/photo) is not served: its request body is 'multipart/form-data'
```

Left out for now: multipart and form bodies, files and text (request or response), `text/event-stream` responses, a
query parameter in deepObject style or of an object type, and a path parameter that is not a whole path segment
(`/files/{name}.json`). Webhooks are the client's: the server does not send them. Server-sent events, multipart and
files are the next slice of the design; `modelsCrate`, authentication and validation the ones after it.

## Cargo features

With both targets, the crate has the features `client` (the `apis` module, `timvw-rust-runtime`) and `server` (the
`server` module, axum), both default. A server that does not need the client:

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
