# Known limitations

What the generator and the runtime do not do (yet), as of the current releases (see the badges in the
[README](../README.md)). The gaps of the
mapping itself (headers, `readOnly`/`writeOnly`, number enums, ...) are listed in
[type-mapping.md](type-mapping.md#known-gaps), each with its rule in the [mapping](mapping/README.md).

## Generated client

- **`RequestOptions` cannot change the path** of one call (async-openai's path override): `base_url` replaces the
  base URL, and the operation decides its path; a `ProviderLayer` with `map_path` rewrites paths for every call.
- **An upload has no JSON form.** A model's file field (`Upload`) is left out of its JSON, and a model with a required
  file field does not decode from JSON.
- **Generated crates declare the runtime's `rust-version` (that of [`runtime/Cargo.toml`](../runtime/Cargo.toml)) but not
  resolver 3.** On that Rust, resolve with
  `CARGO_RESOLVER_INCOMPATIBLE_RUST_VERSIONS=fallback cargo update` (or `resolver = "3"` in your workspace): the newest
  `uuid` needs a newer Rust.

## Generated server

- **Not every operation is served.** A request body that is both a multipart and an urlencoded form, a query
  parameter whose type has no text form (an object that is not deepObject), a path parameter that is not a whole
  segment, or a second operation with the same path, method and fixed query leaves the operation out of the server,
  with a comment and a warning ([guide](guides/server.md#what-the-server-does-not-serve-yet)). Every operation of the
  OpenAI spec is served.
- **Bodies are read whole.** A request body, a multipart form included, is held in memory before the implementation
  is called, within axum's body limit; a response can stream (`EventStream`, `Binary::stream`).
- **Webhooks are sent once.** `serverWebhooks` sends a webhook through the endpoint's client, with its short
  retries; sending again over hours or days, as OpenAI does, needs a queue and is the application's. The server does
  not receive webhooks.
- **Not every security scheme is read.** With `serverAuthentication`, the credentials of `mutualTLS` and of `http`
  schemes other than `bearer` and `basic` are not read; their requirements still reach the `Authenticator`
  ([guide](guides/server.md#authentication-serverauthentication)).
- **Validation leaves headers out.** `serverValidation` checks path and query parameters and bodies; header and cookie
  parameters are not decoded, so not checked ([guide](guides/server.md#validation-servervalidation)).
- **One trait per API module (tag).** With an `apiFeaturesFile` group that takes part of a module, the module's trait
  keeps the methods of the enabled groups, gated one by one.

## Runtime

- **No retry of a failed `fetch` on WebAssembly.** `fetch` does not tell a failure to connect from one after the request
  was sent, so `is_connect()` is always `false` there and such a failure is returned at once.
- **Streamed request bodies are buffered on WebAssembly.** `fetch` takes a body whole, so an `Upload::stream` (or a
  multipart form with one) is read into memory before it is sent.
