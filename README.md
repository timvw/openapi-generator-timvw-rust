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
  ghcr.io/timvw/openapi-generator-timvw-rust:0.28.0-og7.27.0 generate \
  -g timvw-rust \
  -i /local/openapi.yaml \
  -o /local/my-client \
  --additional-properties=packageName=my-client
```


No options are needed: what the generator renders without them is the [mapping](docs/mapping/README.md). The
[options](docs/options.md) add functions, choose the HTTP stack or adapt a spec. Maven and the openapi-generator Maven
plugin are in [getting started](docs/guides/getting-started.md#running-the-generator).

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

Current releases: generator **0.28.0** (`ghcr.io/timvw/openapi-generator-timvw-rust:0.28.0-og7.27.0`,
`be.timvw:openapi-generator-timvw-rust:0.28.0`, built and tested against openapi-generator 7.27.0) and
[`timvw-rust-runtime`](runtime/README.md) **0.3.1**, which the generated `Cargo.toml` asks for (`^0.3.1`).

## What you get

- An async function per operation, with a [bon](https://bon-rs.com) builder, and models that decode what the server
  sends ([getting started](docs/guides/getting-started.md), [type mapping](docs/type-mapping.md)).
- A client that is a [tower](https://docs.rs/tower) service from [`timvw-rust-runtime`](runtime/README.md): reqwest
  unless you replace it, with tower layers for timeouts, logging, rate limits or middleware of your own
  ([HTTP stack](docs/guides/http-stack.md)).
- Retries by default: `429`, `408`, `5xx` and failed connections, honouring `Retry-After` up to 60 s, with jittered
  backoff otherwise ([retries](runtime/README.md#retries)).
- Credentials as `secrecy::SecretString`s, redacted in `Debug`
  ([credentials](docs/guides/http-stack.md#credentials)).
- Headers and query parameters for one call: `.options(&RequestOptions)` on every operation
  ([guide](docs/guides/http-stack.md#headers-and-query-parameters-for-one-call)).
- The same API at another provider (an OpenAI-compatible service, a proxy, a deployment) with `ProviderLayer`
  ([providers](runtime/README.md#providers)).
- File parameters as `Upload`s (paths, bytes, readers, streams), streamed with a `Content-Length` and sent again on a
  retry; binary responses saved to a file with `save_to` ([uploads and downloads](docs/guides/http-stack.md#uploads-and-downloads)).
- Typed `text/event-stream` responses with an idle timeout (`typedEventStreams`,
  [guide](docs/guides/http-stack.md#typed-event-streams)).
- Typed errors per status, with the response headers ([errors](docs/guides/errors.md)), and `validate()` for the
  spec's constraints ([validation](docs/guides/validation.md)).
- A Cargo feature per tag or group of operations, with types-only twins (`apiFeatures`,
  [guide](docs/guides/api-features.md)).
- A crate that builds for WebAssembly (`wasm32-unknown-unknown`) as it is
  ([guide](docs/guides/http-stack.md#webassembly)).

## Documentation

| | |
| --- | --- |
| [Getting started](docs/guides/getting-started.md) | the generated crate: calling an operation, models, errors, validation; running the generator (Docker, Maven), a hand-kept `Cargo.toml` |
| [Options](docs/options.md) | every option, with its default, an example and its tests; the names the generator rejects |
| [HTTP stack](docs/guides/http-stack.md) | `httpStack`: the tower client, retries, providers, per-call options, credentials, uploads and downloads, event streams, WebAssembly |
| [timvw-rust-runtime](runtime/README.md) | the runtime: retries, timeouts, logging, middleware, providers, rate limits, uploads, its features |
| [Errors](docs/guides/errors.md) | the `Error` type, status variants, headers, text errors |
| [Validation](docs/guides/validation.md) | `validate()`: what it checks, what it skips, the off switch |
| [API features](docs/guides/api-features.md) | `apiFeatures`: a Cargo feature per API group, and types-only features |
| [Response functions](docs/guides/response-functions.md) | `responseFunctions`: status, (typed) headers and partial decoding of a success |
| [Migration](docs/guides/migration.md) | upgrading release by release (0.20 to 0.28), and coming from stock `-g rust` |
| [Type mapping](docs/type-mapping.md), [mapping rules](docs/mapping/README.md) | what each OpenAPI construct becomes: one overview table, and the rules per specification object, each with its tests |
| [Known limitations](docs/limitations.md) | what the generator and the runtime do not do yet |
| [Comparison](docs/comparison/README.md) | stock `-g rust`, timvw-rust and progenitor side by side on one spec |
| [Decisions](docs/decisions.md), [design](docs/design/partial-decoding-and-ownership.md) | the mapping changes of 0.21, each with what others do; design notes with measurements |
| [History](docs/history.md) | how the generator got here, release by release |
| [Development](docs/development.md), [releasing](docs/releasing.md) | building and testing, CI, the layout; releasing the generator and the runtime |

## License

Licensed under either of [Apache License, Version 2.0](LICENSE-APACHE) or [MIT license](LICENSE-MIT)
at your option.
