# OpenAPI Object, Info Object

Rules for the [OpenAPI Object](https://spec.openapis.org/oas/v3.0.3#openapi-object) and the
[Info Object](https://spec.openapis.org/oas/v3.0.3#info-object). The [baseline](README.md#baseline) options apply.

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OperationMappingTest` renders
`src/test/resources/operation_mapping.yaml` with the baseline options.

### `openapi.webhooks`

| | |
| --- | --- |
| OpenAPI | `webhooks` (OpenAPI 3.1): operations the API calls |
| Rust | stock writes them into `default_api`, where a webhook can replace an untagged path operation of the same name |
| Options | `webhookModule=<Tag>` (openai-client-base) tags them `<Tag>` and generates them into their own module, declared in `src/apis/mod.rs`; a path operation that already carries the tag fails generation. `webhookModuleReexport=true` makes `default_api` re-export that module. A webhook's request body is something the API sends, so [`schema.enum.open`](schema.md#schemaenumopen) opens what it holds |
| Tests | [`SpecRulesTest#webhooksGetTheirOwnModuleAndDefaultApiReexportsIt`](../../src/test/java/be/timvw/openapi/rust/SpecRulesTest.java#L92), [`#reservedWebhookTagOnAPathOperationFails`](../../src/test/java/be/timvw/openapi/rust/SpecRulesTest.java#L104) |

### `info.version`

| | |
| --- | --- |
| OpenAPI | `info.version` |
| Rust | the default user agent, `OpenAPI-Generator/<version>/rust`, in `Configuration::default()` |
| Options | `userAgentFromPackage=true` (openai-client-base) makes it `<packageName>/<crate version>`, the version taken from `CARGO_PKG_VERSION` |
| Tests | [`OperationMappingTest#theFirstServerIsTheBasePathAndTheVersionNamesTheUserAgent`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L163), [`ErrorsAndLintsTest#userAgentIsThePackageAndCrateVersion`](../../src/test/java/be/timvw/openapi/rust/ErrorsAndLintsTest.java#L63) |
