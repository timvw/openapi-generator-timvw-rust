# Callback Object

Rules for the [Callback Object](https://spec.openapis.org/oas/v3.0.3#callback-object). The [baseline](README.md#baseline) options apply.

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OtherObjectsTest` renders
`src/test/resources/other_objects.yaml` with the baseline options.

### `callback.expression`

| | |
| --- | --- |
| OpenAPI | `callbacks` of an operation: requests the API makes to a URL the client gave it |
| Rust | no function: the client does not receive requests. The schemas a callback uses are models like any other |
| Tests | [`OtherObjectsTest#aCallbackHasNoFunctionButItsSchemasAreModels`](../../src/test/java/be/timvw/openapi/rust/OtherObjectsTest.java#L82) |
