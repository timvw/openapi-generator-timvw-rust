# Header Object

Rules for the [Header Object](https://spec.openapis.org/oas/v3.0.3#header-object).

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OtherObjectsTest` renders
`src/test/resources/other_objects.yaml` without options.

### `header.schema`

| | |
| --- | --- |
| OpenAPI | `headers` of a response |
| Rust | no typed header fields. An error response's `ResponseContent` has `headers: reqwest::header::HeaderMap`, every header of the response, declared or not (see [`responses.statusCode`](responses.md#responsesstatuscode)). `{:?}` of such an error prints every header, which can include `set-cookie` or tokens; its `Display` does not print them. A JSON or text success returns only its decoded body, except with `supportMultipleResponses`, whose success `ResponseContent` has the `headers` too. A binary response returns the whole `reqwest::Response`, whose headers the caller can read |
| Library | `reqwest::header::HeaderMap` |
| Options | `supportMultipleResponses`: the success `ResponseContent` has `headers` as well. `responseFunctions`: `<operation>_response` returns the headers of a success in its `ApiResponse` ([`response.content.response-function`](response.md#responsecontentresponse-function)) |
| Gap | the headers of a JSON or text success cannot be read without `supportMultipleResponses` or `responseFunctions` |
| Tests | [`OtherObjectsTest#declaredResponseHeadersGetNoTypedAccessor`](../../src/test/java/be/timvw/openapi/rust/OtherObjectsTest.java#L76), [`ErrorStatusTest#whatItDecodesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/ErrorStatusTest.java#L160) |
