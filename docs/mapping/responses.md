# Responses Object

Rules for the [Responses Object](https://spec.openapis.org/oas/v3.0.3#responses-object): what an operation's error
responses become. Success responses are the [Response Object](response.md).

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OperationMappingTest` renders
`src/test/resources/operation_mapping.yaml` without options.

### `responses.statusCode`

| | |
| --- | --- |
| OpenAPI | the error responses of an operation: a status code (`404`), a range (`4XX`) or `default` |
| Rust | an enum per operation, `<OperationId>Error`, `#[serde(untagged)]`, with a variant per response holding its schema's type (`Status404(models::Problem)`, `DefaultResponse(models::Problem)`) and `UnknownValue(serde_json::Value)` last. An error status gives `Error::ResponseError(ResponseContent { status, content, entity })`: the `reqwest::StatusCode`, the body as text, and `entity: Option<<OperationId>Error>`, the body decoded into that enum (`None` when it does not decode) |
| Wire | the body decodes to the first variant that accepts it |
| Library | `status` is a `reqwest::StatusCode` |
| Gap | the variant is chosen by the body's shape, not the status: two responses of one type always give the first. Read `status` to tell them apart |
| Options | `boxResponseError` (openai-client-base) makes it `ResponseError(Box<ResponseContent<T>>)` (clippy `result_large_err`) |
| Tests | [`OperationMappingTest#errorResponsesAreAnUntaggedEnumPerOperation`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L148), [`ErrorsAndLintsTest#responseErrorIsBoxed`](../../src/test/java/be/timvw/openapi/rust/ErrorsAndLintsTest.java#L46) |

### `responses.statusCode.transport-error`

| | |
| --- | --- |
| OpenAPI | any operation |
| Rust | `Error<T>` also has `Reqwest(reqwest::Error)` and `ReqwestMiddleware(reqwest_middleware::Error)` (sending or reading failed), `Serde(serde_json::Error)` (a success body did not decode, or a struct parameter did not serialize) and `Io(std::io::Error)` (a file to send could not be read) |
| Library | `Error::Reqwest` and `Error::ReqwestMiddleware` |
| Tests | [`OperationMappingTest#errorResponsesAreAnUntaggedEnumPerOperation`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L148) |
