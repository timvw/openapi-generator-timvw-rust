# Paths, Path Item and Operation Object

Rules for the [Paths](https://spec.openapis.org/oas/v3.0.3#paths-object),
[Path Item](https://spec.openapis.org/oas/v3.0.3#path-item-object) and
[Operation](https://spec.openapis.org/oas/v3.0.3#operation-object) Objects. Parameters, request bodies and responses
have files of their own.

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OperationMappingTest` renders
`src/test/resources/operation_mapping.yaml` without options.

### `operation.tags`

| | |
| --- | --- |
| OpenAPI | an operation's first tag |
| Rust | a module per tag, `src/apis/<tag>_api.rs` (`items_api`), declared in `src/apis/mod.rs`; operations without a tag are in `default_api` |
| Tests | [`OperationMappingTest#aTagIsAModuleAndUntaggedOperationsAreInDefaultApi`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L60) |

### `operation.operationId`

| | |
| --- | --- |
| OpenAPI | `operationId` |
| Rust | `pub async fn <snake_case>(configuration: &configuration::Configuration, <parameters...>) -> Result<<success>, Error<<OperationId>Error>>` with `#[bon::builder]`, which turns the function into a builder: it is called as `create_item().configuration(&c).item(&i).call()`, an optional argument has a setter for the value and a `maybe_` setter for the `Option` |
| Options | `bonBuilders` (default `true`) adds `#[bon::builder]`; with `false` the function takes its arguments positionally (`create_item(&c, &i)`). `contentTypeMethods` and `typedEventStreams` add functions with a suffix ([`requestBody.content`](request-body.md#requestbodycontent), [`response.content`](response.md#responsecontent)), and `responseFunctions` an `_response` twin ([`response.content.response-function`](response.md#responsecontentresponse-function)) |
| Tests | [`OperationMappingTest#anOperationIsAnAsyncFunctionWithABuilder`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L68), [`GenerationTest#apiFunctionsGetBonBuilderAfterDeprecated`](../../src/test/java/be/timvw/openapi/rust/GenerationTest.java#L55) |

### `operation.operationId.request-options`

| | |
| --- | --- |
| OpenAPI | every operation |
| Rust | a last argument `options: Option<&timvw_rust_runtime::RequestOptions>`, so the builder has `.options(&options)`: extra headers and query parameters for one call, applied after everything the function sets (parameters, authentication, `Content-Type`, `Accept`). Each of its headers replaces the request's values of that name; its query parameters are appended. `apis::RequestOptions` re-exports the type. When a parameter of the operation is named `options`, the argument is `request_options` |
| Options | only with `httpStack=tower` (the default) and `bonBuilders` (the default); `httpStack=reqwest-middleware` and `bonBuilders=false` render no such argument |
| Tests | [`RequestOptionsTest#everyMethodTakesOptionsLast`](../../src/test/java/be/timvw/openapi/rust/RequestOptionsTest.java#L78), [`#theyAreAppliedLastBeforeBuilding`](../../src/test/java/be/timvw/openapi/rust/RequestOptionsTest.java#L92), [`#aParameterNamedOptionsKeepsItsName`](../../src/test/java/be/timvw/openapi/rust/RequestOptionsTest.java#L107), [`#notWithReqwestMiddlewareOrPositionalArguments`](../../src/test/java/be/timvw/openapi/rust/RequestOptionsTest.java#L114), [`#whatArrivesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/RequestOptionsTest.java#L131) |

### `operation.description`

| | |
| --- | --- |
| OpenAPI | an operation's `description` and `summary` |
| Rust | the `description` is the function's `///` documentation. The `summary` is only in the generated Markdown (`docs/<Tag>Api.md` and the crate's `README.md`), not in the Rust |
| Tests | [`OtherObjectsTest#anOperationsDescriptionDocumentsItsFunctionAndItsSummaryOnlyTheMarkdown`](../../src/test/java/be/timvw/openapi/rust/OtherObjectsTest.java#L68) |

### `operation.parameters`

| | |
| --- | --- |
| OpenAPI | an operation's `parameters` (with the path item's) |
| Rust | one argument each, in spec order, after `configuration`; the request body comes last. Each parameter's type is in [parameter.md](parameter.md) |
| Tests | [`OperationMappingTest#parametersAreArgumentsInSpecOrder`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L75) |

### `operation.deprecated`

| | |
| --- | --- |
| OpenAPI | `deprecated: true` on an operation |
| Rust | `#[deprecated]` on the function |
| Tests | [`OperationMappingTest#aDeprecatedOperationIsDeprecated`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L83), [`GenerationTest#apiFunctionsGetBonBuilderAfterDeprecated`](../../src/test/java/be/timvw/openapi/rust/GenerationTest.java#L55) |

### `operation.security`

| | |
| --- | --- |
| OpenAPI | an operation's `security`, or the spec's when it has none |
| Rust | the function sends the credentials of those schemes that are set in `Configuration` ([security-scheme.md](security-scheme.md)); a scheme the operation does not list is not sent |
| Tests | [`OperationMappingTest#eachOperationSendsTheCredentialsOfItsSecurity`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L178) |
