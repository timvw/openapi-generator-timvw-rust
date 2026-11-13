# Changelog

All notable changes to `timvw-rust-runtime` are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
