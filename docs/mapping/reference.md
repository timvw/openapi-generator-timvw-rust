# Reference Object

Rules for the [Reference Object](https://spec.openapis.org/oas/v3.0.3#reference-object). The
[baseline](README.md#baseline) options apply.

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`.

### `reference.$ref`

| | |
| --- | --- |
| OpenAPI | `$ref: '#/components/schemas/<Name>'` |
| Rust | the referenced model, `models::<Name>`; held by value unless it closes a cycle ([`schema.properties.reference`](schema.md#schemapropertiesreference)). A reference to a scalar component is the scalar ([`schema.type.scalar-component`](schema.md#schematypescalar-component)) |
| Options | always: keywords next to a `$ref` (`description`, `nullable`, `default`, ...) are dropped before generation, as OpenAPI 3.0 says they are ignored |
| Tests | `SchemaMappingTest#propertiesAreAStructWithAConstructorOfTheRequiredFields`, `SpecRulesTest#validationOnlyUnionsAndDefaultsAreDropped` |
