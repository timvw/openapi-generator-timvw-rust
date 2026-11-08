# Components Object

Rules for the [Components Object](https://spec.openapis.org/oas/v3.0.3#components-object). The
[baseline](README.md#baseline) options apply.

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`.

### `components.schemas`

| | |
| --- | --- |
| OpenAPI | a schema under `components.schemas` that renders a type (an object, enum or union; a scalar has no type of its own) |
| Rust | a type named in `CamelCase` in a `snake_case` file of `src/models` (`ChatCompletion` in `chat_completion.rs`), re-exported as `models::ChatCompletion`. Hyphens are removed (`Thing-2` is `Thing2`). Two components with one Rust name fail generation |
| Tests | `SpecRulesTest#genericRulesShapeTheNames`, `QualifiedInlineNamesTest#twoComponentsWithOneRustNameFail`, `SchemaMappingTest#propertiesAreAStructWithAConstructorOfTheRequiredFields` |
