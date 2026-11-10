# History: replacing the client scripts

`timvw-rust` was built to replace the post-processing scripts that
[openai-client-base](https://github.com/genai-rs/openai-client-base) and
[langfuse-client-base](https://github.com/genai-rs/langfuse-client-base) ran on top of stock `-g rust`, by moving every
fix into the generator. This page keeps that history: what each rendering replaced. What the generator renders today
is the [mapping](mapping/README.md).

**Status:** openai-client-base and langfuse-client-base generate without scripts (Milestones 2 and 3, up to 0.17),
the plugin owns its Rust layer (0.20), and 0.21 renders the documented [mapping](mapping/README.md) with the changes
of [decisions.md](decisions.md). The table [below](#what-the-generator-renders-differently-from--g-rust) records what
each rendering replaced in the client repositories' scripts.

## Unreleased

- An inline enum two or more lists or maps deep (`additionalProperties: {additionalProperties: {enum: [..]}}`, a list of
  such maps, a map of lists of lists, ...) is typed at every level, e.g. `HashMap<String, HashMap<String, <Enum>>>`; it
  was typed one level deep only (`HashMap<String, <Enum>>`), which the JSON does not decode to. `validate()` checks the
  size keywords of every list and map that holds it, and the field documentation lists them. A list of lists or maps of
  an inline enum with `uniqueItems` is a `Vec` whose uniqueness `validate()` checks (a list of maps was a `HashSet`,
  which did not compile: a map is not `Hash`). The validation note of a list in a map reads "each item of a value" (it
  read "of an value"). The model docs show the field's Rust type for every inline enum (they showed `Inner` for nested
  ones). A client whose spec has such a field gets the decoding type. `lib.rs` allows `clippy::type_complexity`, which
  such a type three levels deep trips.
- List items and map values that can be null are `Option`s at any depth. This covers `nullable: true` (3.0),
  `type: [T, 'null']` and a `oneOf`/`anyOf` with a `null` branch (3.1), and a `$ref` to a nullable schema, so the
  generator renders `HashMap<String, Option<String>>`, `Vec<Option<T>>` and `Vec<Option<<Enum>>>` for an inline enum.
  Before, the generator ignored the nullability, so `{"a": null}` or `[null]` failed to decode. `None` serializes as
  `null`, and `validate()` skips it. A query, path, header or cookie parameter keeps plain items. A client whose spec
  has such items gets the `Option`: code that builds or reads those lists and maps wraps or unwraps the items.

## 0.21.0 (breaking)

The release of the [mapping decisions](decisions.md): what roadmap step 2 decided, implemented under
[step 3](roadmap.md#3-make-the-generator-conform). The [migration guide](guides/migration.md) has every change with
the code before and after, and the steps for a client repository.

### Configuration

- The 11 baseline options are no longer options
  ([decision 12](decisions.md#12-option-defaults-are-stock-not-the-baseline)): `boxOnlyCycles`, `defaultOnlyWhereValid`,
  `enumFromStr`, `noStructDisplay`, `integersAsI64`, `qualifiedInlineNames`, `modelUnions`, `unionDispatch`,
  `openEnums`, `schemaDefaultDocs` and `nullableUnionFields`. Their `true` rendering is always applied, and setting one,
  to any value, fails generation as not configurable. Their `false` renderings (the stock unions, boxing every
  reference, `Default` everywhere, `i32` integers, the stock inline names, closed response enums, ...) are gone. A
  client that set all 11 to `true` removes them from its configuration; its output does not change. A client that left
  any of them off gets the mapping's rendering.
- The error is always boxed (decision 4, follow-up): `Error::ResponseError` holds `Box<ResponseContent<T>>`, since
  with the headers an unboxed error is over clippy's `result_large_err` limit in every function. `boxResponseError`
  is no longer an option: setting it, to any value, fails generation as not configurable. openai-client-base removes
  `"boxResponseError": true` from its configuration; its output does not change. langfuse-client-base, which did not
  set it, gets the boxed error: code that builds `Error::ResponseError(content)` wraps it (`Box::new(content)` or
  `content.into()`); code that matches `Error::ResponseError(response)` and reads its fields is unchanged (the `Box`
  derefs), but one that moves the `ResponseContent` out gets a `Box` (`*response`).
- An unknown option fails generation ([decision 10](decisions.md#10-unknown-generator-options-are-ignored)): a key
  of `--additional-properties` or of a `-c` file that is neither a timvw-rust option, nor one of openapi-generator's
  general options, nor a key openapi-generator sets itself, was ignored and now fails with every unknown key and, for
  each, the closest option (`did you mean 'bonBuilders'?`). A client whose configuration only holds options sees no
  change; one with a misspelt or stale key removes or corrects it.

### Function signatures and models

- A request body the function only serializes is borrowed ([decision 2](decisions.md#2-request-bodies-by-reference)):
  `create_item(configuration, item: models::Item)` becomes `item: &models::Item` (a union body too, an array body
  `&[T]`, a map or `serde_json::Value` body a reference, an optional or nullable one `Option<&T>`), in every method of
  an operation, the `contentTypeMethods` and `typedEventStreams` methods and webhooks included. Numbers, booleans, dates
  and date-times (`Copy`), strings (`&str`), multipart parts, form fields and files (`PathBuf`) are unchanged. A body
  whose type is `String` (a string enum without `typedEnumParameters`, `format: byte`) is now `&str`. A caller passes
  `&request` instead of `request` (`.create_item(&request)`, `.maybe_create_item(Some(&request))`) and keeps its value;
  the bytes sent do not change.
- A `format: uuid` parameter is `uuid::Uuid` ([decision 6](decisions.md#6-uuid-parameters-are-str)): a path, query
  or header parameter or form field took `&str` (an array already took `Vec<uuid::Uuid>`) and now takes `uuid::Uuid`
  by value (`Option<uuid::Uuid>` when optional), as a model property does. It is sent hyphenated, as before. A client
  passes a `uuid::Uuid` (`Uuid::parse_str(s)?`) instead of a string. Arrays of uuids in a path or header, which did
  not compile, now join their items. A uuid or chrono type only in a response (or only in an array parameter) now
  makes the crate depend on `uuid` or `chrono`; such a crate did not compile.
- A map whose values are an inline enum (`additionalProperties: {type: string, enum: [..]}`) is
  `HashMap<String, <Enum>>` (a map of lists of it `HashMap<String, Vec<<Enum>>>`, a list of such maps
  `Vec<HashMap<String, <Enum>>>`); it was the enum itself (or a list of it), which the JSON does not decode to. The
  field's type changes,
  and `validate()` now checks its `minProperties`/`maxProperties` (its enum values have nothing to check).
- A discriminated union whose branches share one `enum` of every tag value (`kind: [cat, dog]` on both `Cat` and
  `Dog`) dispatches each value to the branch the `mapping` names (`cat` decodes `Cat`, `dog` decodes `Dog`), instead
  of trying both in order for either value: a branch's own tag values select it unless the mapping gives them to
  another branch. The unused `decode` helper of `src/models/union_dispatch.rs` is now left
  out when only `first_of` is used (clippy `dead_code`).

### Errors

- The error variant comes from the status, not the body's shape
  ([decision 5](decisions.md#5-error-variant-chosen-by-body-shape-not-status)): `entity` holds the variant of the
  response with the status's exact code, else of its range (`4XX`), else `DefaultResponse`, with the body decoded as
  that response's type; a body that does not decode as it is `UnknownValue`. Before, the body went into the first
  variant whose type accepted it, so a 404 with the 400's type came back as `Status400`. The enum's type and variants
  do not change, so nothing stops compiling; a client that matched on a `Status…` variant now gets the one of the
  status, and one that relied on a body of another response's type decoding (a 500 body in `Status400`) gets
  `UnknownValue`.
- An error has the response's headers ([decision 4](decisions.md#4-response-headers-cannot-be-read)):
  `ResponseContent` gets `headers: reqwest::header::HeaderMap`, filled on every error status (also of event-stream and
  `contentTypeMethods` methods), so `retry-after`, `x-request-id` and rate-limit headers can be read. Code that
  builds a `ResponseContent` with a struct literal adds `headers` (e.g. `reqwest::header::HeaderMap::new()`). With
  `supportMultipleResponses`, the success `ResponseContent` has the headers too. `{:?}` of an error now prints every
  response header, which can include `set-cookie` or tokens: a client that logs errors with `{:?}` may want to log
  `Display` (only the status) or redact; `Display` is unchanged.
- A `text/plain` error response holds its body as it is: a `string` error response (or `default`) whose media types
  are text and none JSON was decoded as JSON, so a plain-text body (`bad input`) gave no entity; it now gives
  `Status400("bad input")` (`DefaultResponse(..)` for the default), as decision 5 picks the variant by status (also in
  the `_response` function of `responseFunctions`). A client that read such an error from `content` can match the
  variant.

### On the wire

- `date-time` parameters are sent in RFC 3339
  ([decision 11](decisions.md#11-date-time-parameters-are-sent-in-display-form)): with `useChrono` (the default), a `date-time`
  path, query or header parameter or form field (urlencoded body, multipart text part), or each item of an array of
  them, was written with chrono's `Display` (`2026-01-02 03:04:05 +01:00`) and is now written with `to_rfc3339()`
  (`2026-01-02T03:04:05+01:00`), URL-encoded in the path and the query. A `date` stays `YYYY-MM-DD`. An array of `date`
  or `date-time` in a path or header, which did not compile, now joins its items. Signatures do not change; a client
  that built such a request by hand to get RFC 3339 can call the generated function instead.
- Cookie parameters are sent ([decision 3](decisions.md#3-cookie-parameters-are-not-sent)): the argument was
  accepted and never used. The cookie parameters that are set now go out in one `Cookie` header (`a=1; b=2`), each
  value percent-encoded where it is not an RFC 6265 cookie-octet, an exploded array as a pair per item. An api key
  with `in: cookie`, which was not sent either, joins the same header. A map cookie or header parameter
  (`additionalProperties`), which did not compile, is sent as its JSON. Signatures do not change. The generated header
  replaces a client default `Cookie` header on operations that send cookies; a middleware that adds cookies must merge
  into the existing header rather than append a second one.
- Parameter serialization fixes (pre-existing bugs found in reviews):
  - A query parameter the spec declares `style: deepObject` (an object or a `$ref` to one) keeps its type and is
    sent as `name[field]=value`; it was turned into `&str` and then dropped, or, with `resetQueryParameterStyle`,
    sent as one `name=<text>` string (openai-client-base's vault `metadata`).
  - A model parameter listed in `deepObjectQueryParameters` is sent as `name[field]=value`; it went with flat
    `field=value` keys (openai-client-base's audit log `effective_at`). A required listed one is sent in `deepObject`
    style too (it went as its JSON).
  - `pipeDelimited` and `spaceDelimited` query arrays are joined with `|` and a space (they were joined with `,`),
    or repeated with `explode: true`.
  - An array of arrays (or of maps) in the query is sent as each item's JSON; it did not compile.
  - Path arrays URL-encode each item, and a path value encodes a space as `%20` (it was `+`); path and header arrays
    of integers, uuids, enums and structs compile and join their items' text (structs their JSON). A single enum,
    struct or map path value is URL-encoded too (a struct or map as its JSON).
  - An urlencoded form body is a list of pairs in the spec's order (it was a `HashMap`): an array field is a pair
    per item (`style: form, explode: true`) unless the `encoding` says `explode: false` (joined with `,`, or `|` and a
    space for a `pipeDelimited` or `spaceDelimited` encoding), and a required nullable
    field left `None` is sent empty (it did not compile).
  - A JSON body that is an array of uuids is named `uuid` (it was `uuid_colon_colon_uuid`).
  - A model cycle through an inline union is boxed (E0072).
  - The generated parameter code passes `clippy -D warnings`: no `into_iter()` on a reference, no redundant
    closure.
  A client passing a value to a declared `deepObject` parameter now passes the typed value instead of a string; a
  server reading form arrays as one `,`-joined field reads repeated fields instead.

### Fixes

- A query parameter whose type is a `oneOf` union (openai-client-base's vault `status`, `VaultStatusFilterParam`)
  goes out as the union's `Display`: a string variant unquoted (`status=active`, it was `status=%22active%22`),
  numbers and booleans as text, other variants as their JSON, as a path, header or cookie parameter already did.
  A union's `Display` writes a uuid variant hyphenated, a date `YYYY-MM-DD` and a date-time in RFC 3339 (they were
  JSON-quoted, and a date-time was chrono's `Display`). A required query, header or cookie parameter of a nullable
  union takes an `Option` (it was the union itself).
- A `uuid`, `date` or `date-time` type only in a union variant makes the crate depend on `uuid` or `chrono`; such a
  crate did not compile.
- Two properties of a model that become the same Rust field (`foo-bar` and `foo_bar`) fail generation with both names:
  `timvw-rust: model Thing: properties 'foo-bar' and 'foo_bar' both become the Rust field foo_bar; rename one in the
  spec or map one with --name-mappings`. Before, the crate did not compile (E0124).
- Generation logs only what a user can act on: identifier renames (`[Done]` as
  `LeftSquareBracketDoneRightSquareBracket`, `application/json` as `ApplicationSlashJson`) log at debug, and the
  fields declared on a union that a variant does not carry are one warning with their count (each at debug).
  openai-client-base's generation log goes from 5331 to 71 warnings and langfuse-client-base's from 1472 to 4; most
  of what remains is openapi-generator's own.

### New

- Every model implements a generated `Validate` trait ([decision 9](decisions.md#9-validation-keywords-are-not-checked),
  with [decision 8](decisions.md#8-uniqueitems-is-a-vec)): `validate()` checks `minLength`, `maxLength`, `pattern`,
  `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, `multipleOf`, `minItems`, `maxItems`, `uniqueItems` (by
  equality; arrays stay `Vec`), `minProperties` and `maxProperties`, recursively, and returns every violation:
  `models::validation::ValidationErrors`, a non-empty list of `ValidationError` (path, rule, offending value) in field
  order, nested values depth-first, map entries by rendered key, with `len()`, `iter()`, `into_vec()`, `IntoIterator`
  and a `Display` of one line per error. A hand-written impl implements `validate_into(path, errors)`. Nothing generated
  calls it, and `Error` is unchanged. The crate gains `src/models/validation.rs` (declared in `models/mod.rs`, so a
  client's own `lib.rs` needs no change), an `impl crate::models::validation::Validate` per model, and a `regex`
  dependency when the spec has a `pattern`; every constrained field's documentation lists its constraints. A client that
  wants validation calls `validate()` (with `use <crate>::models::validation::Validate;`); one that does not want the
  code sets `validateMethods=false`. A client that keeps its own `Cargo.toml` (openai-client-base) adds `regex = "1"`
  when its spec has a `pattern`. The generator's own warnings (a skipped pattern, a union field that is not rendered,
  ...) now show in the openapi-generator CLI and Docker image, which only print `org.openapitools` loggers; renames that
  are expected (a keyword as `r#type`, a Rust type the base generator uses as a variable name) log at debug instead of
  warn.
- `responseFunctions` (new option, off by default;
  [decision 1](decisions.md#1-response-functions-returning-apiresponset)): every operation whose success is a JSON body, text or
  nothing also gets `<operation>_response`, which sends the same request and fails with the same typed error but returns
  `apis::ApiResponse<T>`: the `status`, the `headers` and the undecoded body, with `json()` (the generated type),
  `json_as::<U>()` (a type of the caller's, owned), `json_borrowed::<U>()` (borrowing from the body) and `text()`.
  Binary responses, event streams and webhooks get none. A twin whose name another operation of the module has fails
  generation. Without the option nothing changes; see the [guide](guides/response-functions.md).

## What the generator renders differently from `-g rust`

For a side-by-side view of stock `-g rust`, timvw-rust and progenitor on one spec (generated types, what each
decodes, clippy, operations), see [docs/comparison](comparison/README.md).

| Rule | Replaces (langfuse-client-base) |
| --- | --- |
| `#[derive(..., bon::Builder)]` on every model struct, `#[bon::builder]` on every API function, `bon = "^3"` in `Cargo.toml` (`bonBuilders`, default on) | `add_bon_builders.py`, `patch_generated.rs.sh` |
| `src/lib.rs` starts with `#![doc = include_str!("../README.md")]` | lib.rs injection in `generate-openapi-client.sh` |
| Bare URLs in `///` doc comments are wrapped in `<...>` (rustdoc's `bare_urls` lint); markdown docs are untouched | `patch_generated.rs.sh` |
| `parse_deep_object` returns `(prefix, value)` for non-object values instead of panicking | `patch_generated.rs.sh` |
| `type: integer` with `format: float`/`double` is read as `type: number` (logged) | `normalize_openapi_schema.py` |
| `titleNaming=false`: schema titles never name, merge or replace types. openapi-generator 7.2x merges titled component schemas of the same shape into one model, swaps an inline schema for a titled component that matches it, and names inline array items after the array's title (`ArrayOfItemsInner5`); with the option off, the generator removes component and array titles before running the inline-model resolver (which it therefore runs itself), so names come from schema keys and property paths as on 7.15. Default `true` (stock behaviour). | the openapi-generator 7.25 upgrade of openai-client-base (95 public types kept) |
| An optional multipart text part whose schema is a referenced string enum carries the enum's wire value (`param_value.to_string()`); stock 7.25 JSON-encodes every non-primitive part, so `verbose_json` arrived as `"verbose_json"`. Objects still travel as JSON. | the openapi-generator 7.25 upgrade of openai-client-base |
| A `oneOf` whose branches render to the same variant (two primitive branches with the same `title` give two `Text(String)`) kept one variant and logged the drop; since unions always render from the model, the enum branch is a model of its own and both variants stay. | `fix_generator_artifacts.py` (openai-client-base) |
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
| `modelUnions=true`: unions render from the model (`UnionRules`). Every `oneOf` and `anyOf`, with or without a discriminator, is a `#[serde(untagged)]` enum, and a model that is `allOf` one union gets that union's variants; a `type: null` branch is no variant, the union is an `Option` where it is nullable. A variant is named after its branch: discriminator mapping key, referenced schema (minus the union's name when the branch was inline), title, then type. A string enum branch becomes a model `<Union><Variant>Enum`. Unions have `Display` (a string, enum or union variant prints itself, anything else its JSON, so a text part of union type goes out as plain text), and with a string variant `new_text`, `new_<vec variant>`, `From<String>` and `From<&str>`. `Default` is rendered unless `defaultOnlyWhereValid` is on. Structs keep every field, tag included. The variants are ordered for untagged decoding, see [`schema.oneOf.variant-order`](mapping/schema.md#schemaoneofvariant-order). Without `qualifiedInlineNames`, an inline schema with the title of the schema it stands in gets a title of its own (`<title> object`, `<title> <property>`), or the resolver makes it refer to itself. `unionPlanFile=<output-relative path>`: the variants of every union and the schemas they refer to are written there as JSON; `tools/check-union-samples.py` uses it to decode one sample per branch of a generated crate. | `fix_untagged_unions.py` and the other scripts named, and the bugs of their name-based rewriting. A new rendering, which changes the public API |
| `unionDispatch=true`, with `modelUnions`: a union with a tag decodes the variant that tag selects, and serializes as before; see [`discriminator.propertyName`](mapping/discriminator.md#discriminatorpropertyname). Set without `modelUnions` it fails generation. | nothing: decoding that used to try every variant, and the error `data did not match any variant` |
| `openEnums=true`, with `modelUnions`: a value enum or tagged union that a response or webhook payload can hold accepts values the spec does not list; see [`schema.enum.open`](mapping/schema.md#schemaenumopen). Set without `modelUnions` it fails generation. | nothing: one new value failing the whole response |
| `boxResponseError=true` (always on since decision 4; no longer an option): `Error::ResponseError` holds `Box<ResponseContent<T>>` and every API function builds it with `Box::new` (clippy `result_large_err`). `apiLintAllows=<lints>`: API modules and `configuration.rs` start with `#![allow(<lints>)]`. `userAgentFromPackage=true`: the default user agent is `<packageName>/<crate version>` (`env!("CARGO_PKG_VERSION")`) instead of `OpenAPI-Generator/<spec version>/rust`. | `box_response_errors.py`, `fix_clippy_warnings.py`, the user-agent edit of `patch_generated.rs.sh` (openai-client-base) |
| Helpers nobody calls are left out: `src/models/union_dispatch.rs` without `first_of` and `Attempt` when no union tries several variants, `src/apis/multipart_helper.rs` without the functions no API module calls. Items marked `#[allow(dead_code)]` stay unless `pruneAllHelpers=true`. Both modules are `pub(crate)`. Always on; see [the comparison](comparison/README.md#renderings-after-the-comparison). | nothing: removes `dead_code` warnings |
| An enum without a description has no empty `///` above it (clippy `empty_docs`). Always on. | nothing: removes `empty_docs` warnings |
| `typedEnumParameters`, `nullableUnionFields`, `schemaDefaultDocs`, `deprecatedAttributes`: typed enum parameters, `null` for nullable union fields, schema defaults in the docs, `#[deprecated]`. Off by default; see [the comparison](comparison/README.md#renderings-after-the-comparison). | nothing: closes gaps the [generator comparison](comparison/README.md) found |
| `preprocessedSpecFile=<output-relative path>`: the spec as it stands after the rules above is written there as JSON, for scripts that still read it and for debugging. | the `target/specs/l2_rust_compatible.*` files the scripts produced |

Spec-level decisions stay in the client repos. Spec validation runs in the CLI before any generator
code, so a keyword the 3.0 validator rejects (Langfuse uses the 3.1 `const`) cannot be repaired here;
langfuse-client-base drops it while composing the generator input (`prepare_compatible_spec.py`, which
also adds the v3 compatibility layer). Any other invalid construct fails generation and is fixed upstream.

## From a subclass of `-g rust` to its own Rust layer

Up to 0.19.0 `TimvwRustCodegen` extended openapi-generator's `RustClientCodegen` and rendered the stock `rust`
templates, overriding some of them per openapi-generator minor version (`timvw-rust/og7.27.0/`, produced by
`tools/sync-templates.py` from the stock jar); every other template fell back to the stock one in the
openapi-generator jar, and a run in an openapi-generator without a template set failed.

After 0.19.0 the plugin owns its Rust side. It still builds on openapi-generator (`DefaultCodegen`, the parser,
`DefaultGenerator`, the CLI), but:

- what it used of `AbstractRustCodegen` and `RustClientCodegen` 7.27.0 (type mapping, identifier sanitizing and keyword
  escaping, the `oneOf` variant names, the model and operation post-processing, the supporting files, and the crate
  options `packageName`, `packageVersion`, `useChrono`, `useSerdePathToError`, `supportMultipleResponses` and
  `reqwestDefaultFeatures`, with their names and defaults) is ported into `RustCodegenBase`;
- every template is in `src/main/resources/timvw-rust/`, one set whatever the openapi-generator version, with the
  branches for the clients it never generates (hyper, hyper0x, reqwest-trait, blocking, without middleware, the
  single request parameter, token source, AWS v4 signatures, mockall) removed; `tools/sync-templates.py` is gone;
- the options of those clients stay rejected, `--library` other than `reqwest` is rejected, and an operation with
  `x-group-parameters` fails generation (with the single request parameter fixed off, stock rendered code for it that
  did not compile);
- a run in an openapi-generator minor version other than the one in the pom logs a warning instead of failing.

The output did not change: every spec in `test-specs/` and `src/test/resources/` under four option sets (none, the
baseline, the baseline with openai-client-base's and langfuse-client-base's extra options, and the crate options), and
both client repos, generate byte-identical with 0.19.0's image.
