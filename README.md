# openapi-generator-timvw-rust

A custom [openapi-generator](https://openapi-generator.tech) Rust client generator, `timvw-rust`,
built as a Java subclass of the stock `rust` generator (`RustClientCodegen`).

Its purpose is to replace the post-processing scripts that
[openai-client-base](https://github.com/timvw/openai-client-base) and
[langfuse-client-base](https://github.com/timvw/langfuse-client-base) run on top of `-g rust`,
by moving every fix into the generator itself. The design, type rules and delivery plan live in the
design doc: [Custom Rust generator: rendering design](https://claude.ai/artifact/ABar8Up91gjVMDHgHL1GE3).

**Status: Milestone 0 (plumbing).** The generator renders exactly what stock `-g rust` renders with
the stock templates; it only narrows the accepted configuration (below). Behaviour changes follow
in later milestones.

## Artifacts

| What | Where |
| --- | --- |
| Maven | `be.timvw:openapi-generator-timvw-rust` on Maven Central |
| Docker | `ghcr.io/timvw/openapi-generator-timvw-rust:<version>` (and `:latest`) |
| Generator name | `timvw-rust` (`-g timvw-rust`) |
| Pinned openapi-generator | 7.25.0 (bundled in the image as `openapi-generator-cli-7.25.0.jar`) |

The Docker image is a drop-in replacement for `openapitools/openapi-generator-cli`: same arguments,
same `/local` mount convention, same `JAVA_OPTS` environment variable.

## Usage in the client repos

Images are published per openapi-generator version, tagged `<generator version>-og<openapi-generator version>`
(for example `0.1.0-og7.15.0` and `0.1.0-og7.25.0`). The plain `<generator version>` and `latest` tags
point at the newest supported openapi-generator (7.25.0). A client repo pins the openapi-generator
version its pipeline is tuned for: openai-client-base uses `-og7.15.0`, langfuse-client-base `-og7.25.0`.

Replace the image in `scripts/generate.sh`:

```bash
docker run --rm -v "$PWD:/local" -u "$(id -u):$(id -g)" \
  ghcr.io/timvw/openapi-generator-timvw-rust:0.1.0-og7.25.0 generate \
  -g timvw-rust \
  -i /local/openapi.yml \
  -o /local \
  --additional-properties=packageName=my-client,packageVersion=0.1.0
```

Options not listed below (`packageName`, `packageVersion`, `useChrono`, `reqwestDefaultFeatures`,
`supportMultipleResponses`, `useSerdePathToError`, ...) pass through to the stock generator unchanged.

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

These options are rejected whenever they are set, whatever the value:
`avoidBoxedModels`, `mockall`, `topLevelApiClient`, `withAWSV4Signature`, `supportTokenSource`,
`useBonBuilder`.

Example failure:

```
Exception in thread "main" java.lang.IllegalArgumentException: timvw-rust: option 'library' must be 'reqwest' (got 'hyper')
```

## Development

Requirements: Java 17, Docker, a Rust toolchain (for the petstore smoke test). Maven comes with the
wrapper.

```bash
./mvnw verify
```

Check that the generator is picked up by the stock CLI (download the CLI jar into `target/`, it is
git-ignored):

```bash
curl -fsSLo target/openapi-generator-cli-7.25.0.jar https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli/7.25.0/openapi-generator-cli-7.25.0.jar
```

```bash
java -cp target/openapi-generator-timvw-rust-0.1.0-SNAPSHOT.jar:target/openapi-generator-cli-7.25.0.jar org.openapitools.codegen.OpenAPIGenerator list | grep timvw-rust
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

CI (`.github/workflows/ci.yml`) runs the same steps on every push and pull request.

## Releasing

1. Make sure `main` is green.
2. Tag and push: `git tag v0.1.0 && git push origin v0.1.0`.
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
src/main/java/be/timvw/openapi/rust/     TimvwRustCodegen extends RustClientCodegen
src/main/resources/META-INF/services/    registers the generator with openapi-generator
src/test/java/                           JUnit 5 tests for the configuration checks
test-specs/petstore.yaml                 OpenAPI 3.0 smoke-test spec
Dockerfile                               eclipse-temurin:17-jre + both jars in /opt/gen
.github/workflows/                       ci.yml, release.yml
```

## License

Licensed under either of [Apache License, Version 2.0](LICENSE-APACHE) or [MIT license](LICENSE-MIT)
at your option.
