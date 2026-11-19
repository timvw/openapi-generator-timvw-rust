# Guide: the server (`targets`)

`timvw-rust` renders a client by default. The `targets` option renders the models alone, a server on
[axum](https://docs.rs/axum), or a client and a server in one crate. Both sides use the same models, so a server
generated from a spec writes the JSON its client reads. The design is in
[targets and a server](../design/targets-and-server.md); this guide covers what is implemented (its slices 1 to 3).
[openai-mock-server](https://github.com/genai-rs/openai-mock-server) is a server generated this way: a mock of the
OpenAI API, generated from OpenAI's spec, whose router serves every operation of that spec.

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
| The models (every target) | `stringIdSchemas`, `stringIdProperties`, `stringProperties`, `optionalProperties`, `fileProperties`, `deepObjectQueryParameters`, `resetQueryParameterStyle`, `dateLibrary`, `bonBuilders`, `titleNaming`, `deprecatedAttributes`, `validateMethods`, `apiFeatures`, `apiFeaturesFile`, `pruneAllHelpers`, `unionPlanFile`, `preprocessedSpecFile`, the crate options |
| The client | `runtimeVersion`, `runtimePath`, `reqwestDefaultFeatures`, `responseFunctions`, `supportMultipleResponses`, `useSerdePathToError`, `contentTypeMethods`, `typedEventStreams`, `typedEnumParameters`, `multipartHelper`, `userAgentFromPackage`, `apiLintAllows`, `webhookModuleReexport` |
| The server | `serverFramework` (only `axum`), `serverDefaultMethods`, `serverValidation`, `modelsCrate`, `modelsCrateVersion`, `modelsCratePath` |

`validateMethods` generates the models' `Validate`, which a server's validation calls, so it belongs to the
models. `webhookModule` names the group (and Cargo feature) of the webhook payload models, so it belongs to the models
too; `webhookModuleReexport` places the client's webhook functions. The server does not send webhooks yet.
`deepObjectQueryParameters` and `resetQueryParameterStyle` decide how query parameters go on the wire, so a server
takes the client's values to read what that client sends.

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

### Query parameters

A query parameter is read as the client writes it: a scalar or an enum from its text, a list from one pair per item
(or the items of one pair joined by the separator its `style` names), and a union of scalars (OpenAI's `order`, `asc`
or `desc`) from its text as the union's JSON string. A deepObject parameter is read into its type from the pairs the
client's `parse_deep_object` writes: `metadata[key]=value` for a map, `created[gt]=10` for the fields of an object,
`created[tags][0]=a` for the items of an array, read as the type at their place asks (a number, a boolean, `null` for
`None`). Which object parameters are deepObject, and the style of the others, is what
[`deepObjectQueryParameters`](../options.md#deepobjectqueryparameters) and
[`resetQueryParameterStyle`](../options.md#resetqueryparameterstyle) say: give a server the client's values.

<!-- not compiled: generated code (src/server.rs of a crate generated from src/test/resources/server_wire.yaml) -->
```rust
pub struct ListNotesRequest {
    /// `metadata`, a query parameter: Exact matches, `metadata[key]=value`.
    pub metadata: Option<std::collections::HashMap<String, String>>,
    /// `created`, a query parameter
    pub created: Option<models::Range>,
    /// `order`, a query parameter
    pub order: Option<models::ListNotesOrderParameter>,
    // ...
}
```

### Paths with a fixed query

A path whose template ends in a query (OpenAI's `/responses/{response_id}?beta=true`, next to
`/responses/{response_id}`) is routed on its path. The operations that share a path and a method are told apart by
their fixed query: a request with `beta=true` goes to the beta operation, any other to the one without a fixed query.
A request that has none of the fixed queries of a path that only has operations with one is a `404`. The other query
parameters are read as usual; the fixed ones are not fields of the request.

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
| `application/x-www-form-urlencoded` | `<Operation>Form`, a field per property | as anything else |
| a string that is not binary, whatever its media type (`application/sdp`) | `String` | as its media type |
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
client's [typed event streams](../options.md#typedeventstreams) decode. When the event schema has a discriminator, an
`event:` line before it names the event by the discriminator's value (`event: response.created`), as OpenAI writes a
Responses stream; the client reads the `data:` line alone, as before. Without one (chat completions), events have no
name. `EventStream::new(stream)` takes a
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

**A body of several media types.** A request body that declares media types of more than one kind (a multipart form
or JSON, say, or OpenAI's `create_realtime_call`: a multipart form or `application/sdp`) is an enum,
`<Operation>Body`, with a variant per kind, named as the client names its function for that media type with
[`contentTypeMethods`](../options.md#contenttypemethods): `Json`, `Multipart`, `Form`, `Text`, `Bytes`, or the
subtype (`Sdp`). The server picks the variant by the request's `Content-Type` (JSON under any JSON media type); a
`Content-Type` the operation does not take, or none with a body, is a `415`:

<!-- not compiled: generated code (src/server.rs of a crate generated from src/test/resources/server.yaml) -->
```rust
pub enum ImportPetBody {
    /// `multipart/form-data`
    Multipart(ImportPetForm),
    /// `application/json`
    Json(models::NewPet),
    /// `application/sdp`
    Sdp(String),
}
```

**Multipart and urlencoded forms.** `<Operation>Form` has a field per property of the body's schema: a `server::FilePart` (its
`bytes`, `file_name` and `content_type`) per file, `Vec<FilePart>` for a list of files, the typed value of a text part
(`String`, numbers, enums, ...), a `Vec` for a list (one part per item, or one part holding a JSON array: the client
sends the latter without [`multipartHelper`](../options.md#multiparthelper)), and the model of a JSON part. An
urlencoded form reads its pairs the same way, with no files; a list whose `encoding` has `explode: false` is one pair
holding the items joined by `,` (`|` for `pipeDelimited`, a space for `spaceDelimited`), as the client sends it. A part
that does not decode is a `400` whose `location` is `body` and `name` the field.

A field whose union has a file variant (OpenAI's `create_video_extend`: a video file or a reference to one;
`create_skill`: the files of a directory or one zip file) is an enum of its own, `<Operation><Field>`, with the union's
variants: a file is a `FilePart`, an array of files a `Vec<FilePart>`, and every other variant its own type. A part with
a file name is a file (the client always gives one); several, or parts named as the client names the parts of an array
of files (`files[]`, when the spec says so), are the array. Another part is the first other variant its JSON, or its
text, decodes into:

<!-- not compiled: generated code (src/server.rs of a crate generated from src/test/resources/server_wire.yaml) -->
```rust
pub enum AttachSource {
    Reference(models::Reference),
    Text(super::FilePart),
}
pub enum AttachFiles {
    ArrayOfStrings(Vec<super::FilePart>),
    Text(super::FilePart),
}
```

### Requests that do not decode, and errors

A path or query parameter that is missing or does not parse as its type, or a body that does not decode (JSON that is
not of its model, text that is not UTF-8, a form field that is missing or does not parse, a media type the operation
does not take), is answered without calling the implementation, by `ErrorHandler::invalid_request`. An implementation's `Err` is
answered by `ErrorHandler::server_error`. Both have a default:

| | Status | Body |
| --- | --- | --- |
| `invalid_request` | the error's `status()`: `400`, `415` for a media type the operation does not take, `413` for a body over the limit | ``{"error": {"message": "query parameter `limit`: invalid value 'x'", "location": "query", "name": "limit"}}`` |
| `server_error` | `500`; `501` for `ServerError::not_implemented` | `{"error": {"message": "internal server error"}}`, the error not shown to the caller; `{"error": {"message": "list_pets is not implemented"}}` |

Implement either method to answer otherwise; `RequestError` has the `status`, the `location` (`path`, `query` or
`body`), the parameter's `name` and the `message`, and `ServerError::not_implemented_operation` says whether an error
is a `not_implemented` one.

With [`serverDefaultMethods`](../options.md#serverdefaultmethods), every trait method has a default returning
`ServerError::not_implemented`, so an implementation of a few methods serves those, and answers every other operation
with a `501`:

```rust,ignore
#[derive(Clone)]
struct Mock;

impl server::ErrorHandler for Mock {}
impl server::ChatApi for Mock {
    async fn create_chat_completion(
        &self,
        request: server::chat_api::CreateChatCompletionRequest,
    ) -> Result<server::chat_api::CreateChatCompletionResponse, server::ServerError> {
        todo!()
    }
}
impl server::FilesApi for Mock {} // every operation: 501
```

### Validation (`serverValidation`)

With [`serverValidation`](../options.md#servervalidation), a request that decodes is checked against the spec's
validation keywords before the implementation is called: `minimum`, `maxLength`, `pattern`, `maxItems` and the others
[validation](validation.md) lists, of

- its path and query parameters, at `path.<name>` and `query.<name>`;
- the fields of a form body (multipart or urlencoded), at `body.<field>`;
- the models of a JSON body, with their own `validate()`, at `body` (`body.messages[0].content`); a body of several
  media types checks the one it was sent as.

A request that breaks one or more is answered by `ErrorHandler::invalid_request` with `RequestError::invalid`: a
`400`, at the location and parameter of the first error, that lists every error. The default body adds them as
`errors`:

```json
{
  "error": {
    "message": "query.limit: 101 breaks maximum 100; query.tag[0]: length 1 breaks minLength 2",
    "location": "query",
    "name": "limit",
    "errors": [
      { "path": "query.limit", "rule": "maximum 100", "message": "query.limit: 101 breaks maximum 100" },
      { "path": "query.tag[0]", "rule": "minLength 2", "message": "query.tag[0]: length 1 breaks minLength 2" }
    ]
  }
}
```

An `invalid_request` of your own reads them from `RequestError::validation_errors()`. The request structs, form
structs and body enums that have something to check implement the models' `Validate`, so an implementation can call
`request.validate()` itself; an operation with nothing to check is not checked. Header and cookie parameters are not
decoded, so not checked either.

The option needs `validateMethods` (the default). A `pattern` of a parameter or form field is a regex static of
`src/server.rs`, so the crate depends on `regex` (behind the `server` feature with both targets). A server with
[`modelsCrate`](#one-crate-or-two) checks its bodies with the models crate's `Validate`: generate that crate with
`validateMethods` too.

### What the server does not serve yet

An operation the server cannot serve is left out of the trait and the router, with a comment in `src/server.rs`, a
line in the crate's README and a warning when generating:

```text
timvw-rust: server: get_pet_json (GET /pets/{petId}.json) is not served: the path segment '{petId}.json' is not one path parameter
```

Left out for now: a request body that is both a multipart and an urlencoded form, a query parameter whose type has no
text form (an object that is not deepObject; [`deepObjectQueryParameters`](../options.md#deepobjectqueryparameters)
makes it one), and a path parameter that is not a whole path segment (`/files/{name}.json`). Two operations with the
same path, method and fixed query cannot be told apart: the second is left out. Every operation of the OpenAI spec is
served. Webhooks are the client's: the server does not send them. Authentication is the next slice of the design.

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
([src/test/rust/server.rs](../../src/test/rust/server.rs) does). For a large spec, a server crate of its own uses the
models of the existing client crate with [`modelsCrate`](../options.md#modelscrate): generate it from the same spec
with the same model options (drop the client's options, keep `apiFeaturesFile` and `webhookModule`), so the client
and the server share their types and Cargo features.

```json
{
  "packageName": "my-api-server",
  "targets": ["server"],
  "modelsCrate": "my-api-client",
  "modelsCrateVersion": "1",
  "modelsCratePath": "..",
  "apiFeaturesFile": "scripts/api-features.yaml"
}
```

A server crate can also generate its own models from the same spec, without `modelsCrate`: the spec is then what the
client and the server share. [openai-mock-server](https://github.com/genai-rs/openai-mock-server) does this, with the
server and its models as modules of one crate, in a repository of its own.
