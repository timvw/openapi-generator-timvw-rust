# OpenAPI Object, Info Object

Rules for the [OpenAPI Object](https://spec.openapis.org/oas/v3.0.3#openapi-object) and the
[Info Object](https://spec.openapis.org/oas/v3.0.3#info-object).

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OperationMappingTest` renders
`src/test/resources/operation_mapping.yaml` without options.

### `openapi.webhooks`

| | |
| --- | --- |
| OpenAPI | `webhooks` (OpenAPI 3.1): operations the API calls |
| Rust | a function per webhook operation in the API module of its tag, next to the path operations of that tag: an untagged webhook in `default_api` with the untagged path operations, a webhook tagged `Pets` in `pets_api`. The path operations come first, then the webhooks, in the module and in `docs/<Tag>Api.md`. A webhook gets no `_response` twin, and a webhook whose function would take the name of a path method of its module (a `_response` twin included) fails generation, naming `webhookModule`. Stock (`-g rust`), and `timvw-rust` up to 0.23.0, write the webhook file over the path operations' file of the same tag, so those path operations are lost |
| Gap | a tag that only webhooks carry gets its module file (`src/apis/<tag>_api.rs`), but `src/apis/mod.rs` does not declare it, so its functions are not part of the crate; `webhookModule` declares the webhook module |
| Options | `webhookModule=<Tag>` (openai-client-base) tags them `<Tag>` and generates them into their own module, declared in `src/apis/mod.rs`; a path operation that already carries the tag fails generation. `webhookModuleReexport=true` makes `default_api` re-export that module. A webhook's request body is something the API sends, so [`schema.enum.open`](schema.md#schemaenumopen) opens what it holds |
| Tests | [`SpecRulesTest#webhooksGetTheirOwnModuleAndDefaultApiReexportsIt`](../../src/test/java/be/timvw/openapi/rust/SpecRulesTest.java#L92), [`#reservedWebhookTagOnAPathOperationFails`](../../src/test/java/be/timvw/openapi/rust/SpecRulesTest.java#L104), [`WebhooksTest#untaggedWebhooksJoinTheUntaggedPathOperationsInDefaultApi`](../../src/test/java/be/timvw/openapi/rust/WebhooksTest.java#L74), [`#aTaggedWebhookJoinsThePathOperationsOfItsTag`](../../src/test/java/be/timvw/openapi/rust/WebhooksTest.java#L90), [`#responseFunctionsStayOnThePathOperations`](../../src/test/java/be/timvw/openapi/rust/WebhooksTest.java#L104), [`#webhookModuleStillKeepsTheWebhooksApart`](../../src/test/java/be/timvw/openapi/rust/WebhooksTest.java#L113), [`#aWebhookNamedLikeAPathMethodFails`](../../src/test/java/be/timvw/openapi/rust/WebhooksTest.java#L127), [`#theMergedCrateCompiles`](../../src/test/java/be/timvw/openapi/rust/WebhooksTest.java#L160) |

### `info.version`

| | |
| --- | --- |
| OpenAPI | `info.version` |
| Rust | the default user agent, `OpenAPI-Generator/<version>/rust`, in `Configuration::default()` |
| Options | `userAgentFromPackage=true` (openai-client-base) makes it `<packageName>/<crate version>`, the version taken from `CARGO_PKG_VERSION` |
| Tests | [`OperationMappingTest#theFirstServerIsTheBasePathAndTheVersionNamesTheUserAgent`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L170), [`ErrorsAndLintsTest#userAgentIsThePackageAndCrateVersion`](../../src/test/java/be/timvw/openapi/rust/ErrorsAndLintsTest.java#L62) |
