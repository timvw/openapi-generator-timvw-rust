# Known limitations

What the generator and the runtime do not do (yet), as of generator 0.28 and `timvw-rust-runtime` 0.3.1. The gaps of the
mapping itself (headers, `readOnly`/`writeOnly`, number enums, ...) are listed in
[type-mapping.md](type-mapping.md#known-gaps), each with its rule in the [mapping](mapping/README.md).

## Generated client

- **`RequestOptions` cannot change the path** of one call (async-openai's path override): `base_url` replaces the
  base URL, and the operation decides its path; a `ProviderLayer` with `map_path` rewrites paths for every call.
- **An upload has no JSON form.** A model's file field (`Upload`) is left out of its JSON, and a model with a required
  file field does not decode from JSON.
- **Generated crates declare the runtime's `rust-version` (1.88) but not resolver 3.** On Rust 1.88, resolve with
  `CARGO_RESOLVER_INCOMPATIBLE_RUST_VERSIONS=fallback cargo update` (or `resolver = "3"` in your workspace): the newest
  `uuid` needs a newer Rust.

## Runtime

- **No retry of a failed `fetch` on WebAssembly.** `fetch` does not tell a failure to connect from one after the request
  was sent, so `is_connect()` is always `false` there and such a failure is returned at once.
- **Streamed request bodies are buffered on WebAssembly.** `fetch` takes a body whole, so an `Upload::stream` (or a
  multipart form with one) is read into memory before it is sent.
