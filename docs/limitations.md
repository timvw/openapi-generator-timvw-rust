# Known limitations

What the generator and the runtime do not do (yet), as of generator 0.28 and `timvw-rust-runtime` 0.3.1. The gaps of the
mapping itself (headers, `readOnly`/`writeOnly`, number enums, ...) are listed in
[type-mapping.md](type-mapping.md#known-gaps), each with its rule in the [mapping](mapping/README.md).

## Generated client

- **`httpStack=reqwest-middleware` is frozen.** It renders what 0.25 rendered: no `Upload`s (file parameters are
  `PathBuf`s), no `SecretString` credentials, no per-call `RequestOptions`, no WebAssembly support. New features go
  to the default `tower` stack.
- **No per-call options without builders.** `.options(&RequestOptions)` needs `bonBuilders` (the default); with
  `bonBuilders=false` the functions take no such argument.
- **`RequestOptions` cannot change the path or the base URL** of one call (async-openai's path override); a
  `ProviderLayer` on the client does that for every call.
- **Multipart body models keep `PathBuf`.** A multipart request body schema is also generated as a model (e.g.
  `CreateContainerFileBody`); its binary properties stay `PathBuf`. The operation itself takes `timvw_rust_runtime::Upload`, so this only matters to code
  that builds that model directly.

## Runtime

- **No retry of a failed `fetch` on WebAssembly.** `fetch` does not tell a failure to connect from one after the request
  was sent, so `is_connect()` is always `false` there and such a failure is returned at once.
- **Streamed request bodies are buffered on WebAssembly.** `fetch` takes a body whole, so an `Upload::stream` (or a
  multipart form with one) is read into memory before it is sent.
