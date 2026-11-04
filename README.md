# openapi-generator-timvw-rust

A custom [openapi-generator](https://openapi-generator.tech) Rust client generator, `timvw-rust`,
built as a Java subclass of the stock `rust` generator (`RustClientCodegen`).

Its purpose is to replace the post-processing scripts that
[openai-client-base](https://github.com/genai-rs/openai-client-base) and
[langfuse-client-base](https://github.com/genai-rs/langfuse-client-base) run on top of `-g rust`,
by moving every fix into the generator itself. The design, type rules and delivery plan live in the
design doc: [Custom Rust generator: rendering design](https://claude.ai/artifact/ABar8Up91gjVMDHgHL1GE3).

**Status: Milestone 3 (API cleanup), step 3.1.** Milestone 2 is complete: openai-client-base generates without scripts. The generator renders what stock `-g rust` renders
plus the conventions below, which langfuse-client-base used to patch in after generation (its public API is
byte-identical between the scripted pipeline and this generator), and the `titleNaming` option that keeps
openai-client-base's type names stable across the openapi-generator 7.15 to 7.25 upgrade.

## Artifacts

| What | Where |
| --- | --- |
| Maven | `be.timvw:openapi-generator-timvw-rust` on Maven Central |
| Docker | `ghcr.io/timvw/openapi-generator-timvw-rust:<version>-og<openapi-generator version>` |
| Generator name | `timvw-rust` (`-g timvw-rust`) |
| Supported openapi-generator | 7.15.x and 7.25.x (one image per version) |

The Docker image is a drop-in replacement for `openapitools/openapi-generator-cli`: same arguments,
same `/local` mount convention, same `JAVA_OPTS` environment variable.

## Usage in the client repos

Images are published per openapi-generator version, tagged `<generator version>-og<openapi-generator version>`
(for example `0.2.0-og7.15.0` and `0.2.0-og7.25.0`). The plain `<generator version>` and `latest` tags
point at the newest supported openapi-generator (7.25.0). A client repo pins the openapi-generator
version its pipeline is tuned for; both client repos now use `-og7.25.0`, and `-og7.15.0` stays published
for pipelines that have not upgraded yet.

Replace the image in `scripts/generate.sh`:

```bash
docker run --rm -v "$PWD:/local" -u "$(id -u):$(id -g)" \
  ghcr.io/timvw/openapi-generator-timvw-rust:0.2.0-og7.25.0 generate \
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
| `stringIdSchemas`: component schemas rendered as `String`. `stringIdProperties`: property names (`name` or `name=fallback_description`) rendered as `String` wherever they reference one of those schemas or are a union, directly or inherited through `allOf`. `stringProperties`: `Schema.property` entries rendered as a bare `String`. `jsonValueSchemas`: component schemas rendered as `serde_json::Value`. | `fix_model_fields.py`, `simplified_schemas.py`, `patch_spec_rust_compat.py` (openai-client-base) |
| Object-typed query parameters (inline or a reference to an object schema) are sent as strings, except those listed in `deepObjectQueryParameters` (`<path>?<name>`), which are sent in deepObject style. `resetQueryParameterStyle=true` gives the other query parameters the default style (`form`, exploded), so arrays go as repeated parameters whatever the spec says (openai-client-base's current wire format). | `patch_spec_rust_compat.py` (openai-client-base) |
| `legacyModelRewrites=true` (transitional): after generation, the rendered models go through Java ports of openai-client-base's model scripts, in the order its pipeline ran them (`be.timvw.openapi.rust.legacy`): untagged and discriminated union enums, JSON `Display` impls, the `Default` derive and impl analysis, recursive boxing, helper-impl cleanup, doc-link repair and unused-import pruning. The output is byte-identical to the scripted pipeline (`LegacyOracleTest` checks every step against snapshots of it). Milestone 3 replaces these text rewrites with model-level rules. | `patch_generated.rs.sh`, `add_bon_builders.py`, `fix_generated_code.py`, `fix_empty_enums.py`, `fix_enum_attributes.py`, `fix_tool_enum_tagging.py`, `fix_untagged_unions.py`, `fix_helper_impl_mismatches.py`, `fix_default_issues.py`, `fix_generator_artifacts.py`, `fix_unused_imports.py` (openai-client-base) |
| `multipartHelper=true`: multipart bodies go through a generated `src/apis/multipart_helper.rs` (declared in `apis/mod.rs`, imported where used). Files are read into a part named after the file; an optional array (form/explode, the default) sends one part per item and an optional object one JSON part; an `encoding` `contentType` is applied to scalar and object parts. Other parameters keep the stock rendering. `fileProperties=<Schema.property,..>`: those properties become files (`type: string, format: binary`). A multipart model parameter's type is qualified once (stock renders `models::models::X`). | `patch_multipart.sh`, `fix_file_upload_todos.py`, `fix_multipart_serialization.py`, the `models::models::` edit of `patch_generated.rs.sh` (openai-client-base) |
| `boxOnlyCycles=true`: a model reference is `Box<T>` only when its target is in the same strongly connected component of the model graph as the model holding it, so every cycle goes through a box and nothing else does. Struct fields (required or `Option`) and enum variant payloads are edges; `Vec` and map items are not, and are never boxed. Without the option every model reference is boxed, as in stock `-g rust`. | nothing: a new rendering, which changes the public API (openai-client-base 0.18.0) |
| `defaultOnlyWhereValid=true`: a `Default` value must be one the API would accept. A struct derives `Default` only when it has no required field; a required nullable field counts as required. An all-optional struct keeps it whatever its field types, since `Option<T>` does not need `T: Default`. No enum implements `Default`: not value enums (the stock impl picks the first variant, arbitrarily) and not unions, also not the ones `legacyModelRewrites` writes. Schema defaults stay ignored. The decision comes from the struct's own required fields (`x-rust-derive-default`), so under the option `legacyModelRewrites` skips its text analysis of `Default`, which works on bare type names: a non-`Default` type in one file stripped the derive from every struct with a local field of that name elsewhere. Without the option `Default` is rendered as before. `tools/check-default-rule.py` holds every `Default` of a generated crate against the rule. | `fix_default_issues.py` and the `Default` analysis in `fix_generated_code.py` (their ports), and the name collision they caused. A new rendering, which changes the public API |
| `enumFromStr=true`: every closed enum (all variants unit variants, not untagged or internally tagged, so no union) implements `FromStr` and `TryFrom<&str>`, `TryFrom<&String>` and `TryFrom<String>`, accepting exactly what its `Display` prints and rejecting everything else with `models::ParseEnumError`, one error type written once to `src/models/parse_enum_error.rs`. This covers string and integer enums, property enums inside a struct's file and the enums `legacyModelRewrites` writes: the impls are added after the rewrites ran, from the final text. A property enum has no `Display` of its own, so it gets one that prints its serde names. A schema named `ParseEnumError` fails generation. | nothing: an additive API change |
| `noStructDisplay=true`: structs have no JSON `Display` (`legacyModelRewrites` adds one otherwise). API code that relied on it serializes explicitly with `serde_json::to_string`: path, query and header parameters and form and multipart text parts of struct type, and arrays of structs in the query, a form or a multipart part. The bytes on the wire are the same, and a serialization failure is now `Error::Serde` instead of a panic. Enums keep their `Display`, unions included. Generation fails if a rewrite turned such a parameter's struct into a union, whose `Display` is not its JSON. | nothing: removes `to_string()` on structs from the public API |
| `integersAsI64=true`: an integer is `i64` unless the spec says `format: int32`, wherever it appears: model fields, `Vec` and map items, parameters, multipart fields, responses, `new()` parameters, builders and union variants. Formats other than `int32` (`int64`, `unixtime`, absent, unknown) give `i64`. Spec bounds never pick a type, and nothing is inferred as unsigned or `NonZero`. `number` is untouched. Integer-valued enums keep `#[repr(i64)]`. Without the option an integer without a format, or with one other than `int64`, is `i32`, as in stock `-g rust`. | nothing: a new rendering, which changes the public API |
| `qualifiedInlineNames=true`: an inline schema that becomes a model keeps its title as its name only when that title is unique: its Rust name matches no component and no other model that will be generated, and every inline schema with that name has the same shape (those share one model). Otherwise all of them, like untitled schemas, are named after where they stand: property `p` of `P` is `P_p` (Rust `PP`), array items `P_inner`, map values `P_value`, a `oneOf`/`anyOf` branch `P_<title>` or, untitled, `P_oneOf`/`P_anyOf` (branches of one union that would share a name get their position), `not` is `P_not`, and an operation's body, parameter or response is `<op>_request`, `<op>_<param>_parameter`, `<op>_<code>_response` (with the media type when several in one body need a model). `P` is the name of the enclosing schema, however it got it. The `Object<i>` titles the generator gives untitled branches never name a model. The outcome does not depend on the order of the spec. Generation then fails, naming both schemas and locations, when two structurally different schemas would produce the same Rust model or file; names are compared after Rust normalization, so `Static_Chunking_Strategy` and `StaticChunkingStrategy` are the same model. Schemas that differ only in annotations (title, description, examples, external docs, `deprecated`, `x-*`), or in the names of references to equal schemas, may share a model. Stock numbers an inline schema whose title is taken (`Object0_17`), lets a schema nested in one with the same title overwrite it (which then references itself), and writes a title that normalizes to a component's name over that component's file. Default `false`. With the option on, the `<Schema><Property>` rename of inline object titles above no longer runs: nothing needs it. The check covers what the generator models, not the text rewrites of `legacyModelRewrites`, which run afterwards. | nothing: a new rendering, which renames types of the public API |
| `modelUnions=true`: unions render from the model (`UnionRules`). Every `oneOf` and `anyOf`, with or without a discriminator, is a `#[serde(untagged)]` enum, and a model that is `allOf` one union gets that union's variants; a `type: null` branch is no variant, the union is an `Option` where it is nullable. A variant is named after its branch: discriminator mapping key, referenced schema (minus the union's name when the branch was inline), title, then type. A string enum branch becomes a model `<Union><Variant>Enum`. Unions have `Display` (a string, enum or union variant prints itself, anything else its JSON, so a text part of union type goes out as plain text), and with a string variant `new_text`, `new_<vec variant>`, `From<String>` and `From<&str>`. `Default` is rendered unless `defaultOnlyWhereValid` is on. Structs keep every field, tag included. The variants are ordered for untagged decoding, see [below](#union-variant-order). Under this option `legacyModelRewrites` leaves out `fix_empty_enums`, `fix_enum_attributes`, `fix_tool_enum_tagging`, `fix_untagged_unions`, `fix_helper_impl_mismatches` and `fix_generator_artifacts`. Without `qualifiedInlineNames`, an inline schema with the title of the schema it stands in gets a title of its own (`<title> object`, `<title> <property>`), or the resolver makes it refer to itself. `unionPlanFile=<output-relative path>`: the variants of every union and the schemas they refer to are written there as JSON; `tools/check-union-samples.py` uses it to decode one sample per branch of a generated crate. | `fix_untagged_unions.py` and the other scripts named, and the bugs of their name-based rewriting. A new rendering, which changes the public API |
| `boxResponseError=true`: `Error::ResponseError` holds `Box<ResponseContent<T>>` and every API function builds it with `Box::new` (clippy `result_large_err`). `apiLintAllows=<lints>`: API modules and `configuration.rs` start with `#![allow(<lints>)]`. `userAgentFromPackage=true`: the default user agent is `<packageName>/<crate version>` (`env!("CARGO_PKG_VERSION")`) instead of `OpenAPI-Generator/<spec version>/rust`. | `box_response_errors.py`, `fix_clippy_warnings.py`, the user-agent edit of `patch_generated.rs.sh` (openai-client-base) |
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
`tools/check-union-samples.py` lists them. They wait for tag dispatch, which will take the place of `serde(untagged)` in
`tools/static/model_union.mustache`, with the variants and their order as they are.

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
| `stringIdSchemas`, `stringIdProperties`, `stringProperties`, `jsonValueSchemas`, `deepObjectQueryParameters` | empty | lists; entries separated by `\|`, `;` or whitespace on the command line (a comma ends an `--additional-properties` value), or a JSON array in a `-c` config file |
| `resetQueryParameterStyle` | `false` | query parameters not listed in `deepObjectQueryParameters` get the default style (`form`, exploded) |
| `legacyModelRewrites` | `false` | transitional: reproduce openai-client-base's scripted model output |
| `multipartHelper` | `false` | multipart bodies through the generated helper module |
| `fileProperties` | empty | `Schema.property` entries rendered as files |
| `boxOnlyCycles` | `false` | box a model reference only where it closes a cycle of the model graph |
| `defaultOnlyWhereValid` | `false` | `Default` only where its value is one the API would accept |
| `enumFromStr` | `false` | closed enums implement `FromStr` and `TryFrom`; property enums get the `Display` they lack |
| `noStructDisplay` | `false` | structs have no JSON `Display`; API code serializes them with `serde_json::to_string` |
| `integersAsI64` | `false` | integers are `i64` unless `format: int32`; `preferUnsignedInt` and `bestFitInt` stay pinned to `false`, so setting either still fails generation |
| `modelUnions` | `false` | unions render from the model as untagged enums, and the union rewrites of `legacyModelRewrites` do not run |
| `unionPlanFile` | unset | with `modelUnions`: output-relative path the union plan is written to, as JSON |
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
python3 tools/sync-templates.py ~/.m2/repository/org/openapitools/openapi-generator/7.25.0/openapi-generator-7.25.0.jar
```

## Development

Requirements: Java 17, Docker, a Rust toolchain (for the petstore smoke test). Maven comes with the
wrapper.

```bash
./mvnw verify
```

Tests cover the configuration checks, the rendering rules on `src/test/resources/milestone1.yaml`
(generated in-process with whichever openapi-generator is on the classpath) and the spec repair. Run
them against the other supported version with `./mvnw -Dopenapi-generator.version=7.15.0 verify`.

Check that the generator is picked up by the stock CLI (download the CLI jar into `target/`, it is
git-ignored):

```bash
curl -fsSLo target/openapi-generator-cli-7.25.0.jar https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli/7.25.0/openapi-generator-cli-7.25.0.jar
```

```bash
java -cp target/openapi-generator-timvw-rust-0.2.0-SNAPSHOT.jar:target/openapi-generator-cli-7.25.0.jar org.openapitools.codegen.OpenAPIGenerator list | grep timvw-rust
```

Build the image and smoke-test it on the bundled petstore spec:

```bash
docker build --build-arg OPENAPI_GENERATOR_VERSION=7.25.0 -t timvw-rust:dev .
```

To build against another openapi-generator version, pass the same version to Maven first
(`./mvnw -Dopenapi-generator.version=7.15.0 package`) and to `--build-arg`.

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
                                         SpecNormalizer (spec repair)
src/main/resources/timvw-rust/og*/       template overrides, one set per openapi-generator minor version
src/main/resources/META-INF/services/    registers the generator with openapi-generator
src/test/java/                           JUnit 5 tests; src/test/resources/milestone1.yaml is the rendering fixture
tools/sync-templates.py                  regenerates the template sets from the stock jars
test-specs/petstore.yaml                 OpenAPI 3.0 smoke-test spec
Dockerfile                               eclipse-temurin:17-jre + both jars in /opt/gen
.github/workflows/                       ci.yml, release.yml
```

## License

Licensed under either of [Apache License, Version 2.0](LICENSE-APACHE) or [MIT license](LICENSE-MIT)
at your option.
