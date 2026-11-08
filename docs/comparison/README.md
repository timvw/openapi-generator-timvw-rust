# timvw-rust compared with stock `-g rust` and progenitor

This page compares three ways to generate a Rust client from the same spec:

- **stock**: openapi-generator 7.27.0, `-g rust`, with the settings timvw-rust pins (`library=reqwest`, `supportAsync`,
  `supportMiddleware`, `useChrono=false`).
- **timvw-rust**: this generator, with the options openai-client-base runs, minus its OpenAI-specific lists
  ([`timvw-rust-config.json`](timvw-rust-config.json)).
- **progenitor**: Oxide's generator, 0.16.0, through `cargo progenitor --interface builder`. Its types come from typify.

The spec is [`spec.yaml`](spec.yaml), a small chat API in the shape of the OpenAI spec. Every snippet and result here
comes from running [`generate.sh`](generate.sh), which writes the three crates to `target/comparison/`, runs
`cargo clippy` on each, and decodes the same JSON payloads with each ([`decode.rs`](decode.rs)). Snippets have their
file headers and blank lines removed.

How timvw-rust renders every OpenAPI construct, with the option behind each rule, is in
[docs/type-mapping.md](../type-mapping.md).

## Summary

| | stock `-g rust` | timvw-rust | progenitor |
| --- | --- | --- | --- |
| Generates the whole spec | yes | yes | no: rejects multipart, and panics on an operation with two response types |
| Discriminated `oneOf` (`Message`) | internally tagged enum that **decodes no message**: the tag is taken away from the struct that requires it | untagged for serialization, decoded by the tag | untagged; the discriminator is ignored |
| `oneOf` with a fixed `type` but no discriminator (`ContentPart`) | untagged, tries each variant | decoded by the `type` it finds (`unionDispatch`) | untagged, tries each variant |
| Decoding errors | `data did not match any variant` | names the variant and the field: `ContentPart::ImagePart: missing field 'url'` | `data did not match any variant` |
| A value the spec does not list (`finish_reason: content_filter`) | error, the whole response is lost | `Unknown` (`openEnums`) | error |
| `integer` without a format | `i32` (overflows at 2^31) | `i64` (`integersAsI64`) | `i64` |
| Boxing | every model reference | only on a cycle (`boxOnlyCycles`) | only on a cycle |
| `nullable` field | `Option<Option<T>>`: absent and `null` differ | `Option<Option<T>>`; a nullable union `Option<U>` unless `nullableUnionFields` | `Option<T>`: `null` is sent as absent |
| `Default` | on everything, first enum variant picked arbitrarily | only on structs whose fields are all optional (`defaultOnlyWhereValid`) | the same; a schema `default` becomes a serde default |
| Enum query parameter (`order`) | `Option<&str>` | `Option<&str>`; `ListModelsOrder` with `typedEnumParameters` | `ListModelsOrder` |
| Builders | none | `bon::Builder` on structs, `#[bon::builder]` on API functions | generated builders for types and requests |
| Event streams | the JSON method only | `create_chat_completion_stream` yields typed `ChatCompletionChunk`s | not supported |
| Multipart | stock rendering | helper module, files, arrays, enum text parts | rejected |
| Return value | `T` | `T` | `ResponseValue<T>` (body, status, headers) |
| Call shape | free functions taking `&Configuration` | the same, plus a builder per function | methods on one `Client` |
| `cargo clippy` warnings | 45 | 13, all `needless_return`, which openai-client-base allows (`apiLintAllows`) | 0 |
| Lines of Rust | 1,666 | 2,847 | 3,320 (one file) |

The rest of the page shows each row in code.

## What decodes

The same ten payloads, decoded with each crate's type and encoded back:

| Payload | stock | timvw-rust | progenitor |
| --- | --- | --- | --- |
| `Message` `{"role":"system","content":"Be brief."}` | error: missing field `role` | ok | ok |
| `Message` user with an array of parts | error: missing field `role` | ok | ok |
| `Message` assistant, `"content":null` | error: missing field `role` | ok, re-encodes `"content":null` | ok, re-encodes **without** `content` |
| `Message` user without `content` | error: missing field `role` | error: `Message::User: missing field 'content'` | error: data did not match any variant of untagged enum Message |
| `Message` with role `developer` | error: unknown variant `developer`, expected one of ... | error: `Message: unknown 'role' value "developer"` | error: data did not match any variant of untagged enum Message |
| `ContentPart` image without `url` | error: data did not match any variant of untagged enum ContentPart | error: `ContentPart::ImagePart: missing field 'url'` | error: data did not match any variant of untagged enum ContentPart |
| `ContentPart` with type `audio` | error: data did not match any variant ... | error: `ContentPart: unknown 'type' value "audio"` | error: data did not match any variant ... |
| `ChatCompletion` with `finish_reason: content_filter` | error: unknown variant `content_filter` | ok, `FinishReason::Unknown` | error: unknown variant `content_filter` |
| `Usage` with `total_tokens: 3000000000` | error: invalid value, expected i32 | ok | ok |
| `ChatCompletionChunk` with `finish_reason: null` | ok | ok | ok, re-encodes without `finish_reason` |

