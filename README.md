# openapi-generator-timvw-rust

`timvw-rust` generates an async Rust client from an OpenAPI 3.0 or 3.1 spec. It is a plugin for
[openapi-generator](https://openapi-generator.tech): openapi-generator parses the spec and drives the run, and
everything Rust is the plugin's own (type mapping, naming, model and operation processing, every template). The crate
it writes has one async function per operation, with a builder, sending through a [tower](https://docs.rs/tower)
service from [`timvw-rust-runtime`](runtime/README.md) (reqwest unless you replace it). Its models decode
what the server sends:
- unions decode by their tag;
- enums accept values the spec does not list yet;
- integers are `i64`;
- types are boxed only where they contain themselves.

Errors are typed per status and carry the response headers. Every model has a `validate()` for the spec's
constraints. What each OpenAPI construct becomes is written down as [rules](docs/mapping/README.md), each with its
tests. It generates [openai-client-base](https://github.com/genai-rs/openai-client-base) and
[langfuse-client-base](https://github.com/genai-rs/langfuse-client-base).

## Quick start

From the directory that holds `openapi.yaml`:

```bash
docker run --rm -v "$PWD:/local" -u "$(id -u):$(id -g)" \
  ghcr.io/timvw/openapi-generator-timvw-rust:0.25.0-og7.27.0 generate \
  -g timvw-rust \
  -i /local/openapi.yaml \
  -o /local/my-client \
  --additional-properties=packageName=my-client
```

No options are needed. What the generator renders without options is the mapping. The options below add functions or
adapt a spec; none of them makes the models or the error handling "more correct".

## What you get

The examples use a small API with an `Items` tag (it is in
[`src/test/resources/docs_examples.yaml`](src/test/resources/docs_examples.yaml)). Every Rust example on this page and
in the guides, except those marked as code of an older release or abridged signatures, is compiled by the tests.

```text
my-client/
  Cargo.toml          bon, serde, serde_json, timvw-rust-runtime, ... (see below)
  src/lib.rs
  src/apis/           configuration.rs, mod.rs (Error, ResponseContent), items_api.rs: one module per tag
  src/models/         one file per schema, mod.rs, validation.rs (the Validate trait)
  docs/               a Markdown page per model and API
```

### Calling an operation

Each operation is an async function with a [bon](https://bon-rs.com) builder. You set the arguments by name and call
`call()`:

```rust
use my_client::apis::configuration::Configuration;
use my_client::apis::items_api;

let mut configuration = Configuration::new();
configuration.base_path = "https://api.example.com/v1".to_owned();
configuration.bearer_access_token = Some("secret".to_owned());

let page = items_api::list_items()
    .configuration(&configuration)
    .limit(10)
    .call()
    .await?;
for item in &page.data {
    println!("{} {}", item.id, item.name);
}
```

An optional argument has a setter for the value (`.limit(10)`) and a `maybe_` setter for an `Option`
(`.maybe_limit(None)`). A request body is borrowed, so you keep it after the call:

```rust
use my_client::models;

let new_item = models::NewItem::builder()
    .name("Lamp".to_owned())
    .tags(vec!["light".to_owned()])
    .build();
let item = items_api::create_item()
    .configuration(&configuration)
    .new_item(&new_item)
    .call()
    .await?;
```

`Configuration` holds the base path, the credentials of the spec's security schemes, the user agent and the
`timvw_rust_runtime::Client` every call goes through: a tower service, reqwest by default. Retries, timeouts, rate
limits or tracing are tower layers on it, and `Client::from_service` replaces reqwest with any service (a hyper client,
a mock in tests):

```rust
use std::time::Duration;
use timvw_rust_runtime::retry::RetryPolicy;
use timvw_rust_runtime::tower::retry::RetryLayer;

let mut configuration = Configuration::new();
let policy = RetryPolicy::new(3).max_delay(Duration::from_secs(10));
configuration.client = configuration.client.clone().layer(RetryLayer::new(policy));
```

`RetryPolicy` retries `429`, `408` and `5xx` responses and failed connections, honouring `Retry-After`. With
`httpStack=reqwest-middleware` the client is a `reqwest_middleware::ClientWithMiddleware` instead, as before 0.25.

### Models

- A schema with properties is a struct with `new(<required fields>)`, a builder and serde attributes.
- A required property is `T`, an optional one `Option<T>`, a nullable one `Option<Option<T>>`. With the double
  `Option`, "not sent" and "sent as `null`" stay apart.
- A `oneOf` or `anyOf` is an enum. When the branches have a tag (a `discriminator`, or a property every branch fixes),
  the variant comes from the tag, and an error names that variant and field:

```rust
let event: models::Event = serde_json::from_str(r#"{"type":"deleted","id":"item-1"}"#)?;
match event {
    models::Event::Created(created) => println!("created {}", created.item.name),
    models::Event::Deleted(deleted) => println!("deleted {}", deleted.id),
    models::Event::Unknown(value) => println!("a newer event: {value}"),
}
```

- An enum a response can hold, and a tagged union, accept a value the spec does not list (`Status::Unknown`,
  `Event::Unknown`), so one new value does not fail the whole response.
- A union with a string variant converts from a string: `.content("a short text".into())`.
- Closed enums implement `FromStr` and `Display`.
- An `integer` is `i64` (`i32` only for `format: int32`). `date`/`date-time` are chrono types and `uuid` is
  `uuid::Uuid`, everywhere.

[docs/type-mapping.md](docs/type-mapping.md) has the whole table.

### Errors

A call returns `Result<T, apis::Error<OperationError>>`. An error status is `Error::ResponseError` with the status,
the headers, the body and the body decoded as the variant the status selects:

```rust
use my_client::apis::Error;

match items_api::get_item().configuration(&configuration).id("item-1").call().await {
    Ok(item) => println!("{}", item.name),
    Err(Error::ResponseError(response)) => {
        let request_id = response.headers.get("x-request-id");
        match &response.entity {
            Some(items_api::GetItemError::Status404(problem)) => println!("not found: {}", problem.detail),
            Some(items_api::GetItemError::Status5XX(problem)) => println!("server error: {}", problem.detail),
            _ => println!("{}: {} ({request_id:?})", response.status, response.content),
        }
    }
    Err(other) => return Err(other.into()),
}
```

See [errors](docs/guides/errors.md).

### Validation

Every model implements `Validate`. It checks the spec's `minLength`, `pattern`, `maximum`, `maxItems`,
`uniqueItems`, ... for the whole value. The generated functions never call it, so validating is your choice:

```rust
use my_client::models::validation::Validate;

let draft = models::NewItem::builder().name(String::new()).price(-1.0).build();
let errors = draft.validate().unwrap_err();
assert_eq!(errors.len(), 2);
for error in &errors {
    println!("{}: {}", error.path, error.rule);
}
assert_eq!(errors.to_string(), "name: length 0 breaks minLength 1\nprice: -1 breaks minimum 0");
```

`validate()` reports every violation, with the path to it.

See [validation](docs/guides/validation.md).

## Options

Pass options with `--additional-properties=<name>=<value>,...` or in a `-c` config file (JSON or YAML).
`config-help -g timvw-rust` lists them. An option that does not exist fails generation and names the closest one
(`did you mean 'bonBuilders'?`), so a typo cannot change the output silently.

**The crate.** These keep the names and defaults of stock `-g rust`.

| Option | Default | What it does |
| --- | --- | --- |
| `packageName` | `openapi` | crate name; `-` becomes `_` where the crate is imported |
| `packageVersion` | the spec's `info.version`, else `1.0.0` | crate version (`v1.2` becomes `1.2.0`) |
| `useChrono` | `true` | `date`/`date-time` as chrono types; `false` renders both as `String` ([rule](docs/mapping/schema.md#schemaformatdate-time)) |
| `useSerdePathToError` | `false` | success bodies decode through `serde_path_to_error`, so a decoding error names the path |
| `supportMultipleResponses` | `false` | a function returns `ResponseContent<<Operation>Success>`, an enum of its 2xx responses, with status and headers |
| `httpStack` | `tower` | how requests are sent: `tower` through `timvw_rust_runtime::Client` (a tower service, reqwest by default); `reqwest-middleware` through reqwest and reqwest-middleware, as up to 0.24 ([guide](docs/guides/http-stack.md)) |
| `runtimeVersion`, `runtimePath` | `0.1`, unset | with `httpStack=tower`: the `timvw-rust-runtime` version requirement, and a path dependency next to it to build against an unreleased runtime |
| `reqwestDefaultFeatures` | `native-tls` | the crate's default features, passed on to reqwest (comma-separated, empty for none; with `httpStack=tower` the crate also has a default `retry` feature). Several values only work in a `-c` config file, as a comma ends an `--additional-properties` value |
| `publishRustRegistry`, `repositoryUrl`, `documentationUrl`, `homePageUrl` | unset | `Cargo.toml` `publish`, `repository`, `documentation`, `homepage` |
| `bonBuilders` | `true` | `bon::Builder` on model structs and `#[bon::builder]` on API functions; `false` makes the functions take positional arguments ([rule](docs/mapping/schema.md#schemaproperties)) |
| `userAgentFromPackage` | `false` | default user agent `<packageName>/<crate version>` |
| `apiLintAllows` | unset | `#![allow(..)]` at the top of the API modules and `configuration.rs`. Several lints (comma-separated) only work in a `-c` config file, as a comma ends an `--additional-properties` value |

**Extra functions and modules.** All are off by default except `validateMethods`, and each one adds to the crate.

| Option | Default | What it adds |
| --- | --- | --- |
| `responseFunctions` | `false` | `<operation>_response`, returning `ApiResponse<T>`: status, headers and the undecoded body, to decode as the generated type, as a subset of your own (owned or borrowed) or as text; the headers a success declares are typed in `<Operation>ResponseHeaders` (`response.typed_headers::<..>()`), and those an error declares in `<Operation>ErrorHeaders` (`content.typed_headers::<..>()` on its `ResponseContent`) ([guide](docs/guides/response-functions.md), [rules](docs/mapping/response.md#responsecontentresponse-function), [`header.schema.typed`](docs/mapping/header.md#headerschematyped), [`header.schema.typed-error`](docs/mapping/header.md#headerschematyped-error)) |
| `contentTypeMethods` | `false` | a function per other request media type and response shape: `_json`, `_multipart`, `_form`, `_text`, `_bytes` ([rule](docs/mapping/request-body.md#requestbodycontent)) |
| `typedEventStreams` | `false` | a `text/event-stream` response is a function returning `apis::sse::EventStream<Event, _>`, a stream of typed events ([rule](docs/mapping/response.md#responsecontentevent-stream)) |
| `multipartHelper` | `false` | multipart bodies through a generated `multipart_helper.rs`: files, arrays, JSON parts, `encoding` content types, unions with file variants ([rule](docs/mapping/request-body.md#requestbodycontentmultipart)) |
| `validateMethods` | `true` | `Validate` impls and `models/validation.rs`; `false` leaves them out, and the `regex` dependency with them ([guide](docs/guides/validation.md)) |
| `deprecatedAttributes` | `false` | `#[deprecated]` on deprecated properties and schemas ([rule](docs/mapping/schema.md#schemadeprecated)) |
| `typedEnumParameters` | `false` | an inline enum of a parameter or form/multipart property becomes a component enum the function takes, instead of `&str` |
| `pruneAllHelpers` | `false` | also leave out unused helpers marked `#[allow(dead_code)]` (unused ones without the mark are always left out) |

**Adapting a spec.** These are for a spec whose shape does not fit as it is.

| Option | Default | What it does |
| --- | --- | --- |
| `titleNaming` | `true` | let schema titles name, merge and replace types (stock); `false` keeps names from schema keys and paths ([rule](docs/mapping/schema.md#schematitle)) |
| `stringIdSchemas`, `stringIdProperties`, `stringProperties` | empty | render the listed component schemas, properties that reference them, or `Schema.property` entries as `String` ([rule](docs/mapping/schema.md#schematypescalar-component)) |
| `fileProperties` | empty | `Schema.property` entries rendered as files ([rule](docs/mapping/schema.md#schemaformatbinary)) |
| `deepObjectQueryParameters` | empty | `<path>?<name>` object query parameters sent in deepObject style (`name[field]=value`), like those the spec declares `style: deepObject`; other object query parameters go as JSON strings |
| `resetQueryParameterStyle` | `false` | the query parameters not sent in deepObject style get the default style (`form`, exploded) |
| `webhookModule` | unset | the tag for webhook operations, generated into their own API module; unset, webhooks go into the module of their tag, after its path operations ([rule](docs/mapping/openapi.md#openapiwebhooks)) |
| `webhookModuleReexport` | `false` | `default_api` re-exports the webhook module |
| `unionPlanFile`, `preprocessedSpecFile` | unset | write the union plan, or the spec after the spec rules, to this output-relative path as JSON (for tools and debugging) |

A list option takes entries separated by `|`, `;` or whitespace on the command line (a comma ends an
`--additional-properties` value), or a JSON array in a `-c` file.

**Stock naming options.** `--model-name-prefix`, `--model-name-suffix`, `--model-name-mappings`, `--name-mappings`
and the like rename what is generated. `--schema-mappings Filter=crate::custom::Filter` uses a type of your own crate
in place of a component schema: it is named by that path everywhere, and no model is generated for the schema
([rule](docs/mapping/components.md#componentsschemas)).

**Not options.** `timvw-rust` generates one async client, whose HTTP stack `httpStack` chooses. Setting stock
`-g rust`'s options for other clients fails generation, whatever the value. These are `library` (other than `reqwest`),
`supportAsync`, `supportMiddleware`, `useSingleRequestParameter`, `preferUnsignedInt`, `bestFitInt`,
`enumNameSuffix`, `hideGenerationTimestamp`, `avoidBoxedModels`, `mockall`, `topLevelApiClient`,
`withAWSV4Signature`, `supportTokenSource`, `useBonBuilder` (use `bonBuilders`) and `templateDir` (`-t`). The options
that were the mapping's baseline up to 0.20 fail as well; their rendering is always applied. These are
`boxOnlyCycles`, `defaultOnlyWhereValid`, `enumFromStr`, `noStructDisplay`, `integersAsI64`, `qualifiedInlineNames`,
`modelUnions`, `unionDispatch`, `openEnums`, `schemaDefaultDocs`, `nullableUnionFields` and `boxResponseError`. An
operation with `x-group-parameters` fails too. The [migration guide](docs/guides/migration.md) lists what to change.

## Running it

| What | Where |
| --- | --- |
| Docker | `ghcr.io/timvw/openapi-generator-timvw-rust:<version>-og<openapi-generator version>`, e.g. `0.25.0-og7.27.0`; `<version>` and `latest` point at the newest openapi-generator |
| Maven | `be.timvw:openapi-generator-timvw-rust` on Maven Central |
| Generator name | `timvw-rust` (`-g timvw-rust`) |
| openapi-generator | built and tested against 7.27.0, which the image bundles; another minor version logs a warning |

The image is a drop-in for `openapitools/openapi-generator-cli`: same arguments, same `/local` mount, same
`JAVA_OPTS`.

With the openapi-generator Maven plugin
([openapi-generator-maven-plugin](https://github.com/OpenAPITools/openapi-generator/tree/master/modules/openapi-generator-maven-plugin)),
add the generator as a dependency of the plugin:

```xml
<plugin>
  <groupId>org.openapitools</groupId>
  <artifactId>openapi-generator-maven-plugin</artifactId>
  <version>7.27.0</version>
  <dependencies>
    <dependency>
      <groupId>be.timvw</groupId>
      <artifactId>openapi-generator-timvw-rust</artifactId>
      <version>0.25.0</version>
    </dependency>
  </dependencies>
  <executions>
    <execution>
      <goals><goal>generate</goal></goals>
      <configuration>
        <generatorName>timvw-rust</generatorName>
        <inputSpec>${project.basedir}/openapi.yaml</inputSpec>
        <output>${project.build.directory}/my-client</output>
        <additionalProperties>packageName=my-client</additionalProperties>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Use `<additionalProperties>` rather than `<configOptions>`. The plugin silently drops a `<configOptions>` entry it does
not know, so a misspelt one there is never checked.

Files you maintain by hand in the output directory (`Cargo.toml`, `README.md`, `lib.rs`) go in its
`.openapi-generator-ignore`, as with any openapi-generator run. A hand-kept `Cargo.toml` has to follow the
dependencies the generator writes, which depend on the spec and the options:

| Dependency | When |
| --- | --- |
| `serde`, `serde_json`, `serde_repr`, `url` | always |
| `timvw-rust-runtime` | `httpStack=tower` (the default) |
| `reqwest`, `reqwest-middleware` | `httpStack=reqwest-middleware` |
| `bon` | `bonBuilders` (the default) |
| `serde_with` | a nullable optional property (`Option<Option<T>>`) or a `format: byte` property |
| `chrono` | a `date` or `date-time` anywhere (model, parameter, union variant), with `useChrono` (the default) |
| `uuid` | a `format: uuid` anywhere |
| `regex` | a `pattern` that `validate()` checks |
| `futures-core` (and reqwest's `stream` feature with `httpStack=reqwest-middleware`) | `typedEventStreams` with an operation that streams |
| `tokio` (`fs`), `tokio-util` (`codec`) (and reqwest's `stream` feature with `httpStack=reqwest-middleware`) | a file body, or a multipart file without `multipartHelper` |
| `serde_path_to_error` | `useSerdePathToError` |

The rule: on every upgrade of the generator (and every spec update), generate once into an empty directory and
compare its `Cargo.toml` with yours.

## Documentation

| | |
| --- | --- |
| [docs/guides/errors.md](docs/guides/errors.md) | the `Error` type, status variants, headers, text errors |
| [docs/guides/validation.md](docs/guides/validation.md) | `validate()`: what it checks, what it skips, the off switch |
| [docs/guides/response-functions.md](docs/guides/response-functions.md) | `responseFunctions`: status, (typed) headers and partial decoding of a success |
| [docs/guides/migration.md](docs/guides/migration.md) | upgrading from 0.21 and 0.20, and coming from stock `-g rust` |
| [docs/mapping](docs/mapping/README.md) | what each OpenAPI construct becomes, as rules per specification object, each with its tests |
| [docs/type-mapping.md](docs/type-mapping.md) | the same as one overview table, and the known gaps |
| [docs/comparison](docs/comparison/README.md) | stock `-g rust`, timvw-rust and progenitor side by side on one spec |
| [docs/decisions.md](docs/decisions.md) | the mapping changes of 0.21, each with what others do and the decision |
| [docs/design](docs/design/partial-decoding-and-ownership.md) | design notes with measurements: decoding only what is needed, and ownership |
| [docs/roadmap.md](docs/roadmap.md), [docs/history.md](docs/history.md) | how the generator got here, release by release |

## Development

Requirements: Java 17, a Rust toolchain (the tests compile and run generated crates with cargo), Docker for the image.
Maven comes with the wrapper.

```bash
./mvnw verify
```

The tests generate in-process from the specs in `src/test/resources/`. Each rule of the
[mapping](docs/mapping/README.md) links the tests that show it (`MappingDocsTest` checks the links; `python3
tools/link-mapping-tests.py` rewrites their line numbers). The runtime tests compile generated crates and run
`src/test/rust/*.rs` against them with cargo, `cargo clippy -- -D warnings` included. To try another openapi-generator
version: `./mvnw -Dopenapi-generator.version=<version> verify`.

Build the image and generate the petstore smoke test with it:

```bash
docker build --build-arg OPENAPI_GENERATOR_VERSION=7.27.0 -t timvw-rust:dev .
docker run --rm -v "$PWD:/local" -u "$(id -u):$(id -g)" timvw-rust:dev generate -g timvw-rust \
  -i /local/test-specs/petstore.yaml -o /local/target/petstore --additional-properties=packageName=petstore
cargo build --manifest-path target/petstore/Cargo.toml
```

To build against another openapi-generator version, pass it to Maven first
(`./mvnw -Dopenapi-generator.version=<version> package`) and then to `--build-arg`. CI (`.github/workflows/ci.yml`)
runs these steps on every push and pull request.

The templates are in `src/main/resources/timvw-rust/`. They started from the `rust` templates of openapi-generator
7.27.0 (see [NOTICE](NOTICE)) and are edited by hand like the Java code. None is looked up in the openapi-generator
jar.

## Releasing

1. Make sure `main` is green.
2. Tag and push: `git tag v0.25.0 && git push origin v0.25.0`.
3. The `Release` workflow (`.github/workflows/release.yml`) does the rest:
   - It sets the pom version from the tag and runs `./mvnw -Prelease deploy`. That signs the jar, sources and javadoc
     with GPG and publishes them through the Sonatype Central Portal.
   - It builds one image per supported openapi-generator version and pushes
     `ghcr.io/timvw/openapi-generator-timvw-rust:<version>-og<openapi-generator>`. The default version also gets
     `:<version>` and `:latest`.

Secrets: `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`. The image push uses the
workflow's `GITHUB_TOKEN`. The pom's version on `main` stays a `-SNAPSHOT`, and the workflow never commits.

### Releasing the runtime

`timvw-rust-runtime` (`runtime/`) has its own version and is released with
[release-plz](https://release-plz.dev) by `.github/workflows/runtime-release.yml`:

1. A push to `main` that changes `runtime/` opens or updates a release PR (version bump and `runtime/CHANGELOG.md`).
2. Merge it, then run the `Release runtime` workflow with `release_action=publish`. It publishes to crates.io and
   tags `timvw-rust-runtime-v<version>`, which the generator's `Release` workflow (`v*`) ignores.

Release the runtime before a generator version whose `DEFAULT_RUNTIME_VERSION` needs it. Secret:
`CARGO_REGISTRY_TOKEN`; `BOT_APP_ID` and `BOT_PRIVATE_KEY` (optional) let the release PR trigger CI.

## Layout

```text
src/main/java/be/timvw/openapi/rust/     RustCodegenBase (Rust types, naming, model and operation processing),
                                         TimvwRustCodegen (options and rules) and the rule classes
src/main/java/org/openapitools/codegen/  same-package access to the inline-model resolver and the normalizer
src/main/resources/timvw-rust/           every template the generator renders
src/test/java/, src/test/resources/      JUnit tests and the specs they generate from
src/test/rust/                           Rust tests run against generated crates
docs/                                    guides, the mapping, comparison, decisions, design, roadmap, history
tools/                                   link-mapping-tests.py, check-default-rule.py, check-union-samples.py
test-specs/petstore.yaml                 smoke-test spec
Dockerfile                               the openapi-generator CLI and this jar on a JRE
```

## License

Licensed under either of [Apache License, Version 2.0](LICENSE-APACHE) or [MIT license](LICENSE-MIT)
at your option.
