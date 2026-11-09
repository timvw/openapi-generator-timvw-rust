# Header Object

Rules for the [Header Object](https://spec.openapis.org/oas/v3.0.3#header-object).

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OtherObjectsTest` renders
`src/test/resources/other_objects.yaml` without options.

### `header.schema`

| | |
| --- | --- |
| OpenAPI | `headers` of a response |
| Rust | nothing: a JSON or text response returns only its decoded body, and an error response only `status`, the body and the decoded entity. A binary response returns the whole `reqwest::Response`, whose headers the caller can read |
| Gap | the headers of a JSON or text response cannot be read |
| Tests | [`OtherObjectsTest#responseHeadersAreNotExposed`](../../src/test/java/be/timvw/openapi/rust/OtherObjectsTest.java#L76) |
