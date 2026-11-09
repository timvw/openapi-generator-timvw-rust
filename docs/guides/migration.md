# Guide: migrating

- [From 0.20 to 0.21](#from-020-to-021): every breaking change, with the code before and after.
- [From stock `-g rust`](#from-stock--g-rust): what is different when you switch generators.

The "after" examples use [`docs_examples.yaml`](../../src/test/resources/docs_examples.yaml), generated as
`my_client`, and are compiled by the tests. The "before" examples are 0.20 code and are not.

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
this page. One that left any of them off now gets the mapping's rendering: the unions of
[`schema.oneOf`](../mapping/schema.md#schemaoneof), boxes only on cycles, `Default` only where valid, `i64` integers,
open response enums and boxed errors. Review the generated diff in that case.

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
    status: reqwest::StatusCode::TOO_MANY_REQUESTS,
    content: body.clone(),
    entity: None::<items_api::CreateItemError>,
    headers: reqwest::header::HeaderMap::new(),
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
  an array of uuids is named `uuid` (it was `uuid_colon_colon_uuid`). A model cycle through an inline union is boxed.

### A map of an inline enum is a map

A property `additionalProperties: {type: string, enum: [..]}` was rendered as the enum itself (or a list of it), which
the JSON does not decode to. It is now `HashMap<String, <Enum>>`. A map of lists of it is `HashMap<String, Vec<<Enum>>>`,
and a list of such maps is `Vec<HashMap<String, <Enum>>>`. The field's type changes, and `validate()` checks its
`minProperties`/`maxProperties`.

### A shared tag enum follows the mapping

When every branch of a discriminated union declares the same `enum` of all tag values (`kind: [cat, dog]` on both
`Cat` and `Dog`), each value now decodes the branch the `discriminator.mapping` names: `cat` gives `Cat`, `dog` gives
`Dog`. Before, both branches were tried in order for either value, so a `dog` could decode as `Cat`. Nothing changes
in the code; a value that decoded into the wrong branch now decodes into the right one.

### Models implement `Validate`

Every model implements `models::validation::Validate`. `validate()` returns every violation as a `ValidationErrors`
list. Constrained fields get a `Validation:` line in their documentation ([validation](validation.md)). Nothing calls it, so behaviour does not change. Three things to
check:

- A crate with a checked `pattern` depends on `regex`. A hand-kept `Cargo.toml` adds `regex = "1"` (openai-client-base
  does).
- A schema whose module would be `models::validation` fails generation. Rename it, or set `validateMethods=false`.
- A trait of yours with a method named `validate`, in scope next to `Validate`, makes a call ambiguous. Call it by its
  path (`Validate::validate(&value)`).
- The generator's own warnings now reach the CLI and the Docker image log, among them one per `pattern` that is not
  checked ([which](validation.md#what-is-not-checked)). Identifier renames and union fields a variant does not carry
  log at debug, so only warnings you can act on remain (openai-client-base: 71 instead of 5331, most of them
  openapi-generator's own). Read them once.

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
- An optional query parameter whose type is a union goes out as the union's `Display`: a string variant as plain
  text, no longer quoted JSON (openai-client-base's vault `status`: `status=active`, not `status=%22active%22`).
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
