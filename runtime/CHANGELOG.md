# Changelog

All notable changes to `timvw-rust-runtime` are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0](https://github.com/timvw/openapi-generator-timvw-rust/releases/tag/timvw-rust-runtime-v0.1.0) - 2026-11-12

### Added

- [**breaking**] tower HTTP stack via timvw-rust-runtime (default), httpStack option ([#93](https://github.com/timvw/openapi-generator-timvw-rust/pull/93))

### Added

- `Client`: sends the requests of a generated client through a tower service (`HttpService`), reqwest by default;
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
- The README is a guide to retries, timeouts, logging, middleware, limits and testing; its examples are doctests.
