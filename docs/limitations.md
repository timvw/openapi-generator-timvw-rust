# Known limitations

What the generator and the runtime do not do (yet), as of generator 0.27 and `timvw-rust-runtime` 0.3. The gaps of the
mapping itself (headers, `readOnly`/`writeOnly`, number enums, ...) are listed in
[type-mapping.md](type-mapping.md#known-gaps), each with its rule in the [mapping](mapping/README.md).

## Generated client

- **Multipart file unions hold a `PathBuf`.** A file parameter is a `timvw_rust_runtime::Upload` (tower), but a
  multipart property whose union has a file variant (`multipartHelper`) keeps `std::path::PathBuf` in its generated
  model: it is streamed from disk like any path, and cannot carry bytes or a stream. On WebAssembly, where there is no
  file system, such a request fails when it is built ([HTTP stack guide](guides/http-stack.md#webassembly)). Planned
  for 0.28: an `Upload` there too.
- **`httpStack=reqwest-middleware` is frozen.** It renders what 0.25 rendered: no `Upload`s (file parameters are
  `PathBuf`s), no `SecretString` credentials, no per-call `RequestOptions`, no WebAssembly support. New features go
  to the default `tower` stack.
- **No per-call options without builders.** `.options(&RequestOptions)` needs `bonBuilders` (the default); with
  `bonBuilders=false` the functions take no such argument.
- **`RequestOptions` cannot change the path or the base URL** of one call (async-openai's path override); a
  `ProviderLayer` on the client does that for every call.

## Runtime

- **No request timeout on WebAssembly.** `Client::default()` sets no timeout. Natively, add a
  `tower::timeout::TimeoutLayer` or set reqwest's timeouts on a `reqwest::Client` (see
  [timeouts](../runtime/README.md#timeouts)). On WebAssembly neither works: connect and read timeouts belong to the
  host's `fetch`, and tower's `TimeoutLayer` sleeps with tokio's timer, which is not there. Planned for runtime 0.3.1:
  an opt-in `timeout::TimeoutLayer` that uses the host's timer.
- **No retry of a failed `fetch` on WebAssembly.** `fetch` does not tell a failure to connect from one after the request
  was sent, so `is_connect()` is always `false` there and such a failure is returned at once.
- **Streamed request bodies are buffered on WebAssembly.** `fetch` takes a body whole, so an `Upload::stream` (or a
  multipart form with one) is read into memory before it is sent.
- **`cargo doc` without the default features has broken links.** Built without `fs`, the docs of `Upload` and
  `Response` link to `Upload::path`, `Upload::file`, `Upload::reader` and `Response::save_to`, which are not compiled
  then: 5 unresolved links, an error with `RUSTDOCFLAGS="-D warnings"`. docs.rs builds with every feature, so the
  published docs are complete. Planned for runtime 0.3.1.
