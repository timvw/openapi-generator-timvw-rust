# Discriminator Object

Rules for the [Discriminator Object](https://spec.openapis.org/oas/v3.0.3#discriminator-object), on a `oneOf` or
`anyOf`. The union itself is [`schema.oneOf`](schema.md#schemaoneof).

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`.

### `discriminator.propertyName`

| | |
| --- | --- |
| OpenAPI | `discriminator: {propertyName: <tag>}` |
| Rust | the enum of [`schema.oneOf`](schema.md#schemaoneof), with a generated `Deserialize` that reads the tag and decodes the variant it selects. It keeps `#[derive(Serialize)]` and `#[serde(untagged)]`. Each branch struct keeps the tag as a field. The helpers are in the crate-private module `src/models/union_dispatch.rs`; a schema with that name fails generation |
| Wire | encodes as the variant's struct, which writes the tag once. Decoding: see below |
| Options | always (stock renders `#[serde(tag = "<tag>")]`, which does not decode when the branch structs keep the tag field, and writes it twice) |
| Tests | [`UnionDispatchTest#aDiscriminatorOnOneOfOrAnyOfIsATag`](../../src/test/java/be/timvw/openapi/rust/UnionDispatchTest.java#L93), [`#itSerializesAsBeforeAndEachStructKeepsItsTag`](../../src/test/java/be/timvw/openapi/rust/UnionDispatchTest.java#L174), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/UnionDispatchTest.java#L229), [`ModelUnionsTest#aDiscriminatedOneOfIsUntaggedSoTheTagIsWrittenOnce`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L75) |

What decodes:

| Input | Result |
| --- | --- |
| a value of one variant | that variant; its error is the error: `` Shape::Circle: missing field `radius` `` |
| a value on several variants | they are tried in variant order; if none decodes, each one's error is listed |
| a value of a variant that is a union | that union, by its own tag; its error comes with the path: `` InputItem::Item: Item::ToolCall: missing field `name` `` |
| no tag, or `null` | the variants where the tag is optional or nullable, in order; if there are none: `` Part: tag `type` is missing or null `` |
| a tag that is no string | `` Shape: tag `kind` must be a string, got a number `` |
| no object | the variants that accept something else, in order; if there are none: `` Shape: expected an object with tag `kind`, got a number `` |
| an unknown value | [`discriminator.propertyName.unknown-value`](#discriminatorpropertynameunknown-value) |

Numbers go through `serde_json::Value` as they go through the buffer of `serde(untagged)`: an integer up to 64 bits
stays that integer, a larger one and every float is an `f64`. A key repeated in one object is the last one, where a
struct alone would reject it. `unionPlanFile` holds each union's `dispatch` (its tag, which values select which
variants); `tools/check-union-samples.py` uses it to decode a sample per value of every variant and check the error
messages.

### `discriminator.propertyName.unknown-value`

| | |
| --- | --- |
| OpenAPI | a tag value no variant lists, in a union a response can hold |
| Rust | the variant `Unknown(serde_json::Value)`, holding what came (`UnknownValue(...)` when a branch is called `Unknown`) |
| Wire | encodes back as the same JSON. In a union only requests use: the error `` Shape: unknown `kind` value "triangle" `` |
| Options | always. A tagged union in front of a struct whose tag is free stays closed |
| Tests | [`OpenEnumsTest#whatAResponseHoldsIsOpen`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L76), [`#whatOnlyARequestHoldsStaysClosed`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L85), [`#whatTellsVariantsApartIsGuarded`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L116), [`#theCatchAllTakesANameAndAStringNoValueHas`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L137), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L161) |

### `discriminator.mapping`

| | |
| --- | --- |
| OpenAPI | `mapping: {<value>: <$ref>}` |
| Rust | each value selects the variant of the referenced schema, and the mapping key names the variant ([`schema.oneOf.variant-names`](schema.md#schemaoneofvariant-names)). A branch is selected by the `mapping` keys that name it and by the fixed (`enum`) values of its tag property that the mapping does not give to another branch: with `kind: [cat, dog]` on both `Cat` and `Dog` and `mapping: {cat: Cat, dog: Dog}`, `cat` selects only `Cat`; with `Cat` `[cat, kitty]` and `mapping: {cat: Cat, feline: Cat, dog: Dog}`, `Cat` is selected by `cat`, `kitty` and `feline`. Without a `mapping`, the tag values alone. A variant can have several values; a value can select several variants |
| Wire | a variant decodes from any of its values and holds the value it was given |
| Options | always. A variant that no value selects, or whose struct has no tag field, fails generation |
| Tests | [`UnionDispatchTest#aDiscriminatorOnOneOfOrAnyOfIsATag`](../../src/test/java/be/timvw/openapi/rust/UnionDispatchTest.java#L93), [`#everyValueOfAVariantSelectsIt`](../../src/test/java/be/timvw/openapi/rust/UnionDispatchTest.java#L148), [`#aValueOnTwoVariantsListsThemInDecodingOrder`](../../src/test/java/be/timvw/openapi/rust/UnionDispatchTest.java#L154), [`#aVariantNoValueSelectsFailsGeneration`](../../src/test/java/be/timvw/openapi/rust/UnionDispatchTest.java#L208), [`#aVariantWithoutTheFieldFailsGeneration`](../../src/test/java/be/timvw/openapi/rust/UnionDispatchTest.java#L201), [`SmallFixesTest#theMappingPicksTheBranchWhenBranchesShareTheTagEnum`](../../src/test/java/be/timvw/openapi/rust/SmallFixesTest.java#L174), [`#theMappingAddsToTheBranchesOwnTagValues`](../../src/test/java/be/timvw/openapi/rust/SmallFixesTest.java#L188) |
