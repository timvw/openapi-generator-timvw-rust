# Changelog

All notable changes to `timvw-rust-runtime` are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `secrecy` is a dependency and re-exported (`timvw_rust_runtime::secrecy`, and `SecretString` and `ExposeSecret` at
  the crate root): generated clients keep their credentials in `SecretString`s.
- `RequestBuilder::sensitive_header`: sets a header, replacing an earlier value, and marks it sensitive, as
  `bearer_auth` and `basic_auth` do. Generated clients send an api key and the `Cookie` header with it.

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
