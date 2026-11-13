# Changelog

All notable changes to `timvw-rust-runtime` are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.4.0] - Unreleased

### Added

- `RequestOptions::base_url`: sends one call to another base URL (scheme, host, port and path prefix), keeping the
  operation's path and the query. Fallible like `header` and `query`; `RequestOptions::base` reads it back, and
  `is_empty` counts it. `RequestBuilder::base` declares the base a request's URL was built on (a generated client's
  `Configuration.base_path`, which generated code declares), so the per-call base replaces exactly that prefix; without
  a declared base the whole path is kept.

### Changed

- Per-call `RequestOptions` win over `ProviderLayer`. `RequestBuilder::options` records what the options set (a
  request extension, kept by `RetryLayer` on a retry), and the layer no longer replaces a header or a query parameter
  of those names: with an `api-version` in both, the call's own is sent, once. A call with a per-call base URL is not
  moved by `rewrite_from`; when that base is the provider's own (or under it) the layer still maps the path and adds
  its headers and query, any other base gets nothing from the layer. Before, the layer replaced headers and query
  parameters of the same name whatever the options set.

## [0.3.1] - 2026-11-13

### Added

- `timeout::TimeoutLayer` (feature `time`): fails a request whose response does not arrive within a time limit, with an
  `Error` whose `is_timeout()` is true. It waits with the crate's timer, tokio's natively and the host's `setTimeout`
  on WebAssembly, where `fetch` has no timeout and tower's `TimeoutLayer` does not work. Outside `RetryLayer` it bounds
  the whole call, inside it each attempt. Opt-in: clients still have no timeout by default.
