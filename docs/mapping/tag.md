# Tag Object

Rules for the [Tag Object](https://spec.openapis.org/oas/v3.0.3#tag-object). The [baseline](README.md#baseline) options apply. That an operation's first tag names its module is [`operation.tags`](operation.md#operationtags).

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OtherObjectsTest` renders
`src/test/resources/other_objects.yaml` with the baseline options.

### `tag.description`

| | |
| --- | --- |
| OpenAPI | the spec's `tags` list: a tag's `description` and `externalDocs` |
| Rust | nothing: the module of a tag gets no documentation from it |
| Tests | `OtherObjectsTest#aTagNamesItsModuleAndNothingElseOfItIsRendered` |
