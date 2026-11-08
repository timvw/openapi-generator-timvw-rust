# Example Object

Rules for the [Example Object](https://spec.openapis.org/oas/v3.0.3#example-object). The [baseline](README.md#baseline) options apply. This also covers the `example` and `examples` fields of the Schema, Parameter and Media Type Objects.

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OtherObjectsTest` renders
`src/test/resources/other_objects.yaml` with the baseline options.

### `example.value`

| | |
| --- | --- |
| OpenAPI | `example`, `examples` on a schema, property, parameter or media type |
| Rust | nothing: not in the types, their documentation or the generated Markdown |
| Tests | [`OtherObjectsTest#examplesAreIgnored`](../../src/test/java/be/timvw/openapi/rust/OtherObjectsTest.java#L97) |
