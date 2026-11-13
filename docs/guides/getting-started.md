# Guide: getting started

What the generated crate looks like and how to call it: an operation, the models, the errors, validation. Then how to
run the generator (Docker, Maven) and what a hand-kept `Cargo.toml` needs. The [options](../options.md) change parts of
this; the [HTTP stack guide](http-stack.md) covers the client: retries, providers, per-call options, uploads, event
streams and WebAssembly.


The examples use a small API with an `Items` tag (it is in
[`src/test/resources/docs_examples.yaml`](../../src/test/resources/docs_examples.yaml)). Every Rust example on this page and
in the other guides, except those marked as code of an older release or abridged signatures, is compiled by the tests.

```text
my-client/
  Cargo.toml          bon, serde, serde_json, timvw-rust-runtime, ... (see below)
  src/lib.rs
  src/apis/           configuration.rs, mod.rs (Error, ResponseContent), items_api.rs: one module per tag
  src/models/         one file per schema, mod.rs, validation.rs (the Validate trait)
  docs/               a Markdown page per model and API
```

## Calling an operation

Each operation is an async function with a [bon](https://bon-rs.com) builder. You set the arguments by name and call
`call()`:

```rust
use my_client::apis::configuration::Configuration;
use my_client::apis::items_api;

let mut configuration = Configuration::new();
configuration.base_path = "https://api.example.com/v1".to_owned();
configuration.bearer_access_token = Some("secret".into());

let page = items_api::list_items()
    .configuration(&configuration)
    .limit(10)
    .call()
    .await?;
for item in &page.data {
    println!("{} {}", item.id, item.name);
}
```

The credentials (`bearer_access_token`, `oauth_access_token`, the password of `basic_auth`, the key of `api_key`) are
`SecretString`s from [secrecy](https://docs.rs/secrecy): `Some("secret".into())` or `Some(SecretString::from(token))`
sets one, `Debug` prints `[REDACTED]`, and the generated code reads them only to build the request
([more](http-stack.md#credentials)).

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
use timvw_rust_runtime::{Client, ReqwestService};

let mut configuration = Configuration::new(); // retries with RetryPolicy::default()
let policy = RetryPolicy::new(5).max_delay(Duration::from_secs(30));
configuration.client = Client::from_service(ReqwestService::default()).layer(RetryLayer::new(policy));
```

`Configuration::new()` retries `429`, `408` and `5xx` responses and failed connections, honouring `Retry-After` up to
60 s and backing off with jitter otherwise (`RetryPolicy::default()`: 3 retries); the example retries more. A `429`
whose body says it is permanent (OpenAI's `insufficient_quota`) is not retried with
`timvw_rust_runtime::retry::RetryLayer::permanent_429`, an opt-in. The
[runtime's guide](../../runtime/README.md) covers retries, timeouts, logging (`trace::TraceLayer`, feature `tracing`),
headers and authentication layers, and rate limits. Headers and query parameters for one call go on its builder,
`.options(&options)` with an `apis::RequestOptions`
([guide](http-stack.md#headers-and-query-parameters-for-one-call)). The client builds for WebAssembly
(`wasm32-unknown-unknown`) as it is ([guide](http-stack.md#webassembly)).

## Models

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

[docs/type-mapping.md](../type-mapping.md) has the whole table.

## Errors

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

See [errors](errors.md).

## Validation

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

See [validation](validation.md).

## Running the generator

| What | Where |
| --- | --- |
| Docker | `ghcr.io/timvw/openapi-generator-timvw-rust:<version>-og<openapi-generator version>` ([tags](https://github.com/timvw/openapi-generator-timvw-rust/pkgs/container/openapi-generator-timvw-rust)); `<version>` and `latest` point at the newest openapi-generator |
| Maven | `be.timvw:openapi-generator-timvw-rust` on Maven Central |
| Generator name | `timvw-rust` (`-g timvw-rust`) |
| openapi-generator | built and tested against the version in the image tag (`-og<version>`), which the image bundles; another minor version logs a warning |

The image is a drop-in for `openapitools/openapi-generator-cli`: same arguments, same `/local` mount, same
`JAVA_OPTS`. Options go on the command line or, for lists and comma-separated values, in a `-c` config file
([options](../options.md#passing-options)):

```bash
docker run --rm -v "$PWD:/local" -u "$(id -u):$(id -g)" \
  ghcr.io/timvw/openapi-generator-timvw-rust:latest generate \
  -g timvw-rust \
  -i /local/openapi.yaml \
  -o /local/my-client \
  -c /local/generator-config.json
```

In CI, pin `:<version>-og<openapi-generator version>` from the [list of tags](https://github.com/timvw/openapi-generator-timvw-rust/pkgs/container/openapi-generator-timvw-rust) instead of `:latest`.

```json
{
  "packageName": "my-client",
  "reqwestDefaultFeatures": "rustls",
  "typedEventStreams": true,
  "apiFeaturesFile": "/local/api-features.yaml"
}
```

With the openapi-generator Maven plugin
([openapi-generator-maven-plugin](https://github.com/OpenAPITools/openapi-generator/tree/master/modules/openapi-generator-maven-plugin)),
add the generator as a dependency of the plugin:

```xml
<plugin>
  <groupId>org.openapitools</groupId>
  <artifactId>openapi-generator-maven-plugin</artifactId>
  <!-- the openapi-generator version the timvw-rust release was built against (its image tag's -og<version>) -->
  <version>A.B.C</version>
  <dependencies>
    <dependency>
      <groupId>be.timvw</groupId>
      <artifactId>openapi-generator-timvw-rust</artifactId>
      <!-- the latest release on Maven Central -->
      <version>X.Y.Z</version>
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
| `timvw-rust-runtime` | always, `default-features = false`; with its `fs` feature when a file is uploaded (a file parameter, a multipart union with a file variant, or a model with a file field), its `time` feature with `typedEventStreams` and an operation that streams |
| `bon` | `bonBuilders` (the default) |
| `serde_with` | a nullable optional property (`Option<Option<T>>`) or a `format: byte` property |
| `chrono` | a `date` or `date-time` anywhere (model, parameter, union variant), with `useChrono` (the default) |
| `uuid` | a `format: uuid` anywhere; its `js` feature too on `wasm32-unknown-unknown` (a `[target.'cfg(all(target_family = "wasm", target_os = "unknown"))'.dependencies]` table) |
| `regex` | a `pattern` that `validate()` checks |
| `futures-core` | `typedEventStreams` with an operation that streams |
| `serde_path_to_error` | `useSerdePathToError` |

With `apiFeatures`, the HTTP dependency (`timvw-rust-runtime`) is `optional = true` (unless a model every feature set
compiles holds an upload), and the generated `[features]` section lists every group: copy both.

The rule: on every upgrade of the generator (and every spec update), generate once into an empty directory and
compare its `Cargo.toml` with yours.
