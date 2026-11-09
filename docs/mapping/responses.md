# Responses Object

Rules for the [Responses Object](https://spec.openapis.org/oas/v3.0.3#responses-object): what an operation's error
responses become. Success responses are the [Response Object](response.md).

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OperationMappingTest` renders
`src/test/resources/operation_mapping.yaml` without options.

### `responses.statusCode`

| | |
| --- | --- |
| OpenAPI | the error responses of an operation: a status code (`404`), a range (`4XX`) or `default` |
| Rust | an enum per operation, `<OperationId>Error`, `#[serde(untagged)]`, with a variant per response holding its schema's type (`Status404(models::Problem)`, `DefaultResponse(models::Problem)`) and `UnknownValue(serde_json::Value)` last. An error status gives `Error::ResponseError(ResponseContent { status, content, entity })`: the `reqwest::StatusCode`, the body as text, and `entity: Option<<OperationId>Error>`, the body decoded as the variant its status picks (see Wire) |
| Wire | the status picks the variant: the response with that exact code (`404`), else the response of its range (`4XX` is 400 to 499), else `default`; the body decodes as that response's type. A response without a body gives its variant (`Status409()`) whatever the body. A body that does not decode as the chosen type, or a status no response lists in an operation without `default`, is `UnknownValue` when the body is JSON, and `entity` is `None` when it is not. Two responses of one type each get their own variant. The enum stays `#[serde(untagged)]`, so it serializes as the body it holds and still derives `Deserialize` |
| Library | `status` is a `reqwest::StatusCode` |
| Options | `boxResponseError` (openai-client-base) makes it `ResponseError(Box<ResponseContent<T>>)` (clippy `result_large_err`) |
| Tests | [`OperationMappingTest#errorResponsesAreAnUntaggedEnumPerOperation`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L149), [`ErrorStatusTest#theStatusPicksTheVariantExactCodesBeforeRangesThenDefault`](../../src/test/java/be/timvw/openapi/rust/ErrorStatusTest.java#L55), [`ErrorStatusTest#withoutDefaultAnUnlistedStatusIsUnknownValue`](../../src/test/java/be/timvw/openapi/rust/ErrorStatusTest.java#L69), [`ErrorStatusTest#whatItDecodesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/ErrorStatusTest.java#L91), [`ErrorsAndLintsTest#responseErrorIsBoxed`](../../src/test/java/be/timvw/openapi/rust/ErrorsAndLintsTest.java#L46) |

### `responses.statusCode.transport-error`

| | |
| --- | --- |
| OpenAPI | any operation |
| Rust | `Error<T>` also has `Reqwest(reqwest::Error)` and `ReqwestMiddleware(reqwest_middleware::Error)` (sending or reading failed), `Serde(serde_json::Error)` (a success body did not decode, or a struct parameter did not serialize) and `Io(std::io::Error)` (a file to send could not be read) |
| Library | `Error::Reqwest` and `Error::ReqwestMiddleware` |
| Tests | [`OperationMappingTest#errorResponsesAreAnUntaggedEnumPerOperation`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L149) |
