# XML Object

Rules for the [XML Object](https://spec.openapis.org/oas/v3.0.3#xml-object). The [baseline](README.md#baseline) options apply.

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OtherObjectsTest` renders
`src/test/resources/other_objects.yaml` with the baseline options.

### `xml.name`

| | |
| --- | --- |
| OpenAPI | `xml` on a schema or property |
| Rust | nothing: bodies are JSON (or form, multipart, text and bytes); XML is neither sent nor decoded |
| Tests | `OtherObjectsTest#xmlIsIgnored` |
