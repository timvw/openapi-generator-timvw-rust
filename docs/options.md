# Options

No options are needed: what `timvw-rust` renders without them is the [mapping](mapping/README.md). The options add
functions or modules, set the runtime dependency and its TLS, shape the crate, or adapt a spec whose shape does not fit; none of them
makes the models or the error handling "more correct". This page lists every option, with its default, an example and
the tests that cover it, and then the names the generator [rejects](#not-options).

The Rust snippets are excerpts of generated code (from the tests that the entries link), not compiled examples.

## Passing options

- On the command line: `--additional-properties=<name>=<value>,<name>=<value>`.
- In a config file, `-c generator-config.json` (or YAML): `{"<name>": <value>, ...}`.
- With the openapi-generator Maven plugin: `<additionalProperties>` (not `<configOptions>`, which drops a key it does
  not know without a word), see [getting started](guides/getting-started.md#running-the-generator).

`config-help -g timvw-rust` lists the options. An option that does not exist fails generation and names the closest one
(`did you mean 'bonBuilders'?`), so a typo cannot change the output silently
([`UnknownOptionsTest`](../src/test/java/be/timvw/openapi/rust/UnknownOptionsTest.java)).

Value types:

- **boolean**: `true` or `false` (a JSON boolean or a string).
- **string**: one value.
- **list**: entries separated by `|`, `;` or whitespace on the command line, or a JSON/YAML array in a config file. A
  comma ends an `--additional-properties` value, so a list cannot use commas there.
- **comma-separated** (`reqwestDefaultFeatures`, `apiLintAllows`): one string; several values only work in a config
  file, for the same reason.

| Group | Options |
| --- | --- |
| [The crate](#the-crate) | `packageName`, `packageVersion`, `publishRustRegistry`, `repositoryUrl`, `documentationUrl`, `homePageUrl`, `userAgentFromPackage`, `apiLintAllows` |
| [HTTP stack and runtime](#http-stack-and-runtime) | `runtimeVersion`, `runtimePath`, `reqwestDefaultFeatures` |
| [Cargo features](#cargo-features) | `apiFeatures`, `apiFeaturesFile` |
| [Types and naming](#types-and-naming) | `useChrono`, `bonBuilders`, `titleNaming`, `typedEnumParameters`, `deprecatedAttributes` |
| [Functions and modules](#functions-and-modules) | `responseFunctions`, `supportMultipleResponses`, `useSerdePathToError`, `contentTypeMethods`, `typedEventStreams`, `validateMethods` |
| [Helpers](#helpers) | `multipartHelper`, `pruneAllHelpers` |
| [Adapting a spec](#adapting-a-spec) | `stringIdSchemas`, `stringIdProperties`, `stringProperties`, `fileProperties`, `deepObjectQueryParameters`, `resetQueryParameterStyle`, `webhookModule`, `webhookModuleReexport` |
| [Debugging](#debugging) | `unionPlanFile`, `preprocessedSpecFile` |

## The crate

These keep the names and defaults of stock `-g rust`.

### `packageName`

string, default `openapi`. The crate's name; `-` becomes `_` where the crate is imported (`my-client` is
`my_client`).

```bash
--additional-properties=packageName=my-client
```

Tests: [`TimvwRustCodegenTest`](../src/test/java/be/timvw/openapi/rust/TimvwRustCodegenTest.java) (`crateOptionsKeepTheirDefaults`).

### `packageVersion`

string, default the spec's `info.version`, else `1.0.0`. The crate's version; a version that is not semver is made one
(`v1.2` becomes `1.2.0`).

```bash
--additional-properties=packageVersion=0.32.0
```

Tests: [`TimvwRustCodegenTest`](../src/test/java/be/timvw/openapi/rust/TimvwRustCodegenTest.java).

### `publishRustRegistry`

### `repositoryUrl`

### `documentationUrl`

### `homePageUrl`

strings, unset by default. `Cargo.toml`'s `publish = ["<value>"]`, `repository`, `documentation` and `homepage`;
unset leaves the key out.

```json
{ "repositoryUrl": "https://github.com/genai-rs/openai-client-base", "documentationUrl": "https://docs.rs/openai-client-base" }
```

Tests: [`UnknownOptionsTest`](../src/test/java/be/timvw/openapi/rust/UnknownOptionsTest.java) (`theCargoKeysAreAdvertisedOptions`).

### `userAgentFromPackage`

boolean, default `false`. The default user agent is `<packageName>/<crate version>` instead of
`OpenAPI-Generator/<spec version>/rust`.

<!-- not compiled: generated code -->
```rust
// src/apis/configuration.rs, packageName=errors-lints
user_agent: Some(concat!("errors-lints/", env!("CARGO_PKG_VERSION")).to_owned()),
```

Tests: [`ErrorsAndLintsTest`](../src/test/java/be/timvw/openapi/rust/ErrorsAndLintsTest.java) (`userAgentIsThePackageAndCrateVersion`).

### `apiLintAllows`

comma-separated, unset by default. Lints allowed at the top of every API module and `configuration.rs`, as
`#![allow(..)]`. Several lints only work in a config file.

```json
{ "apiLintAllows": "clippy::needless_return, clippy::into_iter_on_ref" }
```

<!-- not compiled: generated code -->
```rust
// src/apis/widgets_api.rs, after the file header
#![allow(clippy::needless_return, clippy::into_iter_on_ref)]
```

Tests: [`ErrorsAndLintsTest`](../src/test/java/be/timvw/openapi/rust/ErrorsAndLintsTest.java) (`apiModulesAndConfigurationAllowTheLints`).

## HTTP stack and runtime

### `runtimeVersion`

string, default: the runtime release this generator was built for (`DEFAULT_RUNTIME_VERSION` in
[`TimvwRustCodegen.java`](../src/main/java/be/timvw/openapi/rust/TimvwRustCodegen.java); the
[crates.io badge](https://crates.io/crates/timvw-rust-runtime) shows the current release). The version
requirement of `timvw-rust-runtime` in `Cargo.toml`, written as `^<value>`. Every generated client sends through
`timvw_rust_runtime::Client`, a tower service (reqwest unless replaced) from
[`timvw-rust-runtime`](../runtime/README.md), with retries by default, `SecretString` credentials, per-call
`RequestOptions`, `Upload` files and WebAssembly support ([guide](guides/http-stack.md)). A runtime fix reaches a
client with `cargo update`; set it only to require a newer patch release.

```bash
# for example, to require a patch release (use a release of the runtime this generator was built for)
--additional-properties=runtimeVersion=X.Y.Z
```

```toml
# Cargo.toml: <runtimeVersion> is the value of the option (the default unless set)
timvw-rust-runtime = { version = "^<runtimeVersion>", default-features = false }
```

Tests: [`HttpStackTest`](../src/test/java/be/timvw/openapi/rust/HttpStackTest.java) (`theRuntimeVersionAndPathAreOptions`, `theDefaultRuntimeVersionIsTheRuntimeCrates`).

### `runtimePath`

string, unset by default. A path dependency on `timvw-rust-runtime` next to the version, to build against an unreleased runtime. The generator's own tests set it to `runtime/` (through the system property
`timvw-rust.runtimePath`).

```bash
--additional-properties=runtimePath=../openapi-generator-timvw-rust/runtime
```

```toml
timvw-rust-runtime = { version = "^<runtimeVersion>", default-features = false, path = "../openapi-generator-timvw-rust/runtime" }
```

Tests: [`HttpStackTest`](../src/test/java/be/timvw/openapi/rust/HttpStackTest.java) (`theRuntimeVersionAndPathAreOptions`).

### `reqwestDefaultFeatures`

comma-separated, default `native-tls`. The crate's default features, which choose reqwest's TLS: `native-tls`,
`rustls`, or empty for none (then `Client::default()` fails every request; set a client with `Client::from_service`).
The crate also has the features `retry` (default) and `tracing`, forwarded to the runtime
([features](guides/http-stack.md#features-of-the-generated-crate)).

```json
{ "reqwestDefaultFeatures": "rustls" }
```

```toml
[features]
default = ["rustls", "retry"]
native-tls = ["timvw-rust-runtime/native-tls"]
rustls = ["timvw-rust-runtime/rustls"]
retry = ["timvw-rust-runtime/retry"]
tracing = ["timvw-rust-runtime/tracing"]
```

Tests: [`TimvwRustCodegenTest`](../src/test/java/be/timvw/openapi/rust/TimvwRustCodegenTest.java) (`reqwestDefaultFeaturesTakesAListOrACommaSeparatedString`).

## Cargo features

### `apiFeatures`

boolean, default `false`. A Cargo feature per tag (`vector-stores`; untagged operations are `default-api`), each with
a `-types` twin that compiles only its models, without the HTTP client; plus `types` (every model) and `full`
(everything, a default feature). Models, API modules and functions get `#[cfg(feature = ..)]`.

```bash
--additional-properties=apiFeatures=true
```

```toml
my-client = { version = "1", default-features = false, features = ["rustls", "retry", "responses"] }
```

Guide: [API features](guides/api-features.md). Tests: [`ApiFeaturesTest`](../src/test/java/be/timvw/openapi/rust/ApiFeaturesTest.java),
[`FunctionGatedImportsTest`](../src/test/java/be/timvw/openapi/rust/FunctionGatedImportsTest.java).

### `apiFeaturesFile`

string (a path, relative to where the generator runs), unset by default; implies `apiFeatures`. A YAML or JSON file
naming groups of operations by `tags` and `paths` prefixes, in place of the tag features for the operations it
selects.

```yaml
# api-features.yaml, with apiFeaturesFile=/local/api-features.yaml
administration:
  tags: [Audit Logs, Projects, Users]
  paths: [/organization]
responses: { tags: [Responses, Conversations] }
```

Guide: [groups from a file](guides/api-features.md#groups-from-a-file-apifeaturesfile). Tests:
[`ApiFeaturesTest`](../src/test/java/be/timvw/openapi/rust/ApiFeaturesTest.java) (`theFileFormat`).

## Types and naming

### `useChrono`

boolean, default `true`. `date` and `date-time` are `chrono::NaiveDate` and `chrono::DateTime<chrono::FixedOffset>`;
`false` renders both as `String` and drops the `chrono` dependency (openai-client-base sets it).

```bash
--additional-properties=useChrono=false
```

Rule: [`schema.format.date-time`](mapping/schema.md#schemaformatdate-time). Tests:
[`TimvwRustCodegenTest`](../src/test/java/be/timvw/openapi/rust/TimvwRustCodegenTest.java) (`useChronoFalseRendersDatesAsStrings`),
[`DateParamsTest`](../src/test/java/be/timvw/openapi/rust/DateParamsTest.java).

### `bonBuilders`

boolean, default `true`. `bon::Builder` on model structs and `#[bon::builder]` on API functions. `false` makes the
functions take positional arguments, as stock does; the last one is the per-call
`Option<&RequestOptions>` (since 0.29; `None` for none).

<!-- not compiled: calls, with and without the option -->
```rust
// bonBuilders=true (default)
list_models().configuration(&config).limit(10).order("desc").call().await?;
// bonBuilders=false
list_models(&config, Some(10), Some("desc"), None, None).await?; // the last: per-call RequestOptions
```

Rule: [`operation.operationId`](mapping/operation.md#operationoperationid). Tests:
[`TimvwRustCodegenTest`](../src/test/java/be/timvw/openapi/rust/TimvwRustCodegenTest.java) (`bonBuildersIsOnByDefaultAndAdvertised`, `bonBuildersCanBeSwitchedOff`).

### `titleNaming`

boolean, default `true` (stock). Schema titles name, merge and replace types. `false` keeps the names from schema keys
and paths, so every component keeps its own type (openai-client-base sets it).

<!-- not compiled: a generated field, with titleNaming=false -->
```rust
pub status: models::BetaApplyPatchCallStatus, // with titleNaming=true: models::ApplyPatchCallStatus, shared by title
```

Rule: [`schema.title`](mapping/schema.md#schematitle). Tests:
[`TitleNamingTest`](../src/test/java/be/timvw/openapi/rust/TitleNamingTest.java).

### `typedEnumParameters`

boolean, default `false`. An inline `enum` of a parameter, or of a form or multipart property, becomes a component
enum (`<operationId>_<parameter>`, `<Schema>_<property>`) that the function takes instead of `&str`; it is sent as
its wire value (langfuse-client-base sets it).

<!-- not compiled: a generated signature, before and after -->
```rust
// without: order: Option<&str>
pub async fn list_models(configuration: &configuration::Configuration, limit: Option<i64>, order: Option<models::ListModelsOrder>, after: Option<&str>) -> ...
```

Rule: [`parameter.schema.enum`](mapping/parameter.md#parameterschemaenum). Tests:
[`ExtraRulesTest`](../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java).

### `deprecatedAttributes`

boolean, default `false`. `#[deprecated]` on deprecated properties and schemas; every generated model and API file
starts with `#![allow(deprecated)]`, so only callers are warned (langfuse-client-base sets it). Operations get
`#[deprecated]` without the option.

<!-- not compiled: a generated field -->
```rust
#[deprecated]
#[serde(rename = "legacy_user", skip_serializing_if = "Option::is_none")]
pub legacy_user: Option<String>,
```

Rule: [`schema.deprecated`](mapping/schema.md#schemadeprecated). Tests:
[`ExtraRulesTest`](../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java).

## Functions and modules

### `responseFunctions`

boolean, default `false`. Next to each operation with a body, `<operation>_response`, returning `ApiResponse<T>`:
the status, the headers and the undecoded body, to decode as the generated type, as a subset of your own (owned or
borrowed) or as text. The headers a success declares are typed in `<Operation>ResponseHeaders`
(`response.typed_headers::<..>()`), those an error declares in `<Operation>ErrorHeaders`.

<!-- not compiled: a call -->
```rust
let response = items_api::get_item_response().configuration(&configuration).id("item-1").call().await?;
```

Guide: [response functions](guides/response-functions.md). Rules:
[`response.content.response-function`](mapping/response.md#responsecontentresponse-function),
[`header.schema.typed`](mapping/header.md#headerschematyped),
[`header.schema.typed-error`](mapping/header.md#headerschematyped-error). Tests:
[`ResponseFunctionsTest`](../src/test/java/be/timvw/openapi/rust/ResponseFunctionsTest.java),
[`ResponseHeadersTest`](../src/test/java/be/timvw/openapi/rust/ResponseHeadersTest.java).

### `supportMultipleResponses`

boolean, default `false` (stock). A function returns `ResponseContent<<Operation>Success>`, an enum of its 2xx
responses, with the status, the body text and the headers, instead of the decoded body of the first.

<!-- not compiled: a generated signature -->
```rust
pub async fn create_response(..) -> Result<ResponseContent<CreateResponseSuccess>, Error<CreateResponseError>>
```

Tests: [`ErrorStatusTest`](../src/test/java/be/timvw/openapi/rust/ErrorStatusTest.java),
[`EventStreamsTest`](../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java) (`aStreamMethodReturnsTheStreamWithSupportMultipleResponses`).

### `useSerdePathToError`

boolean, default `false` (stock). Success bodies decode through `serde_path_to_error`, so a decoding error names the
path of the value: `apis::Error` gets `SerdePathToError(serde_path_to_error::Error<serde_json::Error>)`, and the crate
depends on `serde_path_to_error`.

Guide: [errors](guides/errors.md#the-error-type). Tests:
[`SmallFixesTest`](../src/test/java/be/timvw/openapi/rust/SmallFixesTest.java).

### `contentTypeMethods`

boolean, default `false`. A function per other request media type and per other response shape, named with a suffix:
`_json`, `_multipart`, `_form`, `_text`, `_bytes`, or the subtype (`_sdp`). The operation's own function sends the
first media type (openai-client-base sets it).

<!-- not compiled: generated names -->
```rust
create_transcription()       // multipart/form-data, the first media type, decoded as JSON
create_transcription_text()  // -> Result<String, Error<CreateTranscriptionError>>
```

Rules: [`requestBody.content`](mapping/request-body.md#requestbodycontent),
[`response.content`](mapping/response.md#responsecontent). Tests:
[`EventStreamsTest`](../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java).

### `typedEventStreams`

boolean, default `false`. A `text/event-stream` response is a function returning `apis::sse::EventStream<Event, _>`, a
stream of typed events (each `data:` decoded to the event schema, `[DONE]` ends it), with `idle_timeout` for a stream
that stalls. It is the operation's own function when the event stream is the first media type, `<operation>_stream`
otherwise. `src/apis/sse.rs` is written only when an operation streams; the crate then depends on `futures-core` and on
the runtime's `time` feature (openai-client-base sets it).

<!-- not compiled: a generated signature -->
```rust
pub async fn create_response_stream(..) -> Result<super::sse::EventStream<models::ResponseStreamEvent, CreateResponseError>, Error<CreateResponseError>>
```

Guide: [typed event streams](guides/http-stack.md#typed-event-streams). Rule:
[`response.content.event-stream`](mapping/response.md#responsecontentevent-stream). Tests:
[`EventStreamsTest`](../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java).

### `validateMethods`

boolean, default `true`. Every model implements `models::validation::Validate`, whose `validate()` checks the spec's
constraints. `false` leaves out the impls, `models/validation.rs` and the `regex` dependency.

```bash
--additional-properties=validateMethods=false
```

Guide: [validation](guides/validation.md). Tests:
[`ValidateTest`](../src/test/java/be/timvw/openapi/rust/ValidateTest.java).

## Helpers

### `multipartHelper`

boolean, default `false`. Multipart bodies go through a generated `src/apis/multipart_helper.rs`: files, an array as a
part per item, objects as JSON parts, `encoding` content types honoured, and unions with file variants (whose model
holds a `PathBuf`, see [limitations](limitations.md#generated-client)). openai-client-base sets it.

<!-- not compiled: generated code -->
```rust
multipart_form = multipart_helper::add_file_to_form(multipart_form, &p_form_file, "file").await?;
```

Rule: [`requestBody.content.multipart`](mapping/request-body.md#requestbodycontentmultipart). Tests:
[`MultipartTest`](../src/test/java/be/timvw/openapi/rust/MultipartTest.java),
[`MultipartFileUnionsTest`](../src/test/java/be/timvw/openapi/rust/MultipartFileUnionsTest.java).

### `pruneAllHelpers`

boolean, default `false`. Unused helpers are always left out of `union_dispatch.rs` and `multipart_helper.rs`; with
the option, also those marked `#[allow(dead_code)]`, kept otherwise as part of the helper module's API
(`file_part_from_bytes`, ...).

Tests: [`ExtraRulesTest`](../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java) (`pruneAllHelpersAlsoDropsHelpersKeptOnPurpose`, `helpersKeptOnPurposeStay`).

## Adapting a spec

These are for a spec whose shape does not fit as it is.

### `stringIdSchemas`

### `stringIdProperties`

### `stringProperties`

lists, empty by default. Render as `String`: the listed component schemas (an id schema such as a union of model
names; no model is generated for it), the properties of those names wherever such a schema types them
(`name=fallback_description` documents a property that had no description), and `Schema.property` entries.

```json
{ "stringIdSchemas": ["ModelIds"], "stringIdProperties": "model=ID_of_the_model_to_use|voice", "stringProperties": ["Event.kind"] }
```

<!-- not compiled: generated fields -->
```rust
pub model: String,
pub voice: Option<String>,
pub kind: Option<String>, // Event.kind
```

Rule: [`schema.type.scalar-component`](mapping/schema.md#schematypescalar-component). Tests:
[`SpecRulesTest`](../src/test/java/be/timvw/openapi/rust/SpecRulesTest.java) (`idSchemasAndIdPropertiesBecomeStrings`, `stringProperties`).

### `fileProperties`

list, empty by default. `Schema.property` entries rendered as files (`type: string, format: binary`), for a spec that
types an upload as a plain string.

```json
{ "fileProperties": ["CreateFileRequest.file"] }
```

Rule: [`schema.format.binary`](mapping/schema.md#schemaformatbinary). Tests:
[`MultipartTest`](../src/test/java/be/timvw/openapi/rust/MultipartTest.java) (`filePropertiesTurnAPropertyIntoAFile`).

### `deepObjectQueryParameters`

list, empty by default. `<path>?<name>` object query parameters sent in deepObject style (`name[field]=value`), like
those the spec declares `style: deepObject`; other object query parameters go as JSON strings.

```bash
--additional-properties=deepObjectQueryParameters=/things?filter
```

<!-- not compiled: generated parameters -->
```rust
metadata: Option<&str>,                                      // not listed: a JSON string
filter: Option<std::collections::HashMap<String, String>>,  // listed: filter[key]=value
```

Rule: [`parameter.in.query`](mapping/parameter.md#parameterinquery). Tests:
[`SpecRulesTest`](../src/test/java/be/timvw/openapi/rust/SpecRulesTest.java) (`objectQueryParametersBecomeStringsUnlessAllowed`).

### `resetQueryParameterStyle`

boolean, default `false`. The query parameters not sent in deepObject style get the default style (`form`, exploded),
whatever the spec says: an array goes as one pair per item.

<!-- not compiled: generated code -->
```rust
req_builder = req_builder.query(&[("ids", item)]);
```

Tests: [`SpecRulesTest`](../src/test/java/be/timvw/openapi/rust/SpecRulesTest.java) (`objectQueryParametersBecomeStringsUnlessAllowed`).

### `webhookModule`

string, unset by default. The tag given to webhook operations, generated into an API module of their own
(`webhookModule=Webhooks` gives `webhooks_api`). Unset, webhooks go into the module of their tag, after its path
operations.

Rule: [`openapi.webhooks`](mapping/openapi.md#openapiwebhooks). Tests:
[`WebhooksTest`](../src/test/java/be/timvw/openapi/rust/WebhooksTest.java),
[`SpecRulesTest`](../src/test/java/be/timvw/openapi/rust/SpecRulesTest.java) (`webhooksGetTheirOwnModuleAndDefaultApiReexportsIt`).

### `webhookModuleReexport`

boolean, default `false`; needs `webhookModule`. `default_api` re-exports the webhook module, for clients that had
the webhooks there.

<!-- not compiled: generated code -->
```rust
// src/apis/default_api.rs, at the end
pub use super::spec_rules_webhooks_api::*;
```

Tests: [`SpecRulesTest`](../src/test/java/be/timvw/openapi/rust/SpecRulesTest.java) (`webhooksGetTheirOwnModuleAndDefaultApiReexportsIt`).

## Debugging

### `unionPlanFile`

string, unset by default. Writes the union plan (each union, its variants and how it dispatches) to this
output-relative path as JSON, for tools and debugging.

Tests: [`ModelUnionsTest`](../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java),
[`UnionDispatchTest`](../src/test/java/be/timvw/openapi/rust/UnionDispatchTest.java).

### `preprocessedSpecFile`

string, unset by default. Writes the spec after the spec rules ran (names, hoisted unions, removed keywords) to this
output-relative path as JSON.

```bash
--additional-properties=preprocessedSpecFile=target/specs/preprocessed.json
```

Tests: [`SpecRulesTest`](../src/test/java/be/timvw/openapi/rust/SpecRulesTest.java) (`preprocessedSpecIsWritten`).

## Stock naming options

openapi-generator's own options work as usual: `--model-name-prefix`, `--model-name-suffix`, `--model-name-mappings`,
`--name-mappings` and the like rename what is generated. `--schema-mappings Filter=crate::custom::Filter` uses a type of
your own crate in place of a component schema: it is named by that path everywhere, and no model is generated for the
schema ([rule](mapping/components.md#componentsschemas)).

## Not options

`timvw-rust` generates one async client, on the tower service of `timvw-rust-runtime`. Setting any of these names fails generation, whatever the value (an empty
one too), and the error says why, so a configuration taken over from `-g rust` or
an older release cannot be silently ignored. The [migration guide](guides/migration.md) lists what to change. An
operation with `x-group-parameters` fails too.

"Since" is the release from which the name fails generation, read from the generator's source at each release tag
and from [history](history.md). The first eight stock options were pinned from 0.1.0: up to 0.18.x their pinned value
was accepted and any other value failed; since 0.19.0 any value fails.

| Name | Since | Why |
| --- | --- | --- |
| `library` | pinned to `reqwest` in 0.1.0; any value fails since 0.19.0 | only the async client on `timvw-rust-runtime` (reqwest by default) is rendered: as an additional property it fails whatever the value; `--library reqwest` (the CLI flag) is accepted, another library fails |
| `supportAsync` | pinned to `true` in 0.1.0; any value fails since 0.19.0 | always async |
| `supportMiddleware` | pinned to `true` in 0.1.0; any value fails since 0.19.0 | the client is the runtime's tower service; middleware are tower layers ([HTTP stack](guides/http-stack.md)) |
| `useSingleRequestParameter` | pinned to `false` in 0.1.0; any value fails since 0.19.0 | the functions take their arguments through the bon builder, or positionally |
| `preferUnsignedInt` | pinned to `false` in 0.1.0; any value fails since 0.19.0 | integers are signed, `i64` unless `format: int32` |
| `bestFitInt` | pinned to `false` in 0.1.0; any value fails since 0.19.0 | the same |
| `enumNameSuffix` | pinned to empty in 0.1.0; any value fails since 0.19.0 | enums are named after their schema |
| `hideGenerationTimestamp` | pinned to `true` in 0.1.0; any value fails since 0.19.0 | no timestamp is written |
| `avoidBoxedModels` | 0.1.0 | boxing is only on cycles (always) |
| `mockall` | 0.1.0 | not supported: replace the client's service (`Client::from_service`) in tests |
| `topLevelApiClient` | 0.1.0 | not supported |
| `withAWSV4Signature` | 0.1.0 | not supported: sign with a tower layer |
| `supportTokenSource` | 0.1.0 | not supported: fetch a token with a tower layer |
| `useBonBuilder` | 0.1.0 | named `bonBuilders` |
| `templateDir` | 0.2.0 | the templates are the generator's own (no `-t`) |
| `boxOnlyCycles` | always applied since 0.21.0 | a reference is boxed only where it closes a cycle |
| `defaultOnlyWhereValid` | always applied since 0.21.0 | `Default` only where its value is valid |
| `enumFromStr` | always applied since 0.21.0 | closed enums have `FromStr` and `TryFrom` |
| `noStructDisplay` | always applied since 0.21.0 | structs have no JSON `Display` |
| `integersAsI64` | always applied since 0.21.0 | `i64` unless `format: int32` |
| `qualifiedInlineNames` | always applied since 0.21.0 | inline schemas are named after a unique title or their location |
| `modelUnions` | always applied since 0.21.0 | unions render from the model |
| `unionDispatch` | always applied since 0.21.0 | a union with a tag decodes by it |
| `openEnums` | always applied since 0.21.0 | enums and tagged unions a response can hold have a catch-all |
| `schemaDefaultDocs` | always applied since 0.21.0 | a schema `default` is noted in the docs |
| `nullableUnionFields` | always applied since 0.21.0 | a nullable union field is `Option<Option<U>>` |
| `boxResponseError` | always applied since 0.21.0 | `Error::ResponseError` holds a `Box<ResponseContent<T>>` |
| `jsonValueSchemas` | removed in 0.15.0 | unions render as typed enums. Up to 0.28.x an empty value (`jsonValueSchemas=`) was accepted and ignored; since 0.29.0 any value fails |
| `legacyModelRewrites` | removed in 0.17.0 | the model-level rules replaced the text rewrites |
| `httpStack` | removed in 0.29.0 | the client always sends through the runtime's tower service; the `reqwest-middleware` stack is gone ([HTTP stack](guides/http-stack.md)) |
