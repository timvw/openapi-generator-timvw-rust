# Design: targets, and a server

Status: slices 1 and 2 are implemented (`targets`, the Cargo features `client` and `server`, and an axum server for
JSON, text, binary and multipart bodies and server-sent events; see the [server guide](../guides/server.md)).
Slice 3 is implemented too, as [openai-mock-server](https://github.com/genai-rs/openai-mock-server). Of slice 4,
validation is implemented (`serverValidation`); authentication and webhooks sent by the server are proposed.

`timvw-rust` renders an HTTP client and its models. This page proposes a `targets` option that picks what the
generator renders: the models, a client, a server, or more than one of them in one crate. It also describes the
first server, on [axum](https://docs.rs/axum), taking ideas from the stock `rust-axum` generator.

## Why

- **One set of models on both sides.** Unions, nullability, `Unknown` variants, `stringProperties`,
  `optionalProperties` and `fileProperties` are what make a client decode real responses. A server generated with
  other models (`rust-axum`'s) writes JSON that our clients read differently. With one generator, both sides use the
  same types.
- **Uses in this ecosystem:**
  - a mock OpenAI server for openai-ergonomic's tests (the agent loop's tests script raw responses by hand today);
  - a fake Langfuse for langfuse-ergonomic's tests;
  - OpenAI-compatible proxies and gateways.

## How the stock generators do it

Stock openapi-generator picks the role by generator name; there is no option for it.

| Generator | Renders | HTTP stack |
| --- | --- | --- |
| `rust` | a client | `library`: `reqwest`, `reqwest-trait`, `hyper` |
| `rust-server` | a client and a server in one crate, behind the Cargo features `client` and `server` | hyper 0.14 with the `swagger` crate |
| `rust-axum` | a server | axum 0.8; a `server` feature (default) holds the HTTP dependencies |

Each has its own models, so the same spec gives different types on each side.

## The `targets` option

```json
{ "targets": ["client", "server"] }
```

- A list from `models`, `client` and `server`. `client` and `server` imply `models`.
- The default is `["client"]`: what the generator renders today. Existing configurations do not change.
- There is no `all` value. A target added later (for example a mock) would otherwise change what an existing
  configuration renders.

| `targets` | The crate holds | Cargo features it adds |
| --- | --- | --- |
| `["models"]` | the models only, no HTTP dependencies | none |
| `["client"]` (default) | the models and the client | none: as today |
| `["server"]` | the models and the server | none |
| `["client", "server"]` | the models, the client and the server | `client` and `server`, both default |

### With `apiFeatures`

`apiFeatures` gates by API group: a `<group>` feature and a `<group>-types` twin with only the models. Targets add a
second dimension only when a crate has more than one target:

- an operation's client items compile with `<group>` and `client`;
- its server items (trait methods, routes) compile with `<group>` and `server`;
- `<group>-types` and `types` stay models only, without either target feature.

With a single target there is no target feature, and the gates are those of today.

### Options per target

Every option belongs to the models (both sides), to the client or to the server. An option of a target that is not
in `targets` fails the generation with an error naming the option and its target, as an unknown
`stringProperties` entry does since 0.30.

| Applies to | Options |
| --- | --- |
| The models (every target) | `stringIdSchemas`, `stringIdProperties`, `stringProperties`, `optionalProperties`, `fileProperties`, `deepObjectQueryParameters`, `resetQueryParameterStyle`, `dateLibrary`, `bonBuilders`, `titleNaming`, `deprecatedAttributes`, `apiFeatures`, `apiFeaturesFile`, the crate options |
| The client | `runtimeVersion`, `runtimePath`, `reqwestDefaultFeatures`, `responseFunctions`, `supportMultipleResponses`, `useSerdePathToError`, `contentTypeMethods`, `typedEventStreams`, `typedEnumParameters`, `multipartHelper`, `userAgentFromPackage` |
| The server | `serverFramework` (only `axum`), `serverValidation` |
| To decide | `webhookModule` (the client verifies webhooks; a server could also send them), `validateMethods` (the models' `Validate`, which the server's validation would call) |

### `modelsCrate`

```json
{ "targets": ["server"], "modelsCrate": "openai-client-base" }
```

The models come from an existing crate instead of being rendered: the generated code refers to
`openai_client_base::models`, and `Cargo.toml` depends on it with `default-features = false` and the `-types`
features of the groups it renders. A mock server then uses the same Rust types as the client it is tested with,
not a copy of them. The crate must have been generated from the same spec with the same model options; the
generator cannot check that, so the guide must say so.

genai-rs does not use it: its client and its mock server each generate their own models from the spec, in their own
repositories, and share the spec only ([what was built](openai-mock-server.md#what-was-built)). The option is for
an API where one team wants one models crate.

## The server, on axum

### Shape (from `rust-axum`)

For each API group (tag, or `apiFeatures` group):

```rust
pub trait Responses: Send + Sync {
    /// POST /responses
    fn create_response(
        &self,
        request: CreateResponseRequest,
    ) -> impl Future<Output = Result<CreateResponseResponse, ServerError>> + Send;
}

pub struct CreateResponseRequest {
    pub body: models::CreateResponse,
    pub headers: http::HeaderMap,
}

pub enum CreateResponseResponse {
    /// 200: OK
    Status200(models::Response),
    /// 200, `text/event-stream`
    Status200Stream(EventStream<models::ResponseStreamEvent>),
}
```

and one builder for the whole API:

```rust
let app: axum::Router = server::router(MyImpl::new());
```

- **One trait per group**, with an `async fn` per operation. Native async functions in traits (Rust 1.75+), not
  `async_trait`. The returned future must be `Send` for axum, so the trait spells `impl Future + Send`.
- **One request struct per operation**: path and query parameters as typed fields, the body as its model (an
  `Upload` per file part for multipart), and the raw `HeaderMap`. `rust-axum` passes `method`, `host` and `cookies`
  to every method; an implementation that needs them can use an axum extractor or middleware instead.
- **One response enum per operation**, a variant per declared status. Where the spec declares headers, the variant
  carries the `<Operation>ResponseHeaders` / `<Operation>ErrorHeaders` struct the client already reads, now also
  written.
- **The router** extracts and decodes the request, calls the trait, and encodes the enum: status, headers, body.
  A request that does not decode is a `400` with a JSON error body. Its shape is set by an `ErrorHandler` trait
  with a default, as in `rust-axum`.
- **Server-sent events**: a stream of the generated event type is encoded as `text/event-stream`, the same events
  `typedEventStreams` decodes on the client. `rust-axum` takes a stream of untyped `axum::response::sse::Event`s;
  ours is typed, so the mock server and the client cannot disagree on an event.

### What we keep, change or drop from `rust-axum`

| `rust-axum` | Here |
| --- | --- |
| `apis.mustache`: trait per tag, `async_trait`, `ErrorHandler<E>` super-trait | keep the shape; native async functions; groups follow `apiFeatures` |
| `response.mustache`: enum per operation, headers as fields | keep; headers are our typed header structs |
| `server-mod.mustache`, `server-route.mustache`: `new(api_impl) -> Router` | keep; one `router` for the crate, routes gated per group |
| `server-operation-validate.mustache`: `validator`, on `spawn_blocking` by default | the models' own `Validate` (`validateMethods`), inline, behind `serverValidation` |
| `models.mustache`, `types.mustache` | dropped: our models, or `modelsCrate` |
| `authorization.mustache`: `Claims` per scheme | later: slice 1 passes the headers and leaves authentication to axum middleware |
| `method`, `host`, `cookies` on every method | dropped (extractors) |
| `frunk` conversion feature, analytics events | dropped |

### Slices

1. **`targets`, and a server for JSON operations:**
   - the option and its validation;
   - Cargo features for more than one target;
   - traits, request structs, response enums with typed headers, the router;
   - `400` for requests that do not decode.

   Tested end to end: a server generated from the test specs answers the client generated from the same spec.
2. **Streaming and files** (implemented):
   - server-sent events, typed: an `EventStream<Event>` variant, suffixed `Stream` next to a JSON one;
   - multipart into a form struct per operation, a `FilePart` per file. Not `Upload`: that is what a client sends,
     and it has no way to read its bytes back; a server receives the bytes, the file name and the media type;
   - text (`String`) and binary (`Binary`: bytes or a stream, media type settable) responses, and text and binary
     (`Bytes`) request bodies.

   Then (implemented): urlencoded form bodies into the same form struct; a request body that declares more than one
   kind of media type (OpenAI's JSON-or-multipart operations, and `create_realtime_call`'s multipart-or-SDP) as an
   enum chosen by `Content-Type`, a variant per kind named as the client's `contentTypeMethods` function, and `415`
   for another media type; an `event:` line naming each event by its schema's discriminator, as OpenAI writes a
   Responses stream.

   Then (implemented): a multipart field whose union has a file variant (OpenAI's videos and skills: a file or a JSON
   reference) as an enum of its own, a `FilePart` for a part with a file name; deepObject query parameters read into
   their type from the `name[key]=value` pairs the client writes; a union query parameter from its text; and paths
   with a fixed query (`/responses/{id}?beta=true`) routed on their path, one handler per path and method calling the
   operation whose fixed query the request has. `deepObjectQueryParameters` and `resetQueryParameterStyle` moved to
   the model options: they decide what goes on the wire, which client and server must agree on.
3. **`modelsCrate`, and the first real use:** a mock OpenAI server for openai-ergonomic's tests, generated from
   OpenAI's spec. Planned in [a mock OpenAI server](openai-mock-server.md), which compares LiteLLM and Bifrost, and
   built as [openai-mock-server](https://github.com/genai-rs/openai-mock-server): one crate in its own repository,
   with the generated server and its own models as modules. It does not use `modelsCrate`; the spec is what it
   shares with the client.

   Implemented in the generator: `modelsCrate` (with `modelsCrateVersion` and `modelsCratePath`), and
   `serverDefaultMethods`, a default per trait method that answers `501`, so a mock implements only what it scripts.
   `webhookModule` moved to the model options: it names the Cargo feature of the webhook payloads, which the server
   must name as its models crate does. On the OpenAI spec, all 388 operations are served.
4. **Validation, authentication, webhooks:**
   - validation (implemented): `serverValidation` checks a request that decodes with the models' `Validate`, and
     the parameters' and form fields' own keywords with the same checks, before the implementation is called; a
     request that breaks one is a `400` listing every error;
   - later: authentication schemes, and webhooks sent by the server.

## Open questions

- **Names:** where the server's runtime pieces go (the decoding error type, the event-stream encoder, `Binary`,
  `FilePart`). Decided for now: they are generated into `src/server.rs`, as slice 1's `RequestError` and
  `ServerError` are, so a server-only crate depends on axum, `http` and (when it streams) `futures-util`, not on
  `timvw-rust-runtime`. They can move to a runtime crate behind a `server` feature once the runtime and the generator
  move to genai-rs; the generated names would stay re-exported from `server`.
- **`Send`:** an `impl Future + Send` trait cannot be implemented with non-`Send` state. axum needs `Send`, so the
  server requires it. WebAssembly is client-only.
- **One crate or two:** `["client", "server"]` in one crate is convenient for small APIs. For a large spec, a
  separate server crate is the better fit, with `modelsCrate` or with its own models. genai-rs chose its own models,
  in a separate repository: the spec is the contract, not a crate.
