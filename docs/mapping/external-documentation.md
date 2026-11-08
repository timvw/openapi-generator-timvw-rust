# External Documentation Object

Rules for the [External Documentation Object](https://spec.openapis.org/oas/v3.0.3#external-documentation-object). The [baseline](README.md#baseline) options apply.

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OtherObjectsTest` renders
`src/test/resources/other_objects.yaml` with the baseline options.

### `externalDocumentation.url`

| | |
| --- | --- |
| OpenAPI | `externalDocs` on the spec, a tag, an operation or a schema |
| Rust | nothing: no link in the documentation |
| Tests | [`OtherObjectsTest#externalDocumentationIsIgnored`](../../src/test/java/be/timvw/openapi/rust/OtherObjectsTest.java#L111) |
