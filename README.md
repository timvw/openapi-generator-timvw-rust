# openapi-generator-timvw-rust

A custom [openapi-generator](https://openapi-generator.tech) Rust client generator, `timvw-rust`,
built as a Java subclass of the stock `rust` generator (`RustClientCodegen`).

Its purpose is to replace the post-processing scripts that
[openai-client-base](https://github.com/genai-rs/openai-client-base) and
[langfuse-client-base](https://github.com/genai-rs/langfuse-client-base) run on top of `-g rust`,
by moving every fix into the generator itself. The design, type rules and delivery plan live in the
design doc: [Custom Rust generator: rendering design](https://claude.ai/artifact/ABar8Up91gjVMDHgHL1GE3).

**Status: Milestone 2 (OpenAI without scripts), group 2.2.** The generator renders what stock `-g rust` renders
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

Spec-level decisions stay in the client repos. Spec validation runs in the CLI before any generator
code, so a keyword the 3.0 validator rejects (Langfuse uses the 3.1 `const`) cannot be repaired here;
langfuse-client-base drops it while composing the generator input (`prepare_compatible_spec.py`, which
also adds the v3 compatibility layer). Any other invalid construct fails generation and is fixed upstream.

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

Our own option:

| Option | Default | Notes |
| --- | --- | --- |
| `bonBuilders` | `true` | derive `bon::Builder` on model structs and put `#[bon::builder]` on API functions |

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
