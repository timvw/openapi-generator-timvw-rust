# openapi-generator-timvw-rust

A custom [openapi-generator](https://openapi-generator.tech) Rust client generator, `timvw-rust`,
built as a Java subclass of the stock `rust` generator (`RustClientCodegen`).

Its purpose is to replace the post-processing scripts that
[openai-client-base](https://github.com/genai-rs/openai-client-base) and
[langfuse-client-base](https://github.com/genai-rs/langfuse-client-base) run on top of `-g rust`,
by moving every fix into the generator itself. How each OpenAPI construct becomes Rust is in
[docs/type-mapping.md](docs/type-mapping.md); how that compares with stock `-g rust` and progenitor is in
[docs/comparison](docs/comparison/README.md). The plan to document this mapping and then improve it is in [docs/roadmap.md](docs/roadmap.md).

**Status: Milestones 2 and 3 are complete.** openai-client-base and langfuse-client-base generate without scripts. The generator renders what stock `-g rust` renders
plus the conventions below, which langfuse-client-base used to patch in after generation (its public API is
byte-identical between the scripted pipeline and this generator), and the `titleNaming` option that kept
openai-client-base's type names stable across the openapi-generator 7.15 to 7.25 upgrade.

## Artifacts

| What | Where |
| --- | --- |
| Maven | `be.timvw:openapi-generator-timvw-rust` on Maven Central |
| Docker | `ghcr.io/timvw/openapi-generator-timvw-rust:<version>-og<openapi-generator version>` |
| Generator name | `timvw-rust` (`-g timvw-rust`) |
| Supported openapi-generator | 7.27.x (one image per supported version) |

The Docker image is a drop-in replacement for `openapitools/openapi-generator-cli`: same arguments,
same `/local` mount convention, same `JAVA_OPTS` environment variable.

## Usage in the client repos

Images are published per openapi-generator version, tagged `<generator version>-og<openapi-generator version>`
(for example `0.17.0-og7.27.0`). The plain `<generator version>` and `latest` tags point at the newest supported
openapi-generator (7.27.0). A client repo pins the openapi-generator version its pipeline is tuned for: openai-client-base
and langfuse-client-base both use `-og7.27.0`. Up to v0.16.0 images were also published for 7.15.0 and 7.25.0; those
template sets were removed once no client pinned them.

Replace the image in `scripts/generate.sh`:

```bash
docker run --rm -v "$PWD:/local" -u "$(id -u):$(id -g)" \
  ghcr.io/timvw/openapi-generator-timvw-rust:0.17.0-og7.27.0 generate \
  -g timvw-rust \
  -i /local/openapi.yml \
  -o /local \
  --additional-properties=packageName=my-client,packageVersion=0.1.0
```

Hand-maintained files (`Cargo.toml`, `README.md`, `.gitignore`) go in the output directory's
`.openapi-generator-ignore`, which the generator honours like any openapi-generator run.

Options not listed below (`packageName`, `packageVersion`, `useChrono`, `reqwestDefaultFeatures`,
`supportMultipleResponses`, `useSerdePathToError`, ...) pass through to the stock generator unchanged.

## What the generator renders differently from `-g rust`

For a side-by-side view of stock `-g rust`, timvw-rust and progenitor on one spec (generated types, what each
decodes, clippy, operations), see [docs/comparison](docs/comparison/README.md).

| Rule | Replaces (langfuse-client-base) |
| --- | --- |
| `#[derive(..., bon::Builder)]` on every model struct, `#[bon::builder]` on every API function, `bon = "^3"` in `Cargo.toml` (`bonBuilders`, default on) | `add_bon_builders.py`, `patch_generated.rs.sh` |
| `src/lib.rs` starts with `#![doc = include_str!("../README.md")]` | lib.rs injection in `generate-openapi-client.sh` |
| Bare URLs in `///` doc comments are wrapped in `<...>` (rustdoc's `bare_urls` lint); markdown docs are untouched | `patch_generated.rs.sh` |
| `parse_deep_object` returns `(prefix, value)` for non-object values instead of panicking | `patch_generated.rs.sh` |
| `type: integer` with `format: float`/`double` is read as `type: number` (logged) | `normalize_openapi_schema.py` |
| `titleNaming=false`: schema titles never name, merge or replace types. openapi-generator 7.2x merges titled component schemas of the same shape into one model, swaps an inline schema for a titled component that matches it, and names inline array items after the array's title (`ArrayOfItemsInner5`); with the option off, the generator removes component and array titles before running the inline-model resolver (which it therefore runs itself), so names come from schema keys and property paths as on 7.15. Default `true` (stock behaviour). | the openapi-generator 7.25 upgrade of openai-client-base (95 public types kept) |
| An optional multipart text part whose schema is a referenced string enum carries the enum's wire value (`param_value.to_string()`); stock 7.25 JSON-encodes every non-primitive part, so `verbose_json` arrived as `"verbose_json"`. Objects still travel as JSON. | the openapi-generator 7.25 upgrade of openai-client-base |
| A `oneOf` whose branches render to the same variant (two primitive branches with the same `title` give two `Text(String)`) keeps one variant; the drop is logged. | `fix_generator_artifacts.py` (openai-client-base) |
| An `anyOf` with no typed branch (`anyOf: [{}, {type: null}]`) is `serde_json::Value`; the stock generator references a model named `AnyOfLessThanGreaterThan` that it never writes. | `fix_generator_artifacts.py` (openai-client-base) |
| A required nullable model field is built with `name.map(Box::new)` in `new()`; stock renders `if let Some(x) = name {Some(Box::new(x))} else {None}` (clippy `manual_map`). | `fix_generated_code.py` (openai-client-base) |
| Spec rules, applied before inline schemas are named: `default` and `propertyNames` are removed from component schemas; a `$ref` keeps only the `$ref`; a `oneOf`/`anyOf` whose branches only state `required` is dropped; an untitled, non-reference branch gets the title its variant is named after (`Text`, `Integer`, `Number`, `Boolean`, `ArrayOf<Item>s`, `Object<i>`); an inline `oneOf`/`anyOf` of a property whose branches are all inline, none null and at least one array or object is hoisted into a component `<Schema><Property>` (the stock generator fails on the flattened composed model); a top-level union branch referencing a plain string component is inlined; schema names with hyphens are normalized (`Thing-2` → `Thing2`); an inline object title that would name an existing component's model is renamed `<Schema><Property>` (stock writes both to one file). | `patch_spec_rust_compat.py`, `fix_model_fields.py`, `prompt_cache_title_disambiguation.py` (openai-client-base) |
| `webhookModule=<Tag>`: webhook operations are tagged `<Tag>` and generated into their own API module, declared in `src/apis/mod.rs`; a path operation already carrying the tag fails generation. `webhookModuleReexport=true`: `default_api` re-exports that module (stock writes webhooks into `default_api`, where a webhook replaces the untagged path operations). | `route_webhook_apis.py`, `preserve_webhook_api.py` (openai-client-base) |
| `stringIdSchemas`: component schemas rendered as `String`. `stringIdProperties`: property names (`name` or `name=fallback_description`) rendered as `String` wherever they reference one of those schemas or are a union, directly or inherited through `allOf`. `stringProperties`: `Schema.property` entries rendered as a bare `String`. `jsonValueSchemas` (component schemas rendered as `serde_json::Value`) was removed: unions render as typed enums (`modelUnions`, `unionDispatch`), and a config that still sets it fails. | `fix_model_fields.py`, `simplified_schemas.py`, `patch_spec_rust_compat.py` (openai-client-base) |
| Object-typed query parameters (inline or a reference to an object schema) are sent as strings, except those listed in `deepObjectQueryParameters` (`<path>?<name>`), which are sent in deepObject style. `resetQueryParameterStyle=true` gives the other query parameters the default style (`form`, exploded), so arrays go as repeated parameters whatever the spec says (openai-client-base's current wire format). | `patch_spec_rust_compat.py` (openai-client-base) |
| Imports only where used: a model file has `use crate::models;` only when it refers to another model (`x-rust-uses-models`, with `modelUnions`; without it every model keeps the import), and an API module imports `ContentType` and `serde::de::Error as _` only when an operation decodes a response or streams events, and `TokioFile`/`FramedRead` only when an operation streams a file body (or a multipart file without `multipartHelper`). Decided in Java (`Imports`), printed by the templates. `legacyModelRewrites` was removed in 0.17.0: the text rewrites it ran were no-ops under the Milestone 3 options except this import pruning and a `serde_json::Value` retype of two `content` fields; a config that still sets it fails. | `fix_unused_imports.py` and the rest of `legacyModelRewrites` (openai-client-base) |
| `multipartHelper=true`: multipart bodies go through a generated `src/apis/multipart_helper.rs` (declared in `apis/mod.rs`, imported where used). Files are read into a part named after the file; an optional array (form/explode, the default) sends one part per item and an optional object one JSON part; an `encoding` `contentType` is applied to scalar and object parts. Other parameters keep the stock rendering. `fileProperties=<Schema.property,..>`: those properties become files (`type: string, format: binary`). A multipart model parameter's type is qualified once (stock renders `models::models::X`). With `modelUnions`, a parameter whose union has file variants matches on the union: a file goes through the helper like a file parameter, an array of files sends one part per file (named `<property>[]` where the spec's description documents that name in backticks, as `encoding` cannot say so, otherwise `<property>`), and any other variant is a schema value (a string plain text, an object a JSON part). A variant holding files in any other shape fails generation. | `patch_multipart.sh`, `fix_file_upload_todos.py`, `fix_multipart_serialization.py`, the `models::models::` edit of `patch_generated.rs.sh` (openai-client-base) |
| `boxOnlyCycles=true`: a model reference is `Box<T>` only when its target is in the same strongly connected component of the model graph as the model holding it, so every cycle goes through a box and nothing else does. Struct fields (required or `Option`) and enum variant payloads are edges; `Vec` and map items are not, and are never boxed. Without the option every model reference is boxed, as in stock `-g rust`. | nothing: a new rendering, which changes the public API (openai-client-base 0.18.0) |
| `defaultOnlyWhereValid=true`: a `Default` value must be one the API would accept. A struct derives `Default` only when it has no required field; a required nullable field counts as required. An all-optional struct keeps it whatever its field types, since `Option<T>` does not need `T: Default`. No enum implements `Default`: not value enums (the stock impl picks the first variant, arbitrarily) and not unions. Schema defaults stay ignored. The decision comes from the struct's own required fields (`x-rust-derive-default`), not from bare type names, which made the old text analysis strip the derive from every struct with a local field named like a non-`Default` type elsewhere. Without the option `Default` is rendered as before. `tools/check-default-rule.py` holds every `Default` of a generated crate against the rule. | `fix_default_issues.py` and the `Default` analysis in `fix_generated_code.py` (their ports), and the name collision they caused. A new rendering, which changes the public API |
| `enumFromStr=true`: every closed enum (all variants unit variants, not untagged or internally tagged, so no union) implements `FromStr` and `TryFrom<&str>`, `TryFrom<&String>` and `TryFrom<String>`, accepting exactly what its `Display` prints and rejecting everything else with `models::ParseEnumError`, one error type written once to `src/models/parse_enum_error.rs`. This covers string and integer enums and property enums inside a struct's file: the impls are added after rendering, from the final text. A property enum has no `Display` of its own, so it gets one that prints its serde names. A schema named `ParseEnumError` fails generation. | nothing: an additive API change |
| `noStructDisplay=true`: structs have no JSON `Display` (none does since 0.17.0: only the removed `legacyModelRewrites` added one, so without this option API code that calls `to_string()` on a struct parameter, as stock does, does not compile). API code that relied on it serializes explicitly with `serde_json::to_string`: path, query and header parameters and form and multipart text parts of struct type, and arrays of structs in the query, a form or a multipart part. The bytes on the wire are the same, and a serialization failure is now `Error::Serde` instead of a panic. Enums keep their `Display`, unions included. Generation fails if a rewrite turned such a parameter's struct into a union, whose `Display` is not its JSON. | nothing: removes `to_string()` on structs from the public API |
| `integersAsI64=true`: an integer is `i64` unless the spec says `format: int32`, wherever it appears: model fields, `Vec` and map items, parameters, multipart fields, responses, `new()` parameters, builders and union variants. Formats other than `int32` (`int64`, `unixtime`, absent, unknown, and on openapi-generator 7.27 also `int8`, `int16` and `uint8` to `uint64`) give `i64`. Spec bounds never pick a type, and nothing is inferred as unsigned or `NonZero`. `number` is untouched. Integer-valued enums keep `#[repr(i64)]`. Without the option integers render as in stock `-g rust`: an integer without a format, or with one other than `int64`, is `i32` (7.27 maps `int8`/`int16` to `i8`/`i16` and `uint8` to `uint64` to `u8` to `u64`). | nothing: a new rendering, which changes the public API |
| `qualifiedInlineNames=true`: an inline schema that becomes a model keeps its title as its name only when that title is unique: its Rust name matches no component and no other model that will be generated, and every inline schema with that name has the same shape (those share one model). Otherwise all of them, like untitled schemas, are named after where they stand: property `p` of `P` is `P_p` (Rust `PP`), array items `P_inner`, map values `P_value`, a `oneOf`/`anyOf` branch `P_<title>` or, untitled, `P_oneOf`/`P_anyOf` (branches of one union that would share a name get their position), `not` is `P_not`, and an operation's body, parameter or response is `<op>_request`, `<op>_<param>_parameter`, `<op>_<code>_response` (with the media type when several in one body need a model). `P` is the name of the enclosing schema, however it got it. The `Object<i>` titles the generator gives untitled branches never name a model. The outcome does not depend on the order of the spec. Generation then fails, naming both schemas and locations, when two structurally different schemas would produce the same Rust model or file; names are compared after Rust normalization, so `Static_Chunking_Strategy` and `StaticChunkingStrategy` are the same model. Schemas that differ only in annotations (title, description, examples, external docs, `deprecated`, `x-*`), or in the names of references to equal schemas, may share a model. Stock numbers an inline schema whose title is taken (`Object0_17`), lets a schema nested in one with the same title overwrite it (which then references itself), and writes a title that normalizes to a component's name over that component's file. Default `false`. With the option on, the `<Schema><Property>` rename of inline object titles above no longer runs: nothing needs it. | nothing: a new rendering, which renames types of the public API |
| `modelUnions=true`: unions render from the model (`UnionRules`). Every `oneOf` and `anyOf`, with or without a discriminator, is a `#[serde(untagged)]` enum, and a model that is `allOf` one union gets that union's variants; a `type: null` branch is no variant, the union is an `Option` where it is nullable. A variant is named after its branch: discriminator mapping key, referenced schema (minus the union's name when the branch was inline), title, then type. A string enum branch becomes a model `<Union><Variant>Enum`. Unions have `Display` (a string, enum or union variant prints itself, anything else its JSON, so a text part of union type goes out as plain text), and with a string variant `new_text`, `new_<vec variant>`, `From<String>` and `From<&str>`. `Default` is rendered unless `defaultOnlyWhereValid` is on. Structs keep every field, tag included. The variants are ordered for untagged decoding, see [below](#union-variant-order). Without `qualifiedInlineNames`, an inline schema with the title of the schema it stands in gets a title of its own (`<title> object`, `<title> <property>`), or the resolver makes it refer to itself. `unionPlanFile=<output-relative path>`: the variants of every union and the schemas they refer to are written there as JSON; `tools/check-union-samples.py` uses it to decode one sample per branch of a generated crate. | `fix_untagged_unions.py` and the other scripts named, and the bugs of their name-based rewriting. A new rendering, which changes the public API |
| `unionDispatch=true`, with `modelUnions`: a union with a tag decodes the variant that tag selects, and serializes as before; see [below](#union-tag-dispatch). Set without `modelUnions` it fails generation. | nothing: decoding that used to try every variant, and the error `data did not match any variant` |
| `openEnums=true`, with `modelUnions`: a value enum or tagged union that a response or webhook payload can hold accepts values the spec does not list; see [below](#open-enums). Set without `modelUnions` it fails generation. | nothing: one new value failing the whole response |
| `boxResponseError=true`: `Error::ResponseError` holds `Box<ResponseContent<T>>` and every API function builds it with `Box::new` (clippy `result_large_err`). `apiLintAllows=<lints>`: API modules and `configuration.rs` start with `#![allow(<lints>)]`. `userAgentFromPackage=true`: the default user agent is `<packageName>/<crate version>` (`env!("CARGO_PKG_VERSION")`) instead of `OpenAPI-Generator/<spec version>/rust`. | `box_response_errors.py`, `fix_clippy_warnings.py`, the user-agent edit of `patch_generated.rs.sh` (openai-client-base) |
| Helpers nobody calls are left out: `src/models/union_dispatch.rs` without `first_of` and `Attempt` when no union tries several variants, `src/apis/multipart_helper.rs` without the functions no API module calls. Items marked `#[allow(dead_code)]` stay unless `pruneAllHelpers=true`. Both modules are `pub(crate)`. Always on; see [below](#renderings-from-the-generator-comparison). | nothing: removes `dead_code` warnings |
| An enum without a description has no empty `///` above it (clippy `empty_docs`). Always on. | nothing: removes `empty_docs` warnings |
| `typedEnumParameters`, `nullableUnionFields`, `schemaDefaultDocs`, `deprecatedAttributes`: typed enum parameters, `null` for nullable union fields, schema defaults in the docs, `#[deprecated]`. Off by default; see [below](#renderings-from-the-generator-comparison). | nothing: closes gaps the [generator comparison](docs/comparison/README.md) found |
| `preprocessedSpecFile=<output-relative path>`: the spec as it stands after the rules above is written there as JSON, for scripts that still read it and for debugging. | the `target/specs/l2_rust_compatible.*` files the scripts produced |

Spec-level decisions stay in the client repos. Spec validation runs in the CLI before any generator
code, so a keyword the 3.0 validator rejects (Langfuse uses the 3.1 `const`) cannot be repaired here;
langfuse-client-base drops it while composing the generator input (`prepare_compatible_spec.py`, which
also adds the v3 compatibility layer). Any other invalid construct fails generation and is fixed upstream.

## Union variant order

With `modelUnions` every union is untagged, so decoding takes the first variant that accepts the value, and a struct
accepts any object that has its required fields. `UnionRules` puts a variant before another when both accept the same
JSON kind and it is the narrower one for that kind:

| Kind | Narrower first |
| --- | --- |
| number | an integer before a float, which accepts integers too |
| string | a closed enum before a free string |
| object | a struct before one that would decode its values when it would not decode theirs. A struct would decode a value when the value has every field the struct requires and no required field contradicts: a fixed tag with other values, or a type of another kind. When both would: more required fields first, then more fields, which lose less, and when that ties a union first, since its other arms fall through. Maps after structs |
| array | by these same rules, applied to the items; `Vec<serde_json::Value>` last |
| all | `serde_json::Value` last, since it accepts everything |

A union counts through its arms. When two variants compare differently on different kinds, the object kind decides.
Variants this says nothing about keep the order of the spec, so a tag that tells two variants apart leaves them where
they are.

Two variants that accept the same values stay ambiguous under this rule, and decode to whichever comes first:
`tools/check-union-samples.py` lists them. Tag dispatch tells apart those with a tag that differs.

## Union tag dispatch

With `unionDispatch` (which requires `modelUnions`) a union with a tag decodes by that tag. `UnionRules` finds the tag in
the schema:

- a `discriminator`, on `oneOf` or `anyOf`;
- without one, a property that every branch requires with fixed string values and that tells two variants apart. A
  branch that is a union has it when all its arms do. When several properties qualify, the one with the fewest values on
  two variants wins, then `type`, then the first by name.

Every other union stays `#[serde(untagged)]`, also one where only some branches require the property, one with a branch
that is no object, and one where the property has the same values everywhere. A variant no value selects, or one whose
struct has no such field, fails generation.

A tagged union keeps `#[derive(Serialize)]` and `#[serde(untagged)]`, so what it serializes to does not change: each
struct keeps its tag field, receives the tag when decoding, and writes it once. Its `Deserialize` is generated: it reads
a `serde_json::Value`, looks at the tag and decodes what that selects, with helpers from one crate-private module,
`src/models/union_dispatch.rs`. A schema with that name fails generation.

| Input | What happens |
| --- | --- |
| a value of one variant | that variant decodes, and its error is the error: `` Shape::Circle: missing field `radius` `` |
| a value on several variants | they are tried in the variant order, for that value only; if none decodes, each one's error is listed |
| any of several values of one variant | that variant decodes and holds the value it was given |
| a value of a variant that is a union | that union decodes, by its own tag, and its error comes with the path: `` InputItem::Item: Item::ToolCall: missing field `name` `` |
| no tag, or `null` | the variants where the tag is optional or nullable are tried in order; if there are none: `` Part: tag `type` is missing or null `` |
| an unknown value | `` Shape: unknown `kind` value "triangle" ``, or with [`openEnums`](#open-enums) `Unknown(Value)` where a response can hold the union |
| a tag that is no string | `` Shape: tag `kind` must be a string, got a number `` |
| no object | the variants that accept something else are tried in order; if there are none: `` Shape: expected an object with tag `kind`, got a number `` |

Numbers go through `serde_json::Value` as they go through the buffer of `serde(untagged)`: an integer up to 64 bits stays
that integer, a larger one and every float is an `f64`, which is also what a struct field would get. A key repeated in
one object is the last one, where a struct alone would reject it. A message has no line and column of its own when the
union is the whole document; inside a struct, `serde_json` adds the end of the value.

`unionPlanFile` then also holds each union's `dispatch`: its tag, what selects which variants, and which variants allow
no tag or accept no object. `tools/check-union-samples.py` uses it to decode a sample per value of every variant, count
the keys of what comes back, and check the error messages.

## Open enums

With `openEnums` (which requires `modelUnions`) one new value from the API does not fail a whole response.

**What opens.** What a response body can hold, in any media type and of any status, so server-sent events and error
bodies count, and what a webhook delivers (its request body, which the API sends). `OpenEnums` walks the schemas from
there through references, properties, items, map values and branches. A type a request also uses counts; a type only
requests reach stays closed. A schema a response takes in through `allOf` counts, since its fields are there.
What only travels outside the API operations, such as a WebSocket event, is not reached: it is typed and dispatched on
its tag like any union, but an unknown tag stays an error.

| Type | With a value the spec does not list |
| --- | --- |
| a value enum, inline in a struct or not, also `<Union><Variant>Enum` | the unit variant `Unknown` (`#[serde(rename = "unknown", other)]`), and it keeps `Copy`, `Eq`, `Hash` and `Ord` |
| an integer enum | the unit variant `Unknown = i64::MAX` (`#[serde(other)]` of `serde_repr`) |
| a union with a tag | `Unknown(serde_json::Value)`, holding what came, in place of the error for an unknown tag |
| a union without a tag | stays as it is |

A value of another JSON kind is still an error: a number for a string enum, a tag that is missing, `null` or no string on
a tagged union. serde also accepts an object with one key for an enum, as any externally tagged enum does, so `{"new":
null}` is `Unknown`.

**What it gives back.**

| | `Unknown` of an enum | `Unknown(Value)` of a union |
| --- | --- | --- |
| `Serialize` | `"unknown"` (an integer enum: `i64::MAX`), since the string is gone. No listed value has that spelling, so the API rejects it in a request instead of reading another value into it. It decodes to `Unknown` again. | the same JSON |
| `Display` | `unknown` | the JSON |
| `FromStr`, `TryFrom` | no string parses to it, `"unknown"` included: parsing is a caller's strict check, and a value a caller typed is not one the API sent | none |

An enum with a real value `unknown` keeps that as its variant `Unknown`; the catch-all is `UnknownValue` and serializes as
`"unknown_value"`. A branch called `Unknown` likewise leaves `UnknownValue(Value)` to the catch-all.

**What stays closed although a response can hold it.** Decoding that tries variants in turn (an untagged union, or the
variants of a tagged union that share a value) relies on an earlier variant rejecting what a later one is for, and an enum
is often what rejects it. A catch-all there would change what known input decodes to, so such an enum or union stays
closed: a closed enum in front of a free string (the string takes what the enum does not list) or another enum (the last
one opens), a `role` that tells two structs apart, which leaves their other fields free to open, a tagged union in front of
a struct whose tag is free, anything in a struct in front of a map or `Value`. Closed is decided per enum, so a component
enum that is guarded in one union stays closed where other types use it too. An unknown value there goes on to the later variant, or fails if nothing takes it, as
it does without the option. `unionPlanFile` lists them under `openEnums`, with the counts.

Neither the enums nor the unions get `#[non_exhaustive]`, so the variant is a breaking change the one time for a `match`
without a wildcard, like any new value in the spec, and such a match can name `Unknown` from then on.

`tools/check-union-samples.py` knows the catch-all: it checks that an unknown tag comes back as the same JSON.

## Content-type methods and event streams

The stock generator renders one method per operation: it sends the request body as the first media type the spec
lists and decodes the first media type of the success response. Two options give an operation a method per media type
(`ContentTypeMethods`). The operation's own method keeps its name and what it did; every other combination of a request
media type and a response shape is a method named after the operation plus a suffix per side, request first.

| Side | Media type | Suffix | Option |
| --- | --- | --- | --- |
| request body | `application/json`, `*/*+json` | `_json` | `contentTypeMethods` |
| request body | `multipart/form-data` | `_multipart` | `contentTypeMethods` |
| request body | `application/x-www-form-urlencoded` | `_form` | `contentTypeMethods` |
| request body | `text/plain` | `_text` | `contentTypeMethods` |
| request body | `application/octet-stream` | `_bytes` | `contentTypeMethods` |
| request body | anything else | its subtype (`application/sdp`: `_sdp`), a string body sent as is with that media type | `contentTypeMethods` |
| success response | `text/event-stream` | `_stream`, a typed stream | `typedEventStreams` |
| success response | JSON, other `text/*`, anything else | `_json`, `_text`, `_bytes`: one per shape, since the media types of a shape render to the same Rust type | `contentTypeMethods` |

On openai-client-base: `create_response` decodes the `Response`, `create_response_stream` streams the
`ResponseStreamEvent`s; `create_image_edit` sends multipart as it did, `create_image_edit_json` the JSON body,
`create_image_edit_stream` and `create_image_edit_json_stream` stream; `create_voice` sends JSON, `create_voice_multipart`
multipart; `create_transcription_text` returns the `text/plain` answer; `create_realtime_call_sdp` sends the SDP offer.
A method that is not the operation's own returns the operation's error enum (`Error<CreateResponseError>`) and renders
none of its own, and its documentation says what it sends and decodes.

**Event streams.** With `typedEventStreams` a method for a `text/event-stream` response returns
`Result<apis::sse::EventStream<Event, OperationError>, Error<OperationError>>`; it is the operation's own method when the
event stream is the first media type of its response (decoding JSON could never succeed there), `<operation>_stream`
otherwise. The method sends `Accept: text/event-stream`, turns an error status into the operation's typed
`Error::ResponseError` as the JSON method does, and fails with `Error::Serde` when the response is not an event stream
(the request did not ask for one). `EventStream` implements `futures_core::Stream<Item = Result<Event, Error<E>>>`:

- the `data:` payload of every event decodes to the event schema: a union picks its variant from the payload's `type`
  field (`unionDispatch`), and the SSE `event:` name is not used;
- `data: [DONE]` ends the stream, as does the end of the body; an event without data, a comment and an empty payload
  yield nothing;
- a payload that does not decode yields `Err(Error::Serde(_))` and the stream goes on; a failure reading the body yields
  `Err(Error::Reqwest(_))` and ends it.

`EventStream::from_response` and `EventStream::from_bytes` make a stream of a body read elsewhere, and
`apis::sse::Decoder` is the parser underneath (CR, LF and CRLF line ends, multi-line data, comments, a byte order mark, an
unterminated event dropped at the end), for code that reads events itself. The crate needs `futures-core` and reqwest's
`stream` feature, which the generated `Cargo.toml` declares.

An event schema that models the SSE envelope, objects with required `event` and `data` properties as the Assistants API's
`AssistantStreamEvent`, gets no stream: the `data:` payload holds only the `data` part, and the event name it would take
to rebuild the envelope is what the stream does not use. The generator logs these operations.

## Renderings from the generator comparison

The [comparison with stock `-g rust` and progenitor](docs/comparison/README.md) listed what timvw-rust still rendered
worse than one of them. Two fixes change no public API and are always on; the other four change the API or its
documentation, so each is an option that is off by default. With the options off, openai-client-base and
langfuse-client-base regenerate with only the two always-on differences (checked by regenerating both crates before
and after: 379 and 97 empty `///` lines removed, and langfuse's unused `first_of`/`Attempt`).

The snippets below come from `docs/comparison/spec.yaml`, generated with openai-client-base's options, without and
with each option.

### Unused helpers are left out (always on)

The helper modules were written whole, whatever the crate used. Now an item of `union_dispatch.rs` or
`multipart_helper.rs` that no model or API module names, directly or through another kept item, is left out, and so is
a `use` name only it needed. Both modules are `pub(crate)`, so the public API is the same.

```diff
 // src/models/union_dispatch.rs, in a crate where no union tries several variants for one tag value
-/// A variant to try: its name, and how to decode it.
-pub(crate) type Attempt<U> = (&'static str, fn(Value) -> Result<U, serde_json::Error>);
-
-/// Tries the variants selected in order, and keeps the error of each when none decodes.
-pub(crate) fn first_of<U>(
-    ...
-}
```

Helpers marked `#[allow(dead_code)]` were kept unused on purpose, so they stay. `pruneAllHelpers=true` leaves them out
too:

```diff
 // src/apis/multipart_helper.rs with pruneAllHelpers
-use std::path::{Path, PathBuf};
+use std::path::Path;
-/// Create a multipart Part from a PathBuf
-#[allow(dead_code)]
-pub fn file_part_from_pathbuf(path: PathBuf) -> Result<multipart::Part, std::io::Error> {
-    file_part_from_path(&path)
-}
 (also file_part_from_bytes and add_pathbuf_to_form)
```

openai-client-base: no change (it calls every helper that has no `#[allow(dead_code)]`, and its
`tests/oa06_postprocess_wire.rs` includes `multipart_helper.rs` and calls `add_schema_value_to_form`, which its API
modules call too). langfuse-client-base: `first_of` and `Attempt` go.

### No empty doc comment on enums (always on)

```diff
-/// 
 #[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Hash, Serialize, Deserialize)]
 pub enum FinishReason {
```

An enum with a description keeps it. openai-client-base: 379 lines removed; langfuse-client-base: 97.

### `typedEnumParameters`: enum parameters take the enum

An inline `enum` of a query, path, header or cookie parameter, or of a property of a `multipart/form-data` or
`application/x-www-form-urlencoded` body, becomes a component enum before inline schemas are named:
`<operationId>_<parameter>` for a parameter, `<Schema>_<property>` for a body property (`<operationId>_request_<property>`
when the body is inline), with `_item` for the items of an array. The function takes the enum, which has `Display`
(and `FromStr` with `enumFromStr`):

```diff
-pub async fn list_models(configuration: &configuration::Configuration, limit: Option<i64>, order: Option<&str>, after: Option<&str>) -> ...
+pub async fn list_models(configuration: &configuration::Configuration, limit: Option<i64>, order: Option<models::ListModelsOrder>, after: Option<&str>) -> ...

-pub async fn upload_file(configuration: &configuration::Configuration, file: std::path::PathBuf, purpose: &str) -> ...
+pub async fn upload_file(configuration: &configuration::Configuration, file: std::path::PathBuf, purpose: models::UploadFileRequestPurpose) -> ...
```

```rust
// src/models/list_models_order.rs (new)
pub enum ListModelsOrder {
    #[serde(rename = "asc")]
    Asc,
    #[serde(rename = "desc")]
    Desc,
}
```

The wire is the same: a query parameter and a multipart text part send the enum's `Display`, which is its wire value
(`multipart_form.text("purpose", p_form_purpose.to_string())`). A body property's model field changes type with it:
`UploadFileRequest.purpose` was the enum `upload_file_request::Purpose` and is now `models::UploadFileRequestPurpose`.
Parameters declared on the path item or under `components/parameters` are shared by operations and keep their type.
A name that is already a component's (after Rust normalization) fails generation, naming both. A header or path
parameter that is an array of such enums is joined from the values' `Display` (`.join(",")` alone joins only strings).

### `nullableUnionFields`: a nullable union field can hold `null` (requires `modelUnions`)

With `modelUnions` a field whose union is nullable was `Option<U>`, so a request could not send `null`, and a `null`
that came in went out as an absent field. With the option it renders like any other nullable field: optional ones
`Option<Option<U>>`, required ones `Option<U>` that must be present:

```diff
-    #[serde(rename = "stop", skip_serializing_if = "Option::is_none")]
-    pub stop: Option<models::CreateChatCompletionRequestStop>,
+    #[serde(rename = "stop", default, with = "::serde_with::rust::double_option", skip_serializing_if = "Option::is_none")]
+    pub stop: Option<Option<models::CreateChatCompletionRequestStop>>,
```

`None` leaves the field out, `Some(None)` sends `"stop": null`, `Some(Some(v))` sends the value. A required nullable union
field goes from `U` to `Option<U>`. The union keeps the property's `nullable` when the always-on spec rule hoists an
inline union into a component (that rule dropped it until now; it still does without the option).

### `schemaDefaultDocs`: defaults in the documentation

openapi-generator does not print a property's `default`, and the spec rules remove `default` from component schemas
(the stock generator renders some badly). With the option the default is noted first, in the property's or parameter's
description:

```diff
     #[serde(rename = "n", skip_serializing_if = "Option::is_none")]
     pub n: Option<i32>,
+    /// Defaults to `false`.
     #[serde(rename = "stream", skip_serializing_if = "Option::is_none")]
     pub stream: Option<bool>,
```

The value is the default's JSON. Types do not change: progenitor makes such a field `bool` with `#[serde(default)]`,
which would be a breaking change and would send `false` where the caller set nothing.

### `deprecatedAttributes`: `#[deprecated]` on deprecated fields and schemas

A property or schema with `deprecated: true` gets `#[deprecated]`, so a caller that uses it gets a warning. Operations
already had it (stock). Generated code refers to deprecated items itself (constructors, builders, serde, `mod.rs`), so
every model and API file starts with `#![allow(deprecated)]`, and the crate builds without warnings:

```diff
+#![allow(deprecated)]
 /*
  * Comparison API
...
     #[serde(rename = "response_format", skip_serializing_if = "Option::is_none")]
     pub response_format: Option<models::ResponseFormat>,
+    #[deprecated]
     #[serde(rename = "legacy_user", skip_serializing_if = "Option::is_none")]
     pub legacy_user: Option<String>,
```

A deprecated schema gets `#[deprecated]` above its `#[derive(..)]`. This is not a breaking change in Rust terms, but a
consumer built with `-D warnings` fails where it uses a deprecated item, so turn it on in a client repo together with
any fixes its downstream wrapper needs.

## Supported configuration

`timvw-rust` accepts exactly one configuration. Every option below is applied as the default when
unset, and generation fails with a message naming the option when a different value is given.

| Option | Supported value | Notes |
| --- | --- | --- |
| `library` | `reqwest` | `hyper`, `hyper0x` and `reqwest-trait` are rejected |
| `supportAsync` | `true` | |
| `supportMiddleware` | `true` | `reqwest-middleware` is always wired in |
| `useSingleRequestParameter` | `false` | one function argument per parameter |
| `preferUnsignedInt` | `false` | |
| `bestFitInt` | `false` | |
| `enumNameSuffix` | `` (empty) | |
| `hideGenerationTimestamp` | `true` | |

Our own options:

| Option | Default | Notes |
| --- | --- | --- |
| `bonBuilders` | `true` | derive `bon::Builder` on model structs and put `#[bon::builder]` on API functions |
| `titleNaming` | `true` | let schema titles name, merge and replace types (stock); `false` keeps names from schema keys and paths |
| `webhookModule` | unset | tag for webhook operations, generated into their own API module |
| `webhookModuleReexport` | `false` | `default_api` re-exports the webhook module |
| `stringIdSchemas`, `stringIdProperties`, `stringProperties`, `deepObjectQueryParameters` | empty | lists; entries separated by `\|`, `;` or whitespace on the command line (a comma ends an `--additional-properties` value), or a JSON array in a `-c` config file |
| `resetQueryParameterStyle` | `false` | query parameters not listed in `deepObjectQueryParameters` get the default style (`form`, exploded) |
| `multipartHelper` | `false` | multipart bodies through the generated helper module |
| `fileProperties` | empty | `Schema.property` entries rendered as files |
| `boxOnlyCycles` | `false` | box a model reference only where it closes a cycle of the model graph |
| `defaultOnlyWhereValid` | `false` | `Default` only where its value is one the API would accept |
| `enumFromStr` | `false` | closed enums implement `FromStr` and `TryFrom`; property enums get the `Display` they lack |
| `noStructDisplay` | `false` | structs have no JSON `Display`; API code serializes them with `serde_json::to_string` |
| `integersAsI64` | `false` | integers are `i64` unless `format: int32`; `preferUnsignedInt` and `bestFitInt` stay pinned to `false`, so setting either still fails generation |
| `modelUnions` | `false` | unions render from the model as untagged enums |
| `unionDispatch` | `false` | with `modelUnions`: a union with a tag decodes by that tag |
| `openEnums` | `false` | with `modelUnions`: what a response or webhook payload can hold has a catch-all for values the spec does not list |
| `unionPlanFile` | unset | with `modelUnions`: output-relative path the union plan is written to, as JSON |
| `typedEventStreams` | `false` | a `text/event-stream` response is a method returning a typed stream, see [above](#content-type-methods-and-event-streams); `sse.rs` is only written when some operation streams |
| `contentTypeMethods` | `false` | one method per request body media type and response shape, named with a suffix, see [above](#content-type-methods-and-event-streams) |
| `typedEnumParameters` | `false` | inline enums of parameters and form/multipart body properties become component enums the functions take, see [above](#typedenumparameters-enum-parameters-take-the-enum) |
| `nullableUnionFields` | `false` | with `modelUnions`: a field whose union is nullable is `Option<Option<U>>` (optional) or `Option<U>` (required), so `null` can be sent |
| `schemaDefaultDocs` | `false` | a property's or parameter's `default` is noted in its documentation |
| `deprecatedAttributes` | `false` | `#[deprecated]` on deprecated properties and schemas; generated files start with `#![allow(deprecated)]` |
| `pruneAllHelpers` | `false` | helpers nobody calls are always left out; with this option also those marked `#[allow(dead_code)]` |
| `boxResponseError` | `false` | box the `ResponseError` payload |
| `apiLintAllows` | unset | `#![allow(..)]` contents for API modules and `configuration.rs` |
| `userAgentFromPackage` | `false` | default user agent from `packageName` and the crate version |
| `preprocessedSpecFile` | unset | output-relative path the preprocessed spec is written to, as JSON |

These options are rejected whenever they are set, whatever the value:
`avoidBoxedModels`, `mockall`, `topLevelApiClient`, `withAWSV4Signature`, `supportTokenSource`,
`useBonBuilder` (replaced by `bonBuilders`), `templateDir` (`-t`; the templates are part of the generator).

Example failure:

```
Exception in thread "main" java.lang.IllegalArgumentException: timvw-rust: option 'library' must be 'reqwest' (got 'hyper')
```

## Templates

The stock `rust` templates differ between openapi-generator minor versions, so the overrides live in
one set per supported version, `src/main/resources/timvw-rust/og<major.minor>.0/`, chosen at run
time from the openapi-generator the generator runs in. A set holds only the files that differ from
stock (`lib.mustache`, `model.mustache`, `Cargo.mustache`, `reqwest/api.mustache`,
`reqwest/api_mod.mustache`); everything else falls back to the stock template. Running in an
openapi-generator without a set fails with a message naming the supported versions.

The sets are produced, never edited by hand: `tools/sync-templates.py` copies the stock templates out
of the openapi-generator jars and applies the edits, failing if an anchor no longer matches. To add or
refresh a version:

```bash
python3 tools/sync-templates.py ~/.m2/repository/org/openapitools/openapi-generator/7.27.0/openapi-generator-7.27.0.jar
```

## Development

Requirements: Java 17, Docker, a Rust toolchain (for the petstore smoke test). Maven comes with the
wrapper.

```bash
./mvnw verify
```

Tests cover the configuration checks, the rendering rules on `src/test/resources/milestone1.yaml`
(generated in-process with whichever openapi-generator is on the classpath) and the spec repair. When a new
openapi-generator version gets a template set, run them against it with `./mvnw -Dopenapi-generator.version=<version> verify`.

Check that the generator is picked up by the stock CLI (download the CLI jar into `target/`, it is
git-ignored):

```bash
curl -fsSLo target/openapi-generator-cli-7.27.0.jar https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli/7.27.0/openapi-generator-cli-7.27.0.jar
```

```bash
java -cp target/openapi-generator-timvw-rust-0.2.0-SNAPSHOT.jar:target/openapi-generator-cli-7.27.0.jar org.openapitools.codegen.OpenAPIGenerator list | grep timvw-rust
```

Build the image and smoke-test it on the bundled petstore spec:

```bash
docker build --build-arg OPENAPI_GENERATOR_VERSION=7.27.0 -t timvw-rust:dev .
```

To build against another openapi-generator version, pass the same version to Maven first
(`./mvnw -Dopenapi-generator.version=<version> package`) and to `--build-arg`.

```bash
docker run --rm -v "$PWD:/local" -u "$(id -u):$(id -g)" timvw-rust:dev generate -g timvw-rust -i /local/test-specs/petstore.yaml -o /local/target/petstore --additional-properties=packageName=petstore
```

```bash
cargo build --manifest-path target/petstore/Cargo.toml
```

CI (`.github/workflows/ci.yml`) runs the same steps on every push and pull request, for every
supported openapi-generator version.

## Releasing

1. Make sure `main` is green.
2. Tag and push: `git tag v0.2.0 && git push origin v0.2.0`.
3. The `Release` workflow (`.github/workflows/release.yml`) then:
   - sets the pom version from the tag and runs `./mvnw -Prelease deploy`, which signs the jar,
     sources jar and javadoc jar with GPG and publishes them through the Sonatype Central Portal
     (`central-publishing-maven-plugin`, auto-publish);
   - builds one Docker image per supported openapi-generator version (jar compiled against that
     version) and pushes `ghcr.io/timvw/openapi-generator-timvw-rust:<version>-og<openapi-generator>`;
     the default openapi-generator version also gets `:<version>` and `:latest`.

Repository secrets required: `CENTRAL_USERNAME`, `CENTRAL_PASSWORD` (Central Portal user token),
`GPG_PRIVATE_KEY` (ASCII-armoured private key), `GPG_PASSPHRASE`. The image push uses the
workflow's `GITHUB_TOKEN`.

The pom's version on `main` stays a `-SNAPSHOT`; the release workflow never commits.

## Layout

```
pom.xml                                  be.timvw:openapi-generator-timvw-rust, Java 17
src/main/java/be/timvw/openapi/rust/     TimvwRustCodegen (configuration, options), RustDoc (URL wrapping),
                                         SpecNormalizer (spec repair), HelperPruning (unused helpers),
                                         ExtraRules (the opt-in renderings from the generator comparison)
src/main/resources/timvw-rust/og*/       template overrides, one set per openapi-generator minor version
src/main/resources/META-INF/services/    registers the generator with openapi-generator
src/test/java/                           JUnit 5 tests; src/test/resources/milestone1.yaml is the rendering fixture
tools/sync-templates.py                  regenerates the template sets from the stock jars
docs/type-mapping.md                     each OpenAPI construct and its Rust rendering
docs/mapping/                            the same as rules, one file per OpenAPI Specification object;
                                         MappingDocsTest checks that each rule names tests that exist
docs/roadmap.md                          the plan: document the mapping, decide changes, conform
docs/comparison/                         stock -g rust vs timvw-rust vs progenitor on one spec (generate.sh)
test-specs/petstore.yaml                 OpenAPI 3.0 smoke-test spec
Dockerfile                               eclipse-temurin:17-jre + both jars in /opt/gen
.github/workflows/                       ci.yml, release.yml
```

## License

Licensed under either of [Apache License, Version 2.0](LICENSE-APACHE) or [MIT license](LICENSE-MIT)
at your option.