`Message` with role `developer` is an error for timvw-rust too: `openEnums` opens only what a response can hold, and
`Message` is request-only in this spec. In openai-client-base the message unions a response returns do get
`Unknown(Value)`.

## Tagged unions

The spec's `Message` is a `oneOf` with a `discriminator` on `role`, and each branch requires `role` with one value.

**stock** renders an internally tagged enum, and keeps `role` in each struct:

```rust
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "role")]
pub enum Message {
    #[serde(rename="system")]
    System(Box<models::SystemMessage>),
    #[serde(rename="user")]
    User(Box<models::UserMessage>),
    #[serde(rename="assistant")]
    Assistant(Box<models::AssistantMessage>),
}
// system_message.rs
pub struct SystemMessage {
    #[serde(rename = "role")]
    pub role: Role,
    #[serde(rename = "content")]
    pub content: String,
}
```

serde removes the tag before it decodes the variant, so `SystemMessage` never sees the `role` it requires: every
message fails with ``missing field `role` ``. Serializing writes `role` twice. This is the main reason the client repos
post-processed stock output (`fix_untagged_unions.py`).

**timvw-rust** (`modelUnions`, `unionDispatch`) keeps the structs as they are, serializes untagged, and decodes by
the tag:

```rust
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(untagged)]
pub enum Message {
    System(models::SystemMessage),
    User(models::UserMessage),
    Assistant(models::AssistantMessage),
}
impl Message {
    /// Decodes the variant that `role` selects, so an error is that variant's.
    fn dispatch(value: serde_json::Value) -> Result<Self, String> {
        // ...
        match dispatch::tag(&value, UNION, TAG)? {
            Tag::Value("system") => dispatch::decode(value, UNION, "System").map(Self::System),
            Tag::Value("user") => dispatch::decode(value, UNION, "User").map(Self::User),
            Tag::Value("assistant") => dispatch::decode(value, UNION, "Assistant").map(Self::Assistant),
            Tag::Value(other) => Err(dispatch::unknown_tag(UNION, TAG, other)),
            // ...
        }
    }
}
```

`ContentPart` has no discriminator, but every branch requires `type` with a fixed value, so timvw-rust finds that tag
and dispatches on it the same way. Stock and progenitor render it as a plain `#[serde(untagged)]` enum.

**progenitor** ignores the discriminator and renders every union untagged, with variants named after the referenced
schemas:

```rust
#[serde(untagged)]
pub enum Message {
    SystemMessage(SystemMessage),
    UserMessage(UserMessage),
    AssistantMessage(AssistantMessage),
}
```

This decodes valid input, because each struct's `role` is a one-value enum, but every error is
`data did not match any variant`.

progenitor does better on inline unions it can see whole. `ResponseFormat`, two inline objects told apart by `type`,
one carrying a single extra field, becomes an adjacently tagged enum with no structs at all:

```rust
#[serde(tag = "type", content = "json_schema")]
pub enum ResponseFormat {
    #[serde(rename = "text")]
    Text,
    #[serde(rename = "json_schema")]
    JsonSchema(::serde_json::Map<::std::string::String, ::serde_json::Value>),
}
```

timvw-rust and stock name the two branches `Text` and `JsonSchema` after their titles and render a struct for each.

## Union variant names

| Union | stock | timvw-rust | progenitor |
| --- | --- | --- | --- |
| `UserMessage.content`: string or array of parts | `String(String)`, `ArrayVecContentPart(Vec<ContentPart>)` | `Text(String)`, `ArrayOfContentParts(Vec<ContentPart>)` | `String(String)`, `Array(Vec<ContentPart>)` |
| `stop`: string or array of strings | `String`, `ArrayVecString` | `Text`, `ArrayOfStrings` | `String`, `Array` |
| `Message` | `System`, `User`, `Assistant` (discriminator keys) | `System`, `User`, `Assistant` | `SystemMessage`, `UserMessage`, `AssistantMessage` |

timvw-rust also gives a union with a string variant `From<String>`, `From<&str>` and `new_text`, so
`.stop("END".into())` works, and a `Display`.

