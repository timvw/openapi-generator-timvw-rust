# Guide: migrating

- [From 0.27 to 0.28](#from-027-to-028): multipart unions with a file variant hold `Upload`s; runtime 0.3.1 and its `TimeoutLayer`.
- [From 0.26 to 0.27](#from-026-to-027): file parameters are `timvw_rust_runtime::Upload`s; runtime 0.3; WebAssembly.
- [From 0.25 to 0.26](#from-025-to-026): the credentials of `Configuration` are `SecretString`s; per-call headers and query parameters; runtime 0.2 retries.
- [From 0.24 to 0.25](#from-024-to-025): the client sends through a tower service from `timvw-rust-runtime`.
- [From 0.23 to 0.24](#from-023-to-024): union variant names under a model name prefix or suffix.
- [From 0.22 to 0.23](#from-022-to-023): one rare type change.
- [From 0.21 to 0.22](#from-021-to-022): every breaking change, with the code before and after.
- [From 0.20 to 0.21](#from-020-to-021): every breaking change, with the code before and after.
- [From stock `-g rust`](#from-stock--g-rust): what is different when you switch generators.

The "after" examples use [`docs_examples.yaml`](../../src/test/resources/docs_examples.yaml), generated as
`my_client`, and are compiled by the tests. The "before" examples are code of the previous release and are not.

## From 0.27 to 0.28

### Multipart unions with a file variant hold `Upload`s

With `httpStack=tower` and `multipartHelper`, the file variant of a multipart union (a `oneOf`/`anyOf` with a
`format: binary` branch, such as openai-client-base's `CreateVideoMultipartBodyInputReference`,
`CreateVideoEditMultipartBodyVideo` or `CreateSkillBodyFiles`) holds a `timvw_rust_runtime::Upload` instead of a
`std::path::PathBuf`, and an array of files a `Vec<Upload>`, so it takes bytes and streams as well as paths, on
WebAssembly too. Code that builds the variant from a path changes:

<!-- not compiled: code of 0.27 -->
```rust
// 0.27
.video(models::CreateVideoEditMultipartBodyVideo::Text(PathBuf::from("clip.mp4")))
.files(models::CreateSkillBodyFiles::ArrayOfStrings(vec![PathBuf::from("SKILL.md")]))
```

<!-- not compiled: openai-client-base's API -->
```rust
// 0.28: the setter takes an Upload or a PathBuf, or the union
.video(PathBuf::from("clip.mp4"))
.video(Upload::bytes(mp4).file_name("clip.mp4"))
.files(models::CreateSkillBodyFiles::files([PathBuf::from("SKILL.md")]))
// or build the variant: Text(path.into()), ArrayOfStrings(paths.into_iter().map(Into::into).collect())
```

- The unions get `file(impl Into<Upload>)` and `files(impl IntoIterator<Item = impl Into<Upload>>)` constructors, and
  `From<Upload>`, `From<PathBuf>`, `From<Vec<Upload>>` and `From<Vec<PathBuf>>`. With `bonBuilders`, the setter of such
  a parameter is `#[builder(into)]`: it takes anything that converts into the union. A call that passed
  `value.into()` to it no longer infers the type; pass `value` itself.
- The file variants are `#[serde(skip)]`: serializing a union (or a model holding one, such as `CreateSkillBody`) that
  holds a file is an error, where it wrote the path as a string, and JSON never decodes to a file variant. The unions
  keep `Clone`, `Debug`, `PartialEq`, `Serialize` and `Deserialize`; two uploads are equal when they send the same
  thing (runtime 0.3.1). Their `Display` prints the file name, where it printed the path as JSON.
- With `apiFeatures`, the `-types` features of the groups whose models hold such a union depend on the runtime
  (`video-types = ["dep:timvw-rust-runtime"]`).
- `httpStack=reqwest-middleware` renders what 0.27 rendered: its unions keep `PathBuf`.

### Runtime 0.3.1

The generated `Cargo.toml` asks for `timvw-rust-runtime` `^0.3.1` (`PartialEq` for `Upload`). The runtime adds
`timeout::TimeoutLayer`, a request timeout that works natively and on WebAssembly (where `fetch` has none and tower's
`TimeoutLayer` does not work), and `RetryPolicy::retry_timeouts`. Clients still have no timeout by default; see
[timeouts](../../runtime/README.md#timeouts) for where to put one. A client that used `tower::timeout::TimeoutLayer`
keeps working natively.

To upgrade a client repository, bump the image tag to `0.28.0-og7.27.0`, regenerate, and run
`cargo clippy --all-targets -- -D warnings` and `cargo test`.

## From 0.26 to 0.27

### File parameters are `Upload`s

With `httpStack=tower` (the default), a file parameter (`format: binary`, a multipart part or the whole body) is a
`timvw_rust_runtime::Upload` instead of a `std::path::PathBuf`, and so is each item of an array of files. With
`bonBuilders` (the default) its setter takes `impl Into<Upload>`, so a call that passes a `PathBuf` or a `&Path`
compiles as before, and may now pass bytes:

```rust
items_api::upload_item_image()
    .configuration(&configuration)
    .id("1")
    .image(PathBuf::from("lamp.png"))
    .call()
    .await?;
```

What changes at run time:

- A file is streamed from disk with a `Content-Length`; `multipartHelper` no longer reads it into memory with
  `std::fs::read`. A missing file fails before the request is sent, with `Error::Http` whose `is_io()` is true (it
  was `Error::Io` before).
- A multipart part of a file has a `Content-Type` guessed from its extension (`image/png`); before, it had none.
- A request with a file is retried like any other: the file is opened again for each attempt.

With `bonBuilders=false` a positional argument is an `Upload`: pass `path.into()` or `Upload::path(path)`. A multipart
union with a file variant (`multipartHelper`) keeps `PathBuf` in its model and streams it the same way.
`httpStack=reqwest-middleware` renders what 0.26 rendered. The crate depends on the runtime's `fs` feature instead of
`tokio` and `tokio-util`, and needs `timvw-rust-runtime` 0.3, which has `Upload`: the generated `Cargo.toml` asks for
`^0.3`. A hand-kept `Cargo.toml` follows it ([dependencies](getting-started.md#running-the-generator)).

### WebAssembly

A tower client (and `timvw-rust-runtime` 0.3) builds for `wasm32-unknown-unknown`: nothing to change to keep building
natively. For a WebAssembly build, see [the HTTP stack guide](http-stack.md#webassembly). Code of your own that names
the runtime's bounds may need care:

- `Client::from_service`, `Client::layer`, `Body::wrap`, `Body::wrap_stream`, `Upload::stream`, `Upload::from_fn`,
  `Events::from_bytes` and `EventStream::from_bytes` bound their arguments by `timvw_rust_runtime::MaybeSend` and
  `MaybeSync` instead of `Send` and `Sync`. Natively these are the same (`MaybeSend: Send`), so callers compile as
  before; a generic function of yours that forwards to them can keep `Send` (natively) or use `MaybeSend` to build for
  both.
- `HttpService` is tower's `BoxCloneSyncService` natively, as before, and a `LocalBoxCloneService` on WebAssembly.
- On WebAssembly, `Client`, `Response` and their futures are not `Send`, and `Upload::file`, `Upload::reader`,
  `Response::save_to` and `Response::into_async_read` do not exist.

### Smaller changes

- A typed event stream has `idle_timeout(duration)` (`typedEventStreams`, tower): no timeout unless you set one. The
  crate depends on the runtime's `time` feature for it.
- With `apiFeatures`, an API module whose functions are gated one by one allows unused imports: the
  `#![cfg_attr(not(feature = "full"), allow(unused_imports))]` a hand-written `lib.rs` added for it can go.
- A crate with `uuid`s adds `uuid`'s `js` feature for `wasm32-unknown-unknown` (tower).
- Retries (runtime 0.3) read `Retry-After` as an HTTP date too, and wait until the deadline the server gave, counted
  from when the response arrived. `RetryPolicy`'s API does not change; `server_directed_429(true)` is new and off.
- To send the same API to another provider, `timvw_rust_runtime::ProviderLayer` replaces a layer of your own that
  rewrote URLs ([HTTP stack guide](http-stack.md#tower-the-runtime-crate)).

To upgrade a client repository, bump the image tag to `0.27.0-og7.27.0`, regenerate, and run
`cargo clippy --all-targets -- -D warnings` and `cargo test`.

## From 0.25 to 0.26

### Credentials

With `httpStack=tower` (the default), the secrets of `Configuration` are `secrecy::SecretString`s instead of
`String`s: `bearer_access_token` and `oauth_access_token` are `Option<SecretString>`, `BasicAuth` is
`(String, Option<SecretString>)` and `ApiKey::key` is a `SecretString`. The username and `ApiKey::prefix` stay
`String`s. `configuration::SecretString` re-exports the type; it is made from a `String` or a `&str` with `.into()` or
`SecretString::from`, and read back with `ExposeSecret::expose_secret` (`configuration::ExposeSecret`). With
`httpStack=reqwest-middleware` nothing changes.

Before:

<!-- not compiled: 0.25 -->
```rust
let token = "sk-123".to_owned();
configuration.bearer_access_token = Some(token);
configuration.oauth_access_token = Some("oauth-token".to_owned());
configuration.basic_auth = Some(("user".to_owned(), Some("password".to_owned())));
configuration.api_key = Some(ApiKey {
    prefix: Some("Token".to_owned()),
    key: "key".to_owned(),
});
```

After:

```rust
let token = "sk-123".to_owned();
configuration.bearer_access_token = Some(token.into());
configuration.oauth_access_token = Some(SecretString::from("oauth-token"));
configuration.basic_auth = Some(("user".to_owned(), Some("password".into())));
configuration.api_key = Some(ApiKey {
    prefix: Some("Token".to_owned()),
    key: "key".into(),
});
```

Code that read a credential back reads `token.expose_secret()` where it read `token`. The generated client needs
`timvw-rust-runtime` 0.2, which has `SecretString` and `RequestBuilder::sensitive_header`; the generated
`Cargo.toml` asks for `^0.2`.

### Per-call request options

0.26 adds an optional last argument to every operation with `httpStack=tower` and `bonBuilders` (the defaults):
`options: Option<&timvw_rust_runtime::RequestOptions>`, `.options(&options)` on the builder, for extra headers and
query parameters of one call ([HTTP stack guide](http-stack.md#headers-and-query-parameters-for-one-call)). A call
that does not set it sends what it sent before, so builder calls compile and behave as with 0.25. Two exceptions:

- An operation with a parameter named `options` keeps it; its request options are `.request_options(..)`.
- The generated code needs `timvw-rust-runtime` 0.2 (`RequestOptions`, `SecretString`): the generated `Cargo.toml`
  asks for `^0.2`.

`bonBuilders=false` and `httpStack=reqwest-middleware` render what 0.25 rendered.

### Retries

Runtime 0.2 changes how `Configuration::new()` retries (no code change needed):

- Each backoff delay is jittered (between half and all of it); `.jitter(false)` restores exact delays.
- `retry-after-ms` and fractional `Retry-After` seconds are read. A server's wait is no longer capped by `max_delay`
  (8 s): it is waited for in full up to `max_retry_after` (60 s), and a response that asks for longer is returned at
  once instead of retried.
- `RetryLayer::permanent_429(retry::openai_insufficient_quota)` (opt-in) stops retrying a `429` that will not pass by
  waiting; a client that wants it builds from `Client::from_service(ReqwestService::default())`
  ([runtime guide](../../runtime/README.md#permanent-429s-an-exhausted-quota)).

`apiFeatures` and `apiFeaturesFile` are new and off by default: off, the output is what 0.25 rendered.

To upgrade a client repository, bump the image tag to `0.26.0-og7.27.0`, regenerate, and run
`cargo clippy --all-targets -- -D warnings` and `cargo test`.

## From 0.24 to 0.25

0.25 adds `httpStack`. Its default, `tower`, sends every request through `timvw_rust_runtime::Client`, a tower service
from the new [`timvw-rust-runtime`](../../runtime/README.md) crate (reqwest by default), instead of
`reqwest_middleware::ClientWithMiddleware`. The generated functions, models and `ResponseContent` do not change. To
keep 0.24's output, set `httpStack=reqwest-middleware`; nothing else changes then.

With the default `tower`:

- `Cargo.toml` depends on `timvw-rust-runtime` instead of `reqwest` and `reqwest-middleware`. The features
  `native-tls` and `rustls` stay and are passed on to the runtime; a new default feature `retry` enables
  `timvw_rust_runtime::retry` and makes `Configuration::new()` retry `429`, `408`, `5xx` and failed connections. A
  client that added its own retry layer drops it, or builds from `Client::from_service(ReqwestService::default())`. A hand-kept `Cargo.toml` follows the generated one.
- `Configuration.client` is a `timvw_rust_runtime::Client` (`configuration::Client` re-exports it, where
  `configuration::ClientWithMiddleware` was). A `reqwest_middleware` stack becomes tower layers:
  `Client::from_reqwest(reqwest_client)` for a configured `reqwest::Client`, `.layer(..)` for each middleware. The
  [HTTP stack guide](http-stack.md#moving-from-reqwest-middleware-to-tower) maps the common ones.
- `apis::Error` has `Http(timvw_rust_runtime::Error)` where it had `Reqwest(reqwest::Error)` and
  `ReqwestMiddleware(reqwest_middleware::Error)`. A `match` on those variants matches `Http`; the reqwest error is its
  `source()`.
- `ResponseContent.status` and `.headers`, and `ApiResponse`'s, are the same `http` types as before, named through
  the runtime: `timvw_rust_runtime::StatusCode` and `timvw_rust_runtime::header::HeaderMap`. Code that names them
  through `reqwest::` compiles while it depends on reqwest itself; otherwise it names them through the runtime.
- A file response (`format: binary`) is a `timvw_rust_runtime::Response`, with `status()`, `headers()`, `bytes()` and
  `bytes_stream()`, where it was a `reqwest::Response`. With `typedEventStreams`, `sse::Events::from_response` takes
  one, and a failure reading the stream is `Error::Http`.

To upgrade a client repository, bump the image tag to `0.25.0-og7.27.0`, regenerate, and run
`cargo clippy --all-targets -- -D warnings` and `cargo test`.

## From 0.23 to 0.24

0.24 adds typed accessors for declared error-response headers (with `responseFunctions`) and fixes webhooks without
`webhookModule`, the model name options and `--schema-mappings`; the configuration does not change, and neither
openai-client-base nor langfuse-client-base changes. One change renames types:

- With `--model-name-prefix` or `--model-name-suffix`, a union variant no longer carries the prefix or suffix, and a
  string enum branch a union hoists into a model gets it once
  ([rule](../mapping/schema.md#schemaoneofvariant-names)). With prefix `Api`, `ApiFilter(models::ApiFilter)`,
  `ApiText(String)` and `ApiApiTextVariant(models::ApiApiLooseApiTextVariantEnum)` become
  `Filter(models::ApiFilter)`, `Text(String)` and `TextVariant(models::ApiLooseTextVariantEnum)`. Code that names
  such a variant or hoisted enum uses the new names; without a prefix or suffix nothing changes.

Specs that failed or lost code before now generate in full:

- Without `webhookModule`, webhooks join the path operations of their tag's module instead of overwriting it, and a
  tag that only webhooks carry gets its module declared. A webhook whose function name clashes with a path
  operation's in the same module fails generation; set `webhookModule` to keep the webhooks apart.
- `--model-name-prefix`, `--model-name-suffix` and `--model-name-mappings` no longer fail generation when a struct is
  a JSON parameter or request body.
- `--schema-mappings Name=path::Type` uses that type everywhere and writes no model for the schema. The type needs
  `Clone`, `Debug`, `PartialEq`, `Serialize` and `Deserialize`, and `Display` when it replaces a schema that is not
  an object and is used as a parameter.

To upgrade a client repository, bump the image tag to `0.24.0-og7.27.0`, regenerate, and run
`cargo clippy --all-targets -- -D warnings` and `cargo test`.

## From 0.22 to 0.23

0.23 adds typed accessors for declared success-response headers (with `responseFunctions`) and fixes schemas that did
not compile; the configuration does not change, and neither openai-client-base nor langfuse-client-base changes type.
One shape changes type:

- A union with a branch of any value or `null` next to other branches, such as
  `oneOf: [{$ref: Obj}, {anyOf: [{}, {type: 'null'}]}]`, is `serde_json::Value`
  ([rule](../mapping/schema.md#schematypenull)). It was an untagged enum of `Obj` and `serde_json::Value`, which
  decoded the same values. Code that matched on that enum reads the `serde_json::Value` instead, and can decode it
  as `Obj` with `serde_json::from_value`.

Schemas of only `null`, `oneOf: [{}, null]`, and form fields of any value did not compile before and now do. A
parameter or form field that can only be `null` fails generation with a message naming it.

To upgrade a client repository, bump the image tag to `0.23.0-og7.27.0`, regenerate, and run
`cargo clippy --all-targets -- -D warnings` and `cargo test`.

## From 0.21 to 0.22

0.22 fixes types the JSON did not decode to and parameters that were typed as text. The configuration does not
change. In short:

| Change | What breaks | What to do |
| --- | --- | --- |
| [`oneOf: [schema, null]` parameters](#a-null-branch-parameter-takes-its-schemas-type) | a call that passes text | pass the typed value |
| [nullable path parameters](#a-path-parameter-is-never-an-option) | `Some(..)` around the value | drop the `Some` |
| [nullable items and values](#nullable-items-and-map-values-are-options) | code that builds or reads such a list or map | `Some(..)`, handle `None` |
| [int64 enums](#integer-enums-are-numbers-on-the-wire) | nothing in the code: sent as `1000`, not `"1000"` | check servers that parsed the string |
| [integer enums in lists and maps](#integer-enums-are-numbers-on-the-wire) | nothing in the code: they decode now | none |
| [nested inline enums](#smaller-fixes-in-022) | code that used the field as one level deep | use the nested type |
| [required nullable headers](#smaller-fixes-in-022) | nothing in the code: `None` sends no header | none |

### A null-branch parameter takes its schema's type

A parameter whose schema is `oneOf` or `anyOf` of one schema and `type: null` (OpenAPI 3.1) was `Option<&str>`,
whatever the schema, and sent the caller's text. It now takes that schema's type and is sent as the plain reference
is ([rule](../mapping/parameter.md#parameterschemanull-branch)). In the query, a header or a cookie it is
`Option<T>`, required or not, and `None` sends nothing. In openai-client-base, `bucket_width` of
`organization_decisionsusage` becomes `Option<models::DecisionsUsageBucketWidth>`.

```yaml
- name: status
  in: query
  schema:
    oneOf:
      - $ref: '#/components/schemas/Status'
      - type: 'null'
```

Before:

<!-- not compiled: 0.21 -->
```rust
items_api::list_items().configuration(&configuration).status("published").call().await?;
```

After:

```rust
let published = items_api::list_items()
    .configuration(&configuration)
    .status(models::Status::Published)
    .call()
    .await?;
```

A `uuid` or `date-time` one takes `uuid::Uuid` or `chrono::DateTime<..>`, as a plain one does. A form field
(urlencoded or multipart) of this shape, or a plain `allOf: [$ref]` one, takes the referenced type too; a `uuid` or
`date-time` one did not compile.

### A path parameter is never an `Option`

A nullable path parameter (`type: [T, null]`, `nullable: true`, a `null` branch) was `Option<T>` and panicked on
`None`. It is `T` now: drop the `Some(..)`.

Before:

<!-- not compiled: 0.21 -->
```rust
items_api::get_item().configuration(&configuration).id(Some("42")).call().await?;
```

After:

```rust
items_api::get_item().configuration(&configuration).id("42").call().await?;
```

### Nullable items and map values are `Option`s

A list item or map value that can be null (`nullable: true`, `type: [T, 'null']`, a `null` branch, a `$ref` to a
nullable schema) is an `Option` at any depth: `Vec<Option<String>>`, `HashMap<String, Option<T>>`. Before, the
nullability was ignored and `[null]` or `{"a": null}` failed to decode. `None` serializes as `null`, and `validate()`
skips it. Free-form values (`serde_json::Value`) and the items of parameters and form fields are unchanged.

```yaml
notes:
  type: array
  items:
    type: [string, 'null']
```

Before:

<!-- not compiled: 0.21 -->
```rust
let new_item = models::NewItem::builder()
    .name("Lamp".to_owned())
    .notes(vec!["fragile".to_owned()])
    .build();
```

After:

```rust
let new_item = models::NewItem::builder()
    .name("Lamp".to_owned())
    .notes(vec![Some("fragile".to_owned()), None])
    .build();
for note in new_item.notes.iter().flatten().flatten() {
    println!("{note}");
}
```

A union's array variant of nullable items is named `ArrayOfNullable<Items>`.

### Integer enums are numbers on the wire

An integer enum of `format: int64` (a component or inline) was a string enum: it decoded only from `"1000"` and was
sent as `"1000"`. An integer inline enum in a list or map, at any depth, was one too, so `[1]` or `{"a": 2}` did not
decode. Both are now `#[repr(i64)]` enums, as an `int32` one standing alone already was. The variant names do not
change, so the code compiles as before; the JSON changes:

```yaml
Priority:
  type: integer
  format: int64
  enum: [1000, 2000]
```

Before:

<!-- not compiled: 0.21 -->
```rust
assert_eq!(serde_json::to_string(&models::Priority::Variant1000)?, r#""1000""#);
```

After:

```rust
assert_eq!(serde_json::to_string(&models::Priority::Variant1000)?, "1000");
let priority: models::Priority = serde_json::from_str("2000")?;
```

A response enum's catch-all `Unknown` is sent as `i64::MAX` instead of `"unknown"`. A server that parsed the quoted
form needs checking. An open integer enum that lists `9223372036854775807` itself fails generation, naming the schema.

### Smaller fixes in 0.22

- An inline enum two or more lists or maps deep is typed at every level:
  `HashMap<String, HashMap<String, <Enum>>>` where it was `HashMap<String, <Enum>>`, which the JSON does not decode
  to. Code that used the field takes the nested type. A list of lists or maps of such an enum with `uniqueItems` is a
  `Vec` (a `HashSet` of maps did not compile).
- `lib.rs` allows `clippy::type_complexity`, which such a nested type trips. A hand-kept `lib.rs` adds
  `#![allow(clippy::type_complexity)]` if clippy reports it.
- A required nullable header parameter left `None` is not sent. Before, it was sent with an empty value.

### Upgrading a client repository to 0.22

1. Bump the image tag in the generation script to `0.22.0-og7.27.0`.
2. Regenerate, then fix the hand-written code the compiler lists: typed values for null-branch parameters, no
   `Some(..)` on path parameters, `Option` items.
3. Run `cargo clippy --all-targets -- -D warnings` and `cargo test`.
4. Check a server that read an int64 enum as a string.

## From 0.20 to 0.21

0.21 is the release of the [mapping decisions](../decisions.md). Most changes are in the generated API, a few only in
the bytes on the wire, and a few in the generator's configuration. In short:

| Change | What breaks | What to do |
| --- | --- | --- |
| [baseline options removed](#the-baseline-options-are-gone) | a configuration that sets one | delete the lines |
| [unknown options fail](#an-unknown-option-fails-generation) | a misspelt or stale key | correct or delete it |
| [request bodies by reference](#request-bodies-are-borrowed) | every call that passes a body | `&request` |
| [uuid parameters](#uuid-parameters-are-uuiduuid) | every call that passes a uuid as `&str` | pass a `uuid::Uuid` |
| [error variant by status](#the-status-picks-the-error-variant) | code that relied on the shape match | check your matches |
| [headers on errors](#errors-have-the-headers) | building a `ResponseContent` by hand | add `headers` |
| [boxed errors](#errors-are-always-boxed) | building or moving a `ResponseContent` (langfuse) | `Box::new`, `*response` |
| [text errors](#a-text-error-holds-its-text) | reading a text error from `content` | match the variant |
| [`date-time` on the wire](#date-time-parameters-are-sent-in-rfc-3339) | nothing in the code | check a server that parsed the old form |
| [cookies sent](#cookie-parameters-are-sent) | nothing in the code | check middleware that sets `Cookie` |
| [parameter serialization](#parameters-are-serialized-as-the-spec-says) | a declared `deepObject` parameter taken as `&str` | pass the typed value; check servers that read the old forms |
| [maps of inline enums](#a-map-of-an-inline-enum-is-a-map) | code that used the field as the enum | use the map |
| [array-of-uuid bodies](#parameters-are-serialized-as-the-spec-says) | the body argument `uuid_colon_colon_uuid` | call `.uuid(..)` |
| [nullable union parameters](#smaller-fixes) | a required query, header or cookie parameter of a nullable union | wrap it in `Some(..)` |
| [union query parameters](#smaller-fixes) | nothing in the code: sent as `Display` (`status=active`) | check servers that parsed the quoted JSON |
| [discriminator mapping](#a-shared-tag-enum-follows-the-mapping) | nothing in the code | a value now decodes the branch the mapping names |
| [validation](#models-implement-validate) | a hand-kept `Cargo.toml` with checked patterns | add `regex` |
| [clippy-clean](#generated-code-is-clippy-clean) | nothing | drop `needless_return` and `into_iter_on_ref` from `apiLintAllows` |

### The baseline options are gone

`boxOnlyCycles`, `defaultOnlyWhereValid`, `enumFromStr`, `noStructDisplay`, `integersAsI64`,
`qualifiedInlineNames`, `modelUnions`, `unionDispatch`, `openEnums`, `schemaDefaultDocs`, `nullableUnionFields` and
`boxResponseError` are no longer options. Their `true` rendering is always applied. Setting one, to any value, fails
generation:

```text
timvw-rust: option 'modelUnions' is not configurable: timvw-rust always uses 'true'; remove it (it was set to 'true')
```

Before, in a `-c` config file:

<!-- not compiled: configuration -->
```json
{
  "packageName": "my-client",
  "boxOnlyCycles": true,
  "modelUnions": true,
  "unionDispatch": true,
  "openEnums": true,
  "boxResponseError": true,
  "multipartHelper": true
}
```

After:

<!-- not compiled: configuration -->
```json
{
  "packageName": "my-client",
  "multipartHelper": true
}
```

A configuration that set all of them to `true` generates the same code as before, except for the other changes on
this page. One that left any of them off now gets the mapping's rendering, among others: the unions of
[`schema.oneOf`](../mapping/schema.md#schemaoneof) decoded by their tag, `Option<Option<U>>` for a nullable union field,
boxes only on cycles, `Default` only where valid, `FromStr` on closed enums, no JSON `Display` on structs, `i64`
integers, open response enums, inline schemas named after where they stand (which renames public types whose title was
not unique), defaults in the field documentation, and boxed errors. Review the generated diff in that case.

### An unknown option fails generation

A key of `--additional-properties` or of a `-c` file must be a timvw-rust option, one of openapi-generator's general
options, or a key openapi-generator sets itself. Anything else fails, with every unknown key and the closest option:

```text
timvw-rust: unknown options 'bonBuilder' (did you mean 'bonBuilders'?), 'frobnicate'; remove them or correct their names (config-help -g timvw-rust lists the options)
```

In a `-c` file, the CLI flag names are not settings names. `skipValidateSpec`, `dryRun` or `minimalUpdate` used to be
silently ignored there, and now fail. Use `validateSpec: false`, `enableMinimalUpdate: true` or the CLI flags
(`--skip-validate-spec`, `--minimal-update`). The Maven plugin drops unknown `<configOptions>` entries before the
generator sees them; use `<additionalProperties>` so they are checked.

### Request bodies are borrowed

A function that only serializes its body takes it by reference. You keep the value after the call.

Before:

<!-- not compiled: 0.20 -->
```rust
let item = items_api::create_item()
    .configuration(&configuration)
    .new_item(new_item.clone())
    .call()
    .await?;
```

After:

```rust
let item = items_api::create_item()
    .configuration(&configuration)
    .new_item(&new_item)
    .call()
    .await?;
```

- An optional body: `.maybe_new_item(Some(&new_item))`.
- An array body is a slice, so `&items` with `items: Vec<T>` works.
- A map or `serde_json::Value` body is a reference, too.
- A body whose type is `String` (a string enum, `format: byte`) is `&str`.

Numbers, booleans, dates, multipart parts, form fields and files (`PathBuf`) are unchanged. The compiler finds every
call site (`expected &NewItem, found NewItem`). openai-ergonomic had 27 and langfuse-ergonomic 7.

### `uuid` parameters are `uuid::Uuid`

A `format: uuid` path, query or header parameter or form field took `&str`. It now takes `uuid::Uuid` by value, as a
model property already did (`Option<uuid::Uuid>` when optional). It is sent hyphenated, as before.

Before:

<!-- not compiled: 0.20 -->
```rust
items_api::list_owner_items().configuration(&configuration).owner_id("6f1c...").call().await?;
```

After:

```rust
let owner_id = uuid::Uuid::parse_str("6f1c2b8e-0d1e-4a5b-9c3d-2e4f5a6b7c8d")?;
let items = items_api::list_owner_items()
    .configuration(&configuration)
    .owner_id(owner_id)
    .call()
    .await?;
```

A crate that uses `uuid` or chrono types only in a response (or only in an array parameter) now depends on `uuid` or
`chrono`. Before, such a crate did not compile.

### The status picks the error variant

`entity` used to hold the first variant whose type accepted the body, so a 404 with the same body type as the 400 came
back as `Status400`. Now the status picks the variant: the exact code, else the range (`4XX`), else `default`. The
body is decoded as that response's type, and a body that does not decode as it is `UnknownValue`.

The enum does not change, so this compiles as before. But a match on `Status400` no longer catches a 404, and a 500
body that happened to decode as the 400's type is now `UnknownValue`. Read your matches with the status in mind:

```rust
match &response.entity {
    Some(items_api::GetItemError::Status404(problem)) => println!("missing: {}", problem.detail),
    Some(items_api::GetItemError::Status5XX(problem)) => println!("server: {}", problem.detail),
    Some(items_api::GetItemError::UnknownValue(value)) => println!("{}: {value}", response.status),
    None => println!("{}: {}", response.status, response.content),
}
```

### Errors have the headers

`ResponseContent` has a new field, `headers: reqwest::header::HeaderMap`, on every error status. `retry-after`,
request ids and rate limits can now be read without sending the request yourself. Code that builds a
`ResponseContent` with a struct literal (tests, mocks) adds it:

Before:

<!-- not compiled: 0.20 -->
```rust
let content = ResponseContent { status, content: body, entity: None };
```

After:

```rust
let content = ResponseContent {
    status: timvw_rust_runtime::StatusCode::TOO_MANY_REQUESTS,
    content: body.clone(),
    entity: None::<items_api::CreateItemError>,
    headers: timvw_rust_runtime::header::HeaderMap::new(),
};
```

`{:?}` of an error now prints every response header, which can include `set-cookie` or tokens. Log errors with
`Display`, or pick the fields ([errors](errors.md#logging)).

### Errors are always boxed

`Error::ResponseError` holds `Box<ResponseContent<T>>`. With `boxResponseError` (openai-client-base) it already did,
and the option is gone. Without it (langfuse-client-base), code that builds the error wraps the content, and code
that moves it out dereferences the box. Reading fields through a match works as before:

Before:

<!-- not compiled: 0.20 -->
```rust
let error = Error::ResponseError(content);
if let Err(Error::ResponseError(response)) = result {
    let content: ResponseContent<items_api::GetItemError> = response;
}
```

After:

```rust
let error: Error<items_api::CreateItemError> = Error::ResponseError(Box::new(content));
if let Err(Error::ResponseError(response)) = result {
    let content: ResponseContent<items_api::GetItemError> = *response;
    println!("{}", content.status);
}
```

### A text error holds its text

An error response whose schema is a `string` and whose media types are text (`text/plain`), not JSON, was decoded as
JSON, so a body such as `bad input` gave no `entity`. Now it is the variant with the body as it is:
`Status400("bad input")`, or `DefaultResponse(..)` for `default`. Code that read such an error from `content` can
match the variant instead:

```rust
if let Some(items_api::CreateItemError::Status400(message)) = &response.entity {
    println!("rejected: {message}");
}
```

### `date-time` parameters are sent in RFC 3339

With `useChrono` (the default), a `date-time` path, query or header parameter or form field was written with chrono's
`Display` (`2026-01-02 03:04:05 +01:00`). It is now written with `to_rfc3339()` (`2026-01-02T03:04:05+01:00`),
URL-encoded in the path and the query. A `date` stays `YYYY-MM-DD`. Signatures do not change:

```rust
let since = chrono::DateTime::parse_from_rfc3339("2026-01-02T03:04:05+01:00")?;
let recent = items_api::list_owner_items()
    .configuration(&configuration)
    .owner_id(owner_id)
    .since(since)
    .call()
    .await?;
```

An array of dates in a path or header, which did not compile, now joins its items. A server that parsed the old,
space-separated form needs checking; one that follows the spec gets what it asked for.

### Cookie parameters are sent

A cookie parameter was accepted as an argument and never sent. The cookie parameters that are set now go out in one
`Cookie` header (`a=1; b=2`), percent-encoded where needed. An api key with `in: cookie` joins the same header.
Signatures do not change:

```rust
items_api::list_owner_items()
    .configuration(&configuration)
    .owner_id(owner_id)
    .session("abc")
    .call()
    .await?;
```

On an operation that sends cookies, the generated header replaces a default `Cookie` header of the client. A middleware
that adds cookies must merge them into the existing header rather than append a second one.

### Parameters are serialized as the spec says

A set of parameter fixes. Most change only the bytes sent; the first one changes a type.

- **A query parameter the spec declares `style: deepObject`** keeps its type and is sent as `name[field]=value`.
  Before, it was turned into `&str` and then not sent at all, or, with `resetQueryParameterStyle`, sent as one
  `name=<text>`. In openai-client-base this is the vault `metadata` filter, which is now a
  `HashMap<String, String>`:

<!-- not compiled: 0.20 -->
```rust
items_api::list_items().configuration(&configuration).metadata("team=lamps").call().await?;
```

```rust
let metadata = std::collections::HashMap::from([("team".to_owned(), "lamps".to_owned())]);
let page = items_api::list_items()
    .configuration(&configuration)
    .metadata(metadata)
    .call()
    .await?;
```

- **A model parameter listed in `deepObjectQueryParameters`** is sent as `name[field]=value`. Before, it went with
  flat `field=value` keys. openai-client-base's audit log `effective_at` now arrives as `effective_at[gt]=...`. A
  required listed one is sent in deepObject style too; before, it went as its JSON.
- **An urlencoded form array is a pair per item** (`tags=a&tags=b`, `style: form, explode: true`) unless its
  `encoding` says `explode: false`. Before, it was one `,`-joined field. The form is a list of pairs in the spec's
  order, so a server that read one joined field reads repeated fields now. A required nullable form field left `None`
  is sent empty; before, it did not compile.
- **Path values encode a space as `%20`**, where they used `+`, and path arrays URL-encode each item. An enum, struct
  or map path value is URL-encoded too (a struct or map as its JSON). Path and header arrays of integers, uuids, enums
  and structs compile now and join their items' text.
- **`pipeDelimited` and `spaceDelimited` query arrays** are joined with `|` and a space; before, they were joined with
  `,`. With `explode: true` they repeat.
- An array of arrays (or of maps) in the query is sent as each item's JSON; it did not compile. A JSON body that is
  an array of uuids is named `uuid` (it was `uuid_colon_colon_uuid`): call the builder's `.uuid(&ids)` instead of
  `.uuid_colon_colon_uuid(..)`. A model cycle through an inline union is boxed.

### A map of an inline enum is a map

A property `additionalProperties: {type: string, enum: [..]}` was rendered as the enum itself (or a list of it), which
the JSON does not decode to. It is now `HashMap<String, <Enum>>`. A map of lists of it is `HashMap<String,
Vec<<Enum>>>`, and a list of such maps is `Vec<HashMap<String, <Enum>>>`. The field's type changes, and `validate()`
checks its `minProperties`/`maxProperties`.

### A shared tag enum follows the mapping

When every branch of a discriminated union declares the same `enum` of all tag values (`kind: [cat, dog]` on both
`Cat` and `Dog`), each value now decodes the branch the `discriminator.mapping` names: `cat` gives `Cat`, `dog` gives
`Dog`. Before, both branches were tried in order for either value, so a `dog` could decode as `Cat`. Nothing changes
in the code; a value that decoded into the wrong branch now decodes into the right one.

### Models implement `Validate`

Every model implements `models::validation::Validate`. `validate()` returns every violation as a `ValidationErrors`
list. Constrained fields get a `Validation:` line in their documentation ([validation](validation.md)). Nothing calls
it, so behaviour does not change. Three things to check:

- A crate with a checked `pattern` depends on `regex`. A hand-kept `Cargo.toml` adds `regex = "1"` (openai-client-base
  does).
- A schema whose module would be `models::validation` fails generation. Rename it, or set `validateMethods=false`.
- A trait of yours with a method named `validate`, in scope next to `Validate`, makes a call ambiguous. Call it by its
  path (`Validate::validate(&value)`).

### The generator's warnings reach the log

The generator's own warnings now reach the CLI and the Docker image log, among them one per `pattern` that is not
checked ([which](validation.md#what-is-not-checked)). Identifier renames and union fields a variant does not carry log
at debug, so only warnings you can act on remain (openai-client-base: 71 instead of 5331, langfuse-client-base: 4
instead of 1472, most of them openapi-generator's own). Read them once.

### Generated code is clippy-clean

A crate generated without options passes `cargo clippy -- -D warnings`:
- `ContentType::from` and the content-type match no longer `return` from their last expression (`needless_return`);
- the parameter code no longer calls `into_iter()` on a reference (`into_iter_on_ref`) or uses a redundant closure.

A client that allowed these lints through `apiLintAllows` only for the generated code can drop them.

Before:

<!-- not compiled: configuration -->
```json
{ "apiLintAllows": "clippy::needless_return, clippy::into_iter_on_ref" }
```

After: no `apiLintAllows`.

### Smaller fixes

- `supportMultipleResponses` with `typedEventStreams`: the stream function returns its `EventStream`. Before, it
  returned the stream where `ResponseContent<..Success>` was declared, which did not compile.
- `contentTypeMethods` without `typedEventStreams`: the method for another request media type of an event-stream
  operation decodes JSON. Before, it returned an `sse::EventStream` without `sse.rs`, which did not compile. With
  `typedEventStreams`, an SSE envelope schema gets no stream in those methods either.
- A map cookie or header parameter (`additionalProperties`), which did not compile, is sent as its JSON.
- A query parameter whose type is a union goes out as the union's `Display`: a string variant as plain text, no
  longer quoted JSON (openai-client-base's vault `status`: `status=active`, not `status=%22active%22`). A union's
  `Display` writes a uuid variant hyphenated, a date as `YYYY-MM-DD` and a date-time in RFC 3339; they were
  JSON-quoted.
- A required query, header or cookie parameter whose type is a nullable union takes an `Option` (it took the union
  itself): pass `Some(value)`; `None` stands for the spec's `null`.
- A `uuid` or chrono type that only a union variant uses adds the `uuid` or `chrono` dependency (the crate did not
  compile); a hand-kept `Cargo.toml` adds it too.
- Two properties of a model that become the same Rust field fail generation, naming both. Before, the crate did not
  compile:

  ```text
  timvw-rust: model Thing: properties 'foo-bar' and 'foo_bar' both become the Rust field foo_bar; rename one in the spec or map one with --name-mappings
  ```

- `cargo test --doc` passes on a generated crate. The generated README's `Cargo.toml` line and `cargo doc --open` are
  marked `toml` and `sh`. Code blocks of the spec's `info.description` that rustdoc would compile (no language,
  `rust`, or indented) are marked `text`. A client that kept its own README or disabled doctests for this can drop
  the workaround.
- `useSerdePathToError`: `Display` of `Error::SerdePathToError` is clippy-clean.

### New, off by default

- `responseFunctions`: `<operation>_response` returns the status, headers and undecoded body of a success
  ([guide](response-functions.md)).

### Upgrading a client repository

For openai-client-base and langfuse-client-base, and any client generated the same way:

1. Bump the image tag in the generation script to `0.21.0-og7.27.0`.
2. Remove the removed options from the configuration (`scripts/generator-config.json`,
   `scripts/generate-openapi-client.sh`).
3. Remove `clippy::needless_return` and `clippy::into_iter_on_ref` from `apiLintAllows` if they were only there for
   the generated code.
4. In a hand-kept `Cargo.toml`, add `regex = "1"` if the generated one has it.
5. Regenerate, then fix the hand-written tests, examples and README examples. The compiler lists them: bodies by
   reference, uuids, `ResponseContent` literals.
6. Run `cargo clippy --all-targets -- -D warnings` and `cargo test`.
7. In the ergonomic crate, pass bodies by reference. It may then use the error headers (`retry-after`), `validate()`
   and the response functions.

## From stock `-g rust`

`timvw-rust` started as `-g rust` with the clients' post-processing scripts folded in. It generates one client of
`-g rust`'s, the async reqwest client with middleware, and renders models and errors after its own
[mapping](../mapping/README.md). Switching:

- **Options.** The crate options keep their names and defaults: `packageName`, `packageVersion`, `useChrono`,
  `useSerdePathToError`, `supportMultipleResponses`, `reqwestDefaultFeatures`, `publishRustRegistry`, `repositoryUrl`,
  `documentationUrl`, `homePageUrl`. `library`, `supportAsync` and `supportMiddleware` are fixed: remove them. The
  other client options (`useSingleRequestParameter`, `preferUnsignedInt`, `bestFitInt`, `avoidBoxedModels`,
  `topLevelApiClient`, `mockall`, `withAWSV4Signature`, `supportTokenSource`) fail generation. `useBonBuilder` is
  `bonBuilders`, on by default. `-t` (custom templates) is not supported.
- **Calls.** Every function has a bon builder, so a call is `get_item().configuration(&c).id("1").call().await`. For
  positional calls as in `-g rust` (`get_item(&c, "1").await`), set `bonBuilders=false`. Request bodies are borrowed.
- **Types.** `integer` is `i64` (stock: `i32` unless `int64`). A model reference is boxed only where it closes a
  cycle. `Default` is derived only on structs whose fields are all optional. Structs have no JSON `Display`.
  `uuid` is `uuid::Uuid` in parameters too.
- **Unions.** Every `oneOf`/`anyOf` is an enum that decodes by its tag when it has one, so a discriminated union
  decodes (stock's internally tagged enum takes the tag from a struct that requires it). Variants are named after
  their branch (`Text`, `ArrayOfParts`), and a string union converts from `&str`.
- **Enums.** A response enum or tagged union has an `Unknown` catch-all, and closed enums implement `FromStr`.
- **Errors.** The status picks the variant. Errors are boxed and have the headers. A text error holds its text.
- **Additions.** `validate()` on every model, and optionally response functions, typed event streams, a method per
  media type, a multipart helper.

The [comparison](../comparison/README.md) shows both, and progenitor, on one spec.
