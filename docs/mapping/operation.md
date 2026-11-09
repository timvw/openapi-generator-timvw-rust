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
| Rust | `pub async fn <snake_case>(configuration: &configuration::Configuration, <parameters...>) -> Result<<success>, Error<<OperationId>Error>>`, also callable as a builder (`create_item().configuration(&c).item(i).call()`) |
| Options | `bonBuilders` (default `true`) adds `#[bon::builder]`. `contentTypeMethods` and `typedEventStreams` add functions with a suffix ([`requestBody.content`](request-body.md#requestbodycontent), [`response.content`](response.md#responsecontent)) |
| Tests | [`OperationMappingTest#anOperationIsAnAsyncFunctionWithABuilder`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L68), [`GenerationTest#apiFunctionsGetBonBuilderAfterDeprecated`](../../src/test/java/be/timvw/openapi/rust/GenerationTest.java#L55) |

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
| Tests | [`OperationMappingTest#eachOperationSendsTheCredentialsOfItsSecurity`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L171) |