## Enums

Stock and progenitor render a closed enum; stock also adds a `Default` that picks the first value. timvw-rust adds a
catch-all where a response can hold the enum (`openEnums`), and `FromStr`/`TryFrom` (`enumFromStr`):

```rust
pub enum FinishReason {
    #[serde(rename = "stop")]
    Stop,
    #[serde(rename = "length")]
    Length,
    #[serde(rename = "tool_calls")]
    ToolCalls,
    /// A value this client does not know. It is not kept: this serializes as `"unknown"`, which the API does not list.
    #[serde(rename = "unknown", other)]
    Unknown,
}
impl ::std::str::FromStr for FinishReason { /* accepts exactly what Display prints */ }
```

progenitor also implements `FromStr` and `TryFrom` on its enums, and has no catch-all.

## Struct fields

`CreateChatCompletionRequest`, field by field:

| Field (spec) | stock | timvw-rust | progenitor |
| --- | --- | --- | --- |
| `temperature: number, nullable` | `Option<Option<f64>>` | `Option<Option<f64>>` | `Option<f64>` |
| `max_tokens: integer, nullable` (no format) | `Option<Option<i32>>` | `Option<Option<i64>>` | `Option<i64>` |
| `seed: integer, int64` | `Option<i64>` | `Option<i64>` | `Option<i64>` |
| `n: integer, int32` | `Option<i32>` | `Option<i32>` | `Option<i32>` |
| `stream: boolean, default false` | `Option<bool>` | `Option<bool>`; `schemaDefaultDocs` adds ``Defaults to `false`.`` | `bool` with `#[serde(default)]` |
| `stop: nullable oneOf` | `Option<Option<Box<Stop>>>` | `Option<Stop>`; `Option<Option<Stop>>` with `nullableUnionFields` | `Option<Stop>` |
| `metadata: map` | `Option<HashMap<String, String>>` | `Option<HashMap<String, String>>` | `HashMap<String, String>`, skipped when empty |
| `response_format` | `Option<Box<ResponseFormat>>` | `Option<ResponseFormat>` | `Option<ResponseFormat>` |
| `legacy_user`, `deprecated` | `Option<String>`, no marker | `Option<String>`; `#[deprecated]` with `deprecatedAttributes` | `Option<String>`, no marker |

Notes:

