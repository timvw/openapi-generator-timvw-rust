# Responses Object

Rules for the [Responses Object](https://spec.openapis.org/oas/v3.0.3#responses-object): what an operation's error
responses become. Success responses are the [Response Object](response.md).

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OperationMappingTest` renders
`src/test/resources/operation_mapping.yaml` without options.

### `responses.statusCode`

| | |
| --- | --- |
| OpenAPI | the error responses of an operation: a status code (`404`), a range (`4XX`) or `default` |
| Rust | an enum per operation, `<OperationId>Error`, `#[serde(untagged)]`, with a variant per response holding its schema's type (`Status404(models::Problem)`, `DefaultResponse(models::Problem)`) and `UnknownValue(serde_json::Value)` last. An error status gives `Error::ResponseError` holding a boxed `ResponseContent { status, content, entity, headers }`: the `timvw_rust_runtime::StatusCode`, the body as text, `entity: Option<<OperationId>Error>`, the body decoded as the variant its status picks (see Wire), and `headers: timvw_rust_runtime::header::HeaderMap`, the response's headers (`retry-after`, `x-request-id`, ...); with `responseFunctions`, the headers the error responses declare are typed as well (`content.typed_headers::<<OperationId>ErrorHeaders>()`, [`header.schema.typed-error`](header.md#headerschematyped-error)). `{:?}` of the error prints them all, which can include `set-cookie` or tokens; `Display` prints only the status |
| Wire | the status picks the variant: the response with that exact code (`404`), else the response of its range (`4XX` is 400 to 499), else `default`; the body decodes as that response's type. A response without a body gives its variant (`Status409()`) whatever the body. Only a `String`-typed text error holds the body as it is: a `type: string` response whose media types are text and none JSON (`text/plain`) gives `Status400(String)` (also `DefaultResponse(String)`) with the body, not decoded as a JSON string. A text response of another type, and one that also lists a JSON media type, still decodes as JSON. A body that does not decode as the chosen type, or a status no response lists in an operation without `default`, is `UnknownValue` when the body is JSON, and `entity` is `None` when it is not. Two responses of one type each get their own variant. The enum stays `#[serde(untagged)]`, so it serializes as the body it holds and still derives `Deserialize` |
| Library | `status` is a `timvw_rust_runtime::StatusCode`, `headers` a `timvw_rust_runtime::header::HeaderMap` |
| Options | always: `Error::ResponseError(Box<ResponseContent<T>>)`, built with `Box::new`, so every `Result` stays small with the headers in it (clippy `result_large_err`); the former `boxResponseError` fails generation |
| Tests | [`OperationMappingTest#errorResponsesAreAnUntaggedEnumPerOperation`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L149), [`ErrorStatusTest#theStatusPicksTheVariantExactCodesBeforeRangesThenDefault`](../../src/test/java/be/timvw/openapi/rust/ErrorStatusTest.java#L90), [`ErrorStatusTest#withoutDefaultAnUnlistedStatusIsUnknownValue`](../../src/test/java/be/timvw/openapi/rust/ErrorStatusTest.java#L104), [`ErrorStatusTest#aTextErrorHoldsTheBodyAsItIs`](../../src/test/java/be/timvw/openapi/rust/ErrorStatusTest.java#L118), [`ErrorStatusTest#theResponseFunctionTwinPicksTheVariantTheSameWay`](../../src/test/java/be/timvw/openapi/rust/ErrorStatusTest.java#L136), [`ErrorStatusTest#whatItDecodesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/ErrorStatusTest.java#L165), [`ErrorsAndLintsTest#responseErrorIsBoxed`](../../src/test/java/be/timvw/openapi/rust/ErrorsAndLintsTest.java#L45) |

### `responses.statusCode.transport-error`

| | |
| --- | --- |
| OpenAPI | any operation |
| Rust | `Error<T>` also has `Http(timvw_rust_runtime::Error)` (building or sending the request, or reading the response, failed; `Reqwest(timvw_rust_runtime::Error)` and `ReqwestMiddleware(reqwest_middleware::Error)` with `httpStack=reqwest-middleware`), `Serde(serde_json::Error)` (a success body did not decode, or a struct parameter did not serialize) and `Io(std::io::Error)` (a file to send could not be read) |
| Library | `Error::Http` (`Error::Reqwest` and `Error::ReqwestMiddleware` with `httpStack=reqwest-middleware`) |
| Tests | [`OperationMappingTest#errorResponsesAreAnUntaggedEnumPerOperation`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L149) |
