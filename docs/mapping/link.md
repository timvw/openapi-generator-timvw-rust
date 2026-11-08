# Link Object

Rules for the [Link Object](https://spec.openapis.org/oas/v3.0.3#link-object). The [baseline](README.md#baseline) options apply.

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OtherObjectsTest` renders
`src/test/resources/other_objects.yaml` with the baseline options.

### `link.operationId`

| | |
| --- | --- |
| OpenAPI | `links` of a response |
| Rust | nothing: the caller passes the value to the linked operation itself |
| Tests | [`OtherObjectsTest#linksAreIgnored`](../../src/test/java/be/timvw/openapi/rust/OtherObjectsTest.java#L90) |