- `Option<Option<T>>` (stock and timvw-rust) keeps "not sent" apart from "sent as `null`", which matters for PATCH-like
  APIs and for re-encoding a response unchanged. progenitor collapses the two, so a decoded `"content": null` is
  encoded without the field (see [What decodes](#what-decodes)).
- A nullable union in timvw-rust is an `Option` of the union, not an `Option<Option<..>>`: with `modelUnions` a
  `nullable` or `type: null` branch is no variant, and the union is an `Option` where it is nullable. So `stop` cannot
  be sent as an explicit `null`, unless `nullableUnionFields` is on.
- Only timvw-rust, with `deprecatedAttributes`, marks a deprecated field `#[deprecated]`.

## Boxing and recursion

`ThreadNode` refers to itself through `parent` and `replies`, and to `Author` through `author`.

| Field | stock | timvw-rust | progenitor |
| --- | --- | --- | --- |
| `author` | `Option<Box<Author>>` | `Option<Author>` | `Option<Author>` |
| `parent` | `Option<Box<ThreadNode>>` | `Option<Box<ThreadNode>>` | `Option<Box<ThreadNode>>` |
| `replies` | `Vec<ThreadNode>` | `Vec<ThreadNode>` | `Vec<ThreadNode>` |

stock boxes every model reference; timvw-rust (`boxOnlyCycles`) and progenitor box only the edges that close a cycle.

## Operations

| | stock | timvw-rust | progenitor |
| --- | --- | --- | --- |
| Call | `list_models(&config, Some(10), Some("desc"), None)` | the same, or `list_models().configuration(&config).limit(10).order("desc").call()` | `client.list_models().limit(10).order(ListModelsOrder::Desc).send()` |
| Success | `Result<ModelList, Error<ListModelsError>>` | the same | `Result<ResponseValue<ModelList>, Error<ErrorResponse>>` |
| Error body | `ListModelsError::DefaultResponse(ErrorResponse)` or `UnknownValue(Value)` | the same, `ResponseContent` boxed (`boxResponseError`) | `Error::ErrorResponse(ResponseValue<ErrorResponse>)` |
| Enum query parameter | `&str` | `&str` | the enum |
| Event stream (`text/event-stream`) | ignored: one method decoding JSON | `create_chat_completion_stream(..) -> EventStream<ChatCompletionChunk, _>` | generation panics; without it, JSON only |
| Multipart upload | `upload_file(&config, PathBuf, purpose: &str)` | the same through `multipart_helper` | generation fails |
| Request headers | user agent | user agent from the package (`userAgentFromPackage`) | an `api-version` header with the spec's `info.version` on every request |

progenitor validates arguments in the builder (`TryInto<ListModelsOrder>`) and reports a bad one as
`Error::InvalidRequest` when the request is sent.

## Documentation

- stock and timvw-rust write a `docs/` directory with one Markdown page per model and API (30 pages here), and copy
  descriptions into `///` comments. timvw-rust wraps bare URLs (`<https://...>`) so rustdoc's `bare_urls` lint stays
  quiet, and starts `lib.rs` with `#![doc = include_str!("../README.md")]`.
- progenitor writes no Markdown. Types carry their description, or their name when there is none, and every client
  method has a usage example of its builder.
- Stock leaves an empty `///` above an enum without a description (clippy `empty_docs`, 16 times here); timvw-rust
  did too (14) and no longer does.

## Generated code quality

`cargo clippy` with default lints on each crate:

| Warning | stock | timvw-rust | progenitor |
| --- | --- | --- | --- |
| this `impl` can be derived (`Default` of enums) | 16 | 0 | 0 |
| empty doc comment | 16 | 0 (14 before the fix below) | 0 |
| unneeded `return` statement | 13 | 13 | 0 |
| unused generated helper | 0 | 0 (3 before the fix below) | 0 |

openai-client-base silences `needless_return` through `apiLintAllows`; this comparison leaves that option out.

## Choosing

- **progenitor** gives the cleanest code and the best API ergonomics, and is the right choice for a 3.0 spec that
  stays inside what it supports: JSON bodies, one response type per operation. The OpenAI and Langfuse specs do not:
  they need multipart, event streams, several media types and `default` error responses, which is why the genai-rs
  clients cannot use it.
- **stock `-g rust`** generates everything but does not compile a discriminated union into something that decodes,
  overflows on large integers and fails a whole response on one new enum value, which is what the old
  post-processing scripts fixed.
- **timvw-rust** keeps stock's coverage and call shape and takes progenitor's type rules where they fit (boxing only
  on cycles, `i64`, `FromStr`, `Default` only where valid), plus what neither has: tag dispatch with precise errors,
  open enums, typed event streams and a method per media type.

## Open points

The comparison found six things timvw-rust rendered worse than stock or progenitor. All six are addressed; the
[README](../../README.md#renderings-from-the-generator-comparison) shows each rendering before and after.

| Gap | Fix | Default |
| --- | --- | --- |
| Unused generated helpers (`first_of`, `Attempt`, `add_schema_value_to_form`): 3 `dead_code` warnings | left out when nothing calls them; `pruneAllHelpers` also drops those marked `#[allow(dead_code)]` | on (no API change) |
| Empty `///` above enums without a description | the line is only written with a description | on (no API change) |
| Enum query and form parameters are `&str` (`order`, `purpose`) | `typedEnumParameters` | off |
| A nullable union field cannot send `null` | `nullableUnionFields` | off |
| Schema defaults (`stream: default false`) are lost | `schemaDefaultDocs` notes them in the docs; the types stay | off |
| No `#[deprecated]` on deprecated fields | `deprecatedAttributes` | off |

The options are off because each changes the public API or what a `-D warnings` consumer sees, and the comparison
config ([`timvw-rust-config.json`](timvw-rust-config.json)) keeps the options openai-client-base runs. With all four on,
the comparison crate renders as in the README's examples and clippy reports the same 13 `needless_return`s.

## Reproducing

```bash
./mvnw -DskipTests package
```

```bash
curl -fsSLo target/openapi-generator-cli-7.27.0.jar https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli/7.27.0/openapi-generator-cli-7.27.0.jar
```

```bash
cargo install cargo-progenitor && rustup component add rustfmt --toolchain nightly
```

```bash
docs/comparison/generate.sh
```

progenitor gets a reduced copy of the spec (`target/comparison/spec-progenitor.yaml`): without `/files`, without the
event-stream response, and with `default` responses read as `4XX`. On the full spec it fails with
`unexpected content type: multipart/form-data`, and without `/files` it panics with
`assertion failed: response_types.len() <= 1`, once for the second success media type and once for a `default`
response whose type differs from the success type. cargo-progenitor 0.16 also needs a nightly rustfmt
(`RUSTFMT=$(rustup which --toolchain nightly rustfmt)`), as it formats with unstable options.
