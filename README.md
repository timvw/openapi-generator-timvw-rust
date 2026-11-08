# openapi-generator-timvw-rust

`timvw-rust` is an OpenAPI-to-Rust client generator: a plugin for [openapi-generator](https://openapi-generator.tech),
built as a Java subclass of the stock `rust` generator (`RustClientCodegen`). It renders models whose serde
behaviour matches the spec (unions that decode by their tag, enums that accept values the spec does not list yet, boxes
only where a type contains itself) and a reqwest client with one async function per operation.

It is used for [openai-client-base](https://github.com/genai-rs/openai-client-base) and
[langfuse-client-base](https://github.com/genai-rs/langfuse-client-base), whose specs it must keep supporting, and is
meant for any OpenAPI 3.0 spec.

| Documentation | |
| --- | --- |
| [docs/mapping](docs/mapping/README.md) | what Rust each OpenAPI construct becomes, as rules per OpenAPI Specification object, each with its tests |
| [docs/type-mapping.md](docs/type-mapping.md) | the same as one overview table, including what is not yet a rule |
| [docs/comparison](docs/comparison/README.md) | stock `-g rust`, timvw-rust and progenitor side by side on one spec |
| [docs/roadmap.md](docs/roadmap.md) | the plan: document the mapping, decide what to change, make the generator conform |
| [docs/design](docs/design/partial-decoding-and-ownership.md) | design proposals with measurements: decoding only what is needed, and ownership |
| [docs/history.md](docs/history.md) | how the generator replaced the clients' post-processing scripts |

## Artifacts

| What | Where |
| --- | --- |
| Maven | `be.timvw:openapi-generator-timvw-rust` on Maven Central |
| Docker | `ghcr.io/timvw/openapi-generator-timvw-rust:<version>-og<openapi-generator version>` |
| Generator name | `timvw-rust` (`-g timvw-rust`) |
| Supported openapi-generator | 7.27.x (one image per supported version) |

The Docker image is a drop-in replacement for `openapitools/openapi-generator-cli`: same arguments,
same `/local` mount convention, same `JAVA_OPTS` environment variable.

## Usage

Generate a client with the Docker image, from the directory that holds the spec:

```bash
docker run --rm -v "$PWD:/local" -u "$(id -u):$(id -g)" \
  ghcr.io/timvw/openapi-generator-timvw-rust:0.18.1-og7.27.0 generate \
  -g timvw-rust \
  -i /local/openapi.yml \
  -o /local \
  --additional-properties=packageName=my-client,packageVersion=0.1.0
```

Without options the output is close to stock `-g rust`. The options openai-client-base and langfuse-client-base both
set are the mapping's [baseline](docs/mapping/README.md#baseline), and the recommended starting point:

```bash
--additional-properties=boxOnlyCycles=true,defaultOnlyWhereValid=true,enumFromStr=true,noStructDisplay=true,\
integersAsI64=true,qualifiedInlineNames=true,modelUnions=true,unionDispatch=true,openEnums=true,\
schemaDefaultDocs=true,nullableUnionFields=true
```

Hand-maintained files (`Cargo.toml`, `README.md`, `.gitignore`) go in the output directory's
`.openapi-generator-ignore`, which the generator honours like any openapi-generator run.

Options not listed below (`packageName`, `packageVersion`, `useChrono`, `reqwestDefaultFeatures`,
`supportMultipleResponses`, `useSerdePathToError`, ...) pass through to the stock generator unchanged.

Images are published per openapi-generator version, tagged `<generator version>-og<openapi-generator version>`
(for example `0.18.1-og7.27.0`). The plain `<generator version>` and `latest` tags point at the newest supported
openapi-generator (7.27.0). openai-client-base and langfuse-client-base pin `-og7.27.0` in their generation scripts.

## Supported configuration

These stock options are pinned: each has exactly one allowed value, applied when the option is unset. Any other value
fails generation with a message naming the option (`supportAsync=false` gives
``option 'supportAsync' must be 'true' (got 'false')``). The generator only maintains the async reqwest-middleware
client, so a blocking client or a single request parameter struct is not available.

| Option | Only allowed value | Notes |
| --- | --- | --- |
| `library` | `reqwest` | `hyper`, `hyper0x` and `reqwest-trait` are rejected |
| `supportAsync` | `true` | |
| `supportMiddleware` | `true` | `reqwest-middleware` is always wired in |
| `useSingleRequestParameter` | `false` | one function argument per parameter |
| `preferUnsignedInt` | `false` | |
| `bestFitInt` | `false` | |
| `enumNameSuffix` | `` (empty) | |
| `hideGenerationTimestamp` | `true` | |

Our own options. The Rule column links the mapping rule that describes what an option renders; the
[history](docs/history.md) describes the others.

| Option | Default | Notes | Rule |
| --- | --- | --- | --- |
| `bonBuilders` | `true` | derive `bon::Builder` on model structs and put `#[bon::builder]` on API functions | [`schema.properties`](docs/mapping/schema.md#schemaproperties) |
| `titleNaming` | `true` | let schema titles name, merge and replace types (stock); `false` keeps names from schema keys and paths | [`schema.title`](docs/mapping/schema.md#schematitle) |
| `webhookModule` | unset | tag for webhook operations, generated into their own API module | |
| `webhookModuleReexport` | `false` | `default_api` re-exports the webhook module | |
| `stringIdSchemas`, `stringIdProperties`, `stringProperties`, `deepObjectQueryParameters` | empty | lists; entries separated by `\|`, `;` or whitespace on the command line (a comma ends an `--additional-properties` value), or a JSON array in a `-c` config file | [`schema.type.scalar-component`](docs/mapping/schema.md#schematypescalar-component) |
| `resetQueryParameterStyle` | `false` | query parameters not listed in `deepObjectQueryParameters` get the default style (`form`, exploded) | |
| `multipartHelper` | `false` | multipart bodies through the generated helper module | |
| `fileProperties` | empty | `Schema.property` entries rendered as files | [`schema.format.binary`](docs/mapping/schema.md#schemaformatbinary) |
| `boxOnlyCycles` | `false` | box a model reference only where it closes a cycle of the model graph | [`schema.properties.reference`](docs/mapping/schema.md#schemapropertiesreference) |
| `defaultOnlyWhereValid` | `false` | `Default` only where its value is one the API would accept | [`schema.properties.default-impl`](docs/mapping/schema.md#schemapropertiesdefault-impl) |
| `enumFromStr` | `false` | closed enums implement `FromStr` and `TryFrom`; property enums get the `Display` they lack | [`schema.enum.from-str`](docs/mapping/schema.md#schemaenumfrom-str) |
| `noStructDisplay` | `false` | structs have no JSON `Display`; API code serializes them with `serde_json::to_string` | [`schema.properties.display`](docs/mapping/schema.md#schemapropertiesdisplay) |
| `integersAsI64` | `false` | integers are `i64` unless `format: int32`; `preferUnsignedInt` and `bestFitInt` stay pinned to `false`, so setting either still fails generation | [`schema.type.integer`](docs/mapping/schema.md#schematypeinteger) |
| `modelUnions` | `false` | unions render from the model as untagged enums | [`schema.oneOf`](docs/mapping/schema.md#schemaoneof) |
| `unionDispatch` | `false` | with `modelUnions`: a union with a tag decodes by that tag | [`discriminator.propertyName`](docs/mapping/discriminator.md#discriminatorpropertyname) |
| `openEnums` | `false` | with `modelUnions`: what a response or webhook payload can hold has a catch-all for values the spec does not list | [`schema.enum.open`](docs/mapping/schema.md#schemaenumopen) |
| `unionPlanFile` | unset | with `modelUnions`: output-relative path the union plan is written to, as JSON | |
| `typedEventStreams` | `false` | a `text/event-stream` response is a method returning a typed stream; `sse.rs` is only written when some operation streams | [`response.content.event-stream`](docs/mapping/response.md#responsecontentevent-stream) |
| `contentTypeMethods` | `false` | one method per request body media type and response shape, named with a suffix | [`requestBody.content`](docs/mapping/request-body.md#requestbodycontent) |
| `typedEnumParameters` | `false` | inline enums of parameters and form/multipart body properties become component enums the functions take, see [the comparison](docs/comparison/README.md#typedenumparameters-enum-parameters-take-the-enum) | |
| `nullableUnionFields` | `false` | with `modelUnions`: a field whose union is nullable is `Option<Option<U>>` (optional) or `Option<U>` (required), so `null` can be sent | [`schema.oneOf.null-branch`](docs/mapping/schema.md#schemaoneofnull-branch) |
| `schemaDefaultDocs` | `false` | a property's or parameter's `default` is noted in its documentation | [`schema.default`](docs/mapping/schema.md#schemadefault) |
| `deprecatedAttributes` | `false` | `#[deprecated]` on deprecated properties and schemas; generated files start with `#![allow(deprecated)]` | [`schema.deprecated`](docs/mapping/schema.md#schemadeprecated) |
| `pruneAllHelpers` | `false` | helpers nobody calls are always left out; with this option also those marked `#[allow(dead_code)]` | |
| `boxResponseError` | `false` | box the `ResponseError` payload | |
| `apiLintAllows` | unset | `#![allow(..)]` contents for API modules and `configuration.rs` | |
| `userAgentFromPackage` | `false` | default user agent from `packageName` and the crate version | |
| `preprocessedSpecFile` | unset | output-relative path the preprocessed spec is written to, as JSON | |

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
docs/mapping/                            the mapping as rules, one file per OpenAPI Specification object;
                                         MappingDocsTest checks that each rule names tests that exist
docs/history.md                          what each rendering replaced in the client repos
docs/design/                             design proposals; decode-bench measures decoding costs
docs/roadmap.md                          the plan: document the mapping, decide changes, conform
docs/comparison/                         stock -g rust vs timvw-rust vs progenitor on one spec (generate.sh)
test-specs/petstore.yaml                 OpenAPI 3.0 smoke-test spec
Dockerfile                               eclipse-temurin:17-jre + both jars in /opt/gen
.github/workflows/                       ci.yml, release.yml
```

## License

Licensed under either of [Apache License, Version 2.0](LICENSE-APACHE) or [MIT license](LICENSE-MIT)
at your option.
