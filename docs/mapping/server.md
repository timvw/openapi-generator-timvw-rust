# Server Object

Rules for the [Server Object](https://spec.openapis.org/oas/v3.0.3#server-object).

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OperationMappingTest` renders
`src/test/resources/operation_mapping.yaml` without options.

### `server.url`

| | |
| --- | --- |
| OpenAPI | `servers`, the first entry's `url` |
| Rust | `Configuration::default().base_path`; every request URL is `base_path` followed by the operation's path. Other servers, server variables and operation-level `servers` are not used |
| Library | `Configuration` holds the client, a `reqwest_middleware::ClientWithMiddleware`, so retries and tracing plug in as middleware |
| Tests | [`OperationMappingTest#theFirstServerIsTheBasePathAndTheVersionNamesTheUserAgent`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L167) |