- `RetryPolicy::retry_timeouts` (default `false`): retry a request that timed out (a `TimeoutLayer` inside the
  retries, tower's, or a reqwest timeout), like a failure to connect.
- `impl PartialEq for Upload`: equal bytes, the same path, or the same stream or `from_fn` function (an upload and its
  clones), with the same file name, media type, length, slice and `buffer_for_retry` limit. Generated multipart unions
  with a file variant hold an `Upload` and derive `PartialEq`.

### Fixed

- The docs build with any features and on any target (`cargo doc --no-default-features`, WebAssembly): links to items
  a feature set or target leaves out are plain text there.

## [0.3.0] - 2026-11-13

### Added

- `Upload::name` and `Upload::media_type`: the file name and media type an upload is sent with (set, or taken from a
  path).

- `ProviderLayer` (`provider::ProviderLayer`): sends requests to another provider of the same API. It replaces the
  base URL (the provider's own, or a generated base declared with `rewrite_from`) keeping the rest of the path and the
  query, rewrites the path with `map_path`, and sets headers (`header`, `sensitive_header`, `headers`) and query
  parameters (`query`), each replacing the request's of that name. Requests to other hosts pass unchanged.
  Provider-neutral: no presets. `rewrite_uri` shows the URL a request is sent to.
- `RetryPolicy::server_directed_429` (default `false`): retry a `429` only when it has `Retry-After` or
  `retry-after-ms`.
- `RetryPolicy` reads `Retry-After` as an HTTP date too (the `retry` feature depends on `httpdate`); a date in the past
  retries at once.
- `Upload`: something to send as a request body (`RequestBuilder::body(upload)`) or a multipart part
  (`multipart::Part::upload`). Bytes (`Upload::bytes`, `Bytes`, `Vec<u8>`, `&'static [u8]`) are sent again by a retry
  without a copy. A file (`Upload::path`, `Upload::file`, `PathBuf`, `&Path`) is streamed from disk with tokio, named
  after the file, typed from its extension (`mime_guess`), and opened again for each attempt. `Upload::from_fn` makes
  a fresh stream per attempt; `Upload::reader` and `Upload::stream` are read once, unless `buffer_for_retry(limit)`
  holds a short one in memory. `file_name`, `mime` and `len` set the part's name, media type and length, `slice`
  sends a range (a seek for a file), `resolve` reads a path's length (and fails on a missing file),
  `content_length` and `is_replayable` tell what will be sent.
- `Body::try_clone` makes a fresh copy of a body made from a replayable `Upload` (and of a multipart form whose every
  part is replayable), so `RetryPolicy` retries a file upload: the file is opened again. `RetryPolicy`'s API is
  unchanged.
- A multipart form whose every part has a known length (bytes, resolved files) is sent with a `Content-Length` instead
  of chunked; so is a body made from an `Upload` of known length.
- `Response::save_to(path)`: streams the body into a temporary file next to `path`, flushes it to disk and renames it
  to `path`, which never holds part of a body; the temporary file is removed on failure.
  `Response::into_async_read()`: the body as a tokio `AsyncRead`.
- `sse::Events::idle_timeout(duration)`: ends a stream with an error whose `is_timeout()` is true when no bytes arrive
  for `duration`; every chunk restarts the clock, and the whole stream has no limit.
- `Error::is_io()`: a file could not be read or written (an upload, `save_to`).
- Features `fs` (default: files, readers, `save_to`, `into_async_read`; tokio's `fs` and `io-util`, `mime_guess`) and
  `time` (`idle_timeout` and the sleeps of retries; turned on by `retry`).
- WebAssembly (`wasm32-unknown-unknown`, `#[cfg(target_family = "wasm")]`): the crate builds there with any features
  (`default-features = false, features = ["reqwest", "retry"]` is the useful set). reqwest sends with the host's
  `fetch` (a streamed request body is read into memory first; responses stream). Retries back off and honour
  `Retry-After` with the host's `setTimeout` (`gloo-timers`) and `performance.now()`/`Date.now()` clocks (`web-time`),
  as does `Events::idle_timeout`; `TraceLayer` measures with `performance.now()`. A failed `fetch` is not retried
  (`is_connect()` is always `false` there). `rustls`/`native-tls` add nothing but reqwest; `fs` adds only
  `Upload::path` and `From<PathBuf>`/`From<&Path>`, which fail with an `Unsupported` I/O error when resolved or sent.
  tokio and the file system are not dependencies there.
- `MaybeSend` and `MaybeSync`: `Send` and `Sync` natively, implemented by every type on WebAssembly, where the
  host's futures and streams are not `Send`.
- `LocalBoxCloneService`: a boxed, cloneable service that need not be `Send`, nor its future; `HttpService` on
  WebAssembly.

### Changed

- `RetryPolicy` reads the server's wait once into a deadline, counted from when the response arrived, and sleeps until
  it (`tokio::time::sleep_until`): time spent reading a `429` body for `RetryLayer::permanent_429` no longer adds to
  the wait. `max_retry_after` compares with the time left until the deadline.
- The `Send`/`Sync` bounds of `Client::from_service`, `Client::layer`, `Body::wrap`, `Body::wrap_stream`,
  `Upload::stream`, `Upload::from_fn`, `sse::Events::from_bytes`, `TraceLayer`'s and `RetryLayer`'s services, and the
  `Send` of `Response::bytes_stream` are `MaybeSend`/`MaybeSync`: the same natively, none on WebAssembly. `HttpService`
  is a `LocalBoxCloneService` on WebAssembly.
- `tokio` and `mime_guess` are no longer dependencies on WebAssembly, whatever the features.

## [0.2.0] - 2026-11-13

### Added

- `retry::RetryLayer`: retries with a `RetryPolicy` like `tower::retry::RetryLayer`, and with `permanent_429(f)` reads
  the body of each `429` (up to `body_limit`, 64 KiB by default) to not retry a permanent one; the body is put back
  for the caller. `Client::default()`, `Client::new()` and `Client::from_reqwest` use it, without reading bodies.
- `retry::openai_insufficient_quota`: OpenAI's exhausted quota (`error.code` or `error.type` `insufficient_quota`),
  for `RetryLayer::permanent_429`. Opt-in: the default client stays provider-neutral.
- `retry::Permanent`: a response extension that `RetryPolicy` never retries.
- `RetryPolicy::max_retry_after` (default 60 s): the longest `Retry-After` the policy waits for; a response asking for
  longer is returned at once.
- `RetryPolicy::jitter` (default `true`): each backoff delay is picked at random between half and all of it.
- `RetryPolicy` reads OpenAI's `retry-after-ms` header (before `Retry-After`), and fractional seconds in `Retry-After`.
- `secrecy` is a dependency and re-exported (`timvw_rust_runtime::secrecy`, and `SecretString` and `ExposeSecret` at
  the crate root): generated clients keep their credentials in `SecretString`s.
- `RequestBuilder::sensitive_header`: sets a header, replacing an earlier value, and marks it sensitive, as
  `bearer_auth` and `basic_auth` do. Generated clients send an api key and the `Cookie` header with it.
- `RequestOptions`: extra headers and query parameters for one call (`header`, `headers`, `query`), and
  `RequestBuilder::options` to apply them: each header replaces the request's values of that name, the query
  parameters are appended. Generated clients (`httpStack=tower`) take them on every operation's builder,
  `.options(&options)`, and apply them after the headers, query and authentication they set.

### Changed

- `RetryPolicy::max_delay` caps the exponential backoff only, no longer `Retry-After`: a server that asks for 20 s
  now gets 20 s, not 8 s. A `Retry-After` above `max_retry_after` (60 s) is no longer retried after 8 s but returned.
- The exponential backoff is jittered by default; `.jitter(false)` restores exact delays.

## [0.1.0] - 2026-11-12

### Added

- `Client`: sends the requests of a generated client through a tower service (`HttpService`), reqwest with retries by
  default (`Client::default`, `Client::new`, `Client::from_reqwest`);
  `Client::layer` adds tower layers, `Client::from_service` takes any service.
- `RequestBuilder` and `Response` with the methods of reqwest's that generated clients use.
- `Body`: bytes (clonable, so retryable) or a stream.
- `multipart::{Form, Part}`: `multipart/form-data` encoding.
- `ReqwestService` (feature `reqwest`).
- `retry::RetryPolicy` (feature `retry`): retries `429`, `408`, `5xx` and connect failures, honouring `Retry-After`.
- `trace::TraceLayer` (feature `tracing`): a `tracing` span per request (method, URL without query, status) and an
  event per response and per retry.
- `Client::layer` and `Client::from_service` take services that are `Clone + Send` but not `Sync`, such as
  `ConcurrencyLimit` or a `Buffer`ed `RateLimit`.
- `sse`: a `text/event-stream` parser (`Decoder`, `Event`), `Response::events()` and `Events::json::<T>()` to decode
  each event's `data:` to a type of your own. Generated clients use it for their typed event streams.
- The README is a guide to retries, timeouts, logging, middleware, limits and testing; its examples are doctests.
