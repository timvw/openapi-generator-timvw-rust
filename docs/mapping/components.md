# Components Object

Rules for the [Components Object](https://spec.openapis.org/oas/v3.0.3#components-object).

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`.

### `components.schemas`

| | |
| --- | --- |
| OpenAPI | a schema under `components.schemas` that renders a type (an object, enum or union; a scalar has no type of its own) |
| Rust | a type named in `CamelCase` in a `snake_case` file of `src/models` (`ChatCompletion` in `chat_completion.rs`), re-exported as `models::ChatCompletion`. Hyphens are removed (`Thing-2` is `Thing2`). Two components with one Rust name fail generation |
| Tests | [`SpecRulesTest#genericRulesShapeTheNames`](../../src/test/java/be/timvw/openapi/rust/SpecRulesTest.java#L130), [`QualifiedInlineNamesTest#twoComponentsWithOneRustNameFail`](../../src/test/java/be/timvw/openapi/rust/QualifiedInlineNamesTest.java#L214), [`SchemaMappingTest#propertiesAreAStructWithAConstructorOfTheRequiredFields`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L114) |

### `components.schemas.docs`

| | |
| --- | --- |
| OpenAPI | the types of the models' properties, of the parameters and of the return types, in the generated Markdown (`docs/<Model>.md`, `docs/<Tag>Api.md`) |
| Rust | the Markdown shows the Rust type, linked to the doc of the one model it names (`Vec<models::Pet>` and `HashMap<String, Vec<models::Kind>>` to `Pet.md` and `Kind.md`). A type that names no model of the crate (`String`, `Vec<String>`, `uuid::Uuid`, `std::path::PathBuf`, `serde_json::Value`, an inline enum) is shown without a link. Stock linked the container (`Vec.md`), the primitive (`String.md`), a library path (`std::path::PathBuf.md`), the schema name of an inline response (`createPet_200_response.md`) or a model that is not written (`AnyOf.md`), files that do not exist |
| Options | always |
| Tests | [`GeneratedDocLinksTest#everyRelativeLinkResolves`](../../src/test/java/be/timvw/openapi/rust/GeneratedDocLinksTest.java#L36), [`#aTypeLinksToTheOneModelItNames`](../../src/test/java/be/timvw/openapi/rust/GeneratedDocLinksTest.java#L62) |
