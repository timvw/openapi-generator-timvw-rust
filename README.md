# openapi-generator-timvw-rust

`timvw-rust` is an OpenAPI-to-Rust client generator: a plugin for [openapi-generator](https://openapi-generator.tech).
openapi-generator parses the spec and drives the generation (`DefaultCodegen`, `DefaultGenerator`, the CLI); the Rust
side is the plugin's own: the type mapping, naming, model and operation processing (`RustCodegenBase`, ported from
openapi-generator's `-g rust` and then extended) and every template. It renders models whose serde
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
| [docs/decisions.md](docs/decisions.md) | proposed changes to the mapping, each with a recommendation and the maintainer's decision |
| [docs/design](docs/design/partial-decoding-and-ownership.md) | design proposals with measurements: decoding only what is needed, and ownership |
| [docs/history.md](docs/history.md) | how the generator replaced the clients' post-processing scripts |

## Artifacts

| What | Where |
| --- | --- |
| Maven | `be.timvw:openapi-generator-timvw-rust` on Maven Central |
| Docker | `ghcr.io/timvw/openapi-generator-timvw-rust:<version>-og<openapi-generator version>` |
| Generator name | `timvw-rust` (`-g timvw-rust`) |
| openapi-generator | built and tested against 7.27.0 (the pom's version), which the image bundles |

The Docker image is a drop-in replacement for `openapitools/openapi-generator-cli`: same arguments,
same `/local` mount convention, same `JAVA_OPTS` environment variable.

## Usage

Generate a client with the Docker image, from the directory that holds the spec:

```bash
docker run --rm -v "$PWD:/local" -u "$(id -u):$(id -g)" \
  ghcr.io/timvw/openapi-generator-timvw-rust:0.19.0-og7.27.0 generate \
  -g timvw-rust \
  -i /local/openapi.yml \
  -o /local \
  --additional-properties=packageName=my-client,packageVersion=0.1.0
```

Without options you get an async reqwest client with reqwest-middleware and bon builders, with the same models stock
`-g rust` renders. The options openai-client-base and langfuse-client-base both set are the mapping's
[baseline](docs/mapping/README.md#baseline), and the recommended starting point:

```bash
--additional-properties=boxOnlyCycles=true,defaultOnlyWhereValid=true,enumFromStr=true,noStructDisplay=true,\
integersAsI64=true,qualifiedInlineNames=true,modelUnions=true,unionDispatch=true,openEnums=true,\
schemaDefaultDocs=true,nullableUnionFields=true
```

Hand-maintained files (`Cargo.toml`, `README.md`, `.gitignore`) go in the output directory's
`.openapi-generator-ignore`, which the generator honours like any openapi-generator run.

Images are published per openapi-generator version, tagged `<generator version>-og<openapi-generator version>`
(for example `0.19.0-og7.27.0`). The plain `<generator version>` and `latest` tags point at the newest supported
openapi-generator (7.27.0). openai-client-base and langfuse-client-base pin `-og7.27.0` in their generation scripts.

## Configuration

The options of `timvw-rust` (`config-help -g timvw-rust` lists them too). The Rule column links the mapping rule that
describes what an option renders; the [history](docs/history.md) describes the others.

The crate options keep the names and defaults they have in stock `-g rust`, so a configuration taken over from it keeps
working:

| Option | Default | Notes | Rule |
| --- | --- | --- | --- |
| `packageName` | `openapi` | crate name; `-` becomes `_` where the crate is imported | |
| `packageVersion` | the spec's `info.version`, else `1.0.0` | crate version (`v1.2` becomes `1.2.0`) | |
| `useChrono` | `true` | `date` and `date-time` as `chrono` types; `false` renders both as `String` | [`schema.format.date-time`](docs/mapping/schema.md#schemaformatdate-time) |
| `useSerdePathToError` | `false` | responses decode through `serde_path_to_error`, so an error names the path of the value | |
| `supportMultipleResponses` | `false` | a function returns `ResponseContent<<Operation>Success>`, an enum of the 2xx responses | |
| `reqwestDefaultFeatures` | `native-tls` | the crate's default features, passed on to reqwest (comma-separated, empty for none) | |

The options of the generator itself:

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
| `integersAsI64` | `false` | integers are `i64` unless `format: int32` | [`schema.type.integer`](docs/mapping/schema.md#schematypeinteger) |
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

`timvw-rust` generates only the async reqwest-middleware client. The options stock `-g rust` has for other clients
are not options here: it always generates as with `library=reqwest`, `supportAsync=true`, `supportMiddleware=true`,
`useSingleRequestParameter=false`, `preferUnsignedInt=false`, `bestFitInt=false`, `enumNameSuffix=` (empty) and
`hideGenerationTimestamp=true`, and setting any of them, to any value, fails generation, as does `--library` with
anything but `reqwest`. Stock options it does not support are rejected too: `avoidBoxedModels`, `mockall`,
`topLevelApiClient`, `withAWSV4Signature`, `supportTokenSource`, `useBonBuilder` (replaced by `bonBuilders`),
`templateDir` (`-t`; the templates are part of the generator). An operation with the `x-group-parameters` extension
(one parameter struct per operation in stock `-g rust`) fails generation as well. A configuration taken over from
`-g rust` therefore fails instead of being silently ignored:

```
Exception in thread "main" java.lang.IllegalArgumentException: timvw-rust: option 'supportAsync' is not configurable: timvw-rust always uses 'true'; remove it (it was set to 'true')
```

## Templates

Every template the generator renders is its own, in `src/main/resources/timvw-rust/`: the crate files
(`Cargo.mustache`, `lib.mustache`, `README.mustache`, `gitignore.mustache`, `git_push.sh.mustache`, `.travis.yml`),
the models (`model.mustache`, `model_mod.mustache`, `model_doc.mustache`), the API modules (`api.mustache`,
`api_mod.mustache`, `configuration.mustache`, `api_doc.mustache`), the helper modules (`multipart_helper.mustache`,
`sse.mustache`) and `partial_header.mustache`. None is looked up in the openapi-generator jar, and `-t` is rejected.
They started from the `rust` templates of openapi-generator 7.27.0 (see [NOTICE](NOTICE)) and are edited by hand
like the Java code; they only hold the branches of the one client `timvw-rust` generates.

The Java code builds on openapi-generator's API (`DefaultCodegen`, the parser, `DefaultGenerator`), compiled against
the version in the pom. A run in another openapi-generator minor version logs a warning, as what `DefaultCodegen`
hands the generator can change between versions.

## Development

Requirements: Java 17, Docker, a Rust toolchain (for the petstore smoke test). Maven comes with the
wrapper.

```bash
./mvnw verify
```

Tests cover the configuration checks, the rendering rules of the [mapping](docs/mapping/README.md) (generated
in-process from `src/test/resources/`) and the spec repair. To try another openapi-generator version, run them against
it with `./mvnw -Dopenapi-generator.version=<version> verify`, and compare the clients' output before moving the pom.

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

CI (`.github/workflows/ci.yml`) runs the same steps on every push and pull request, with the pom's
openapi-generator version and with every version the client repos pin (7.27.0).

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
src/main/java/be/timvw/openapi/rust/     RustCodegenBase (Rust types, naming, model and operation processing,
                                         crate options; ported from -g rust), TimvwRustCodegen (the generator's
                                         options and rules), RustDoc (URL wrapping), SpecNormalizer (spec repair),
                                         HelperPruning (unused helpers), ExtraRules (the opt-in renderings from the
                                         generator comparison), ...
src/main/java/org/openapitools/codegen/  same-package access to the inline-model resolver and the normalizer
src/main/resources/timvw-rust/           every template the generator renders
src/main/resources-filtered/             timvw-rust.properties: the openapi-generator version built against
src/main/resources/META-INF/services/    registers the generator with openapi-generator
src/test/java/                           JUnit 5 tests; src/test/resources/milestone1.yaml is the rendering fixture
tools/link-mapping-tests.py              links the test names of docs/mapping to their lines
docs/type-mapping.md                     each OpenAPI construct and its Rust rendering
docs/mapping/                            the mapping as rules, one file per OpenAPI Specification object;
                                         MappingDocsTest checks that each rule links tests that exist
docs/history.md                          what each rendering replaced in the client repos
docs/design/                             design proposals; decode-bench measures decoding costs
docs/roadmap.md                          the plan: document the mapping, decide changes, conform
docs/comparison/                         stock -g rust vs timvw-rust vs progenitor on one spec (generate.sh)
test-specs/petstore.yaml                 OpenAPI 3.0 smoke-test spec
Dockerfile                               eclipse-temurin JRE + the openapi-generator CLI jar and this jar in /opt/gen
NOTICE                                   what is derived from openapi-generator (Apache-2.0)
.github/workflows/                       ci.yml, release.yml
```

## License

Licensed under either of [Apache License, Version 2.0](LICENSE-APACHE) or [MIT license](LICENSE-MIT)
at your option.
