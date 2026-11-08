# Schema Object

Rules for the [Schema Object](https://spec.openapis.org/oas/v3.0.3#schema-object). The [baseline](README.md#baseline)
options apply. So far this file holds composition (`oneOf`, `anyOf`, `allOf`); the other fields are still in
[type-mapping.md](../type-mapping.md).

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`.

## `oneOf`

### `schema.oneOf`

| | |
| --- | --- |
| OpenAPI | `oneOf` with two or more branches that are not `type: null` |
| Rust | a `pub enum` with `#[serde(untagged)]` and a variant per branch, holding the branch's type (`User(models::UserMessage)`, `Text(String)`, `ArrayOfStrings(Vec<String>)`). No `Default`. A variant is boxed only where it closes a cycle (`Branch(Box<models::Branch>)`) |
| Wire | decodes to the first variant, in [variant order](#schemaoneofvariant-order), that accepts the value; with a tag, by the tag ([`discriminator.propertyName`](discriminator.md#discriminatorpropertyname), [`schema.oneOf.implicit-tag`](#schemaoneofimplicit-tag)). Encodes as the variant's value, without a wrapper |
| Options | `modelUnions` (baseline). Without it: stock rendering, where a discriminated `oneOf` is `#[serde(tag = "...")]` that does not decode when the branch structs keep the tag field, and writes the tag twice |
| Tests | `ModelUnionsTest#aDiscriminatedOneOfIsUntaggedSoTheTagIsWrittenOnce`, `#nothingIsEmptyAndNoUnionHasADefault`, `#aCycleThroughAUnionIsBoxedOnlyThere`, `#withoutTheOptionNothingChanges` |

### `schema.oneOf.variant-names`

| | |
| --- | --- |
| OpenAPI | the branches of a `oneOf` or `anyOf` |
| Rust | each variant is named, in order of preference, after the discriminator `mapping` key, the referenced schema, the branch's `title`, then its type (`Text`, `Integer`, `Number`, `ArrayOfStrings`, `ArrayOfIntegerArrays`). Arrays that share a title are named after their items |
| Options | `qualifiedInlineNames` (baseline) also gives an inline branch's model the union's name (`models::FormatPcm`), which the variant does not repeat (`Pcm`) |
| Tests | `ModelUnionsTest#thePlanNamesEveryVariantWithItsBranch`, `#arraysSharingATitleAreNamedAfterTheirItems`, `#anInlineBranchKeepsItsOwnModel` |

### `schema.oneOf.variant-order`

| | |
| --- | --- |
| OpenAPI | the branches of a `oneOf` or `anyOf` |
| Rust | the variants are reordered so that, of two variants that accept the same JSON kind, the narrower comes first. Variants this says nothing about keep the order of the spec |
| Wire | an untagged enum decodes to the first variant that accepts the value, so the order decides what decodes |
| Options | `modelUnions` (baseline) |
| Tests | `ModelUnionsTest#anIntegerGoesBeforeAFloatAndAnEnumBeforeAString`, `#aStructGoesBeforeOneThatWouldDecodeItsValues`, `#aUnionWithARicherArmGoesBeforeAStructItCouldLoseFieldsTo`, `#whatAcceptsEverythingComesLast` |

Narrower first, per JSON kind:

| Kind | Order |
| --- | --- |
| number | an integer before a float, which accepts integers too |
| string | a closed enum before a free string |
| object | a struct before one that would decode its values when it would not decode theirs. A struct decodes a value that has every field it requires, unless a required field contradicts it (a fixed tag with other values, or a type of another kind). When both would: more required fields first, then more fields, then a union. Maps after structs |
| array | by these rules applied to the items; `Vec<serde_json::Value>` last |
| any | `serde_json::Value` last |

A variant that is a union counts through its arms. When two variants compare differently on different kinds, the
object kind decides. Two variants that accept the same values decode to whichever comes first;
`tools/check-union-samples.py` lists them.

### `schema.oneOf.null-branch`

| | |
| --- | --- |
| OpenAPI | a `oneOf` or `anyOf` with a `type: null` branch (OpenAPI 3.1, or `nullable: true` on the union) |
| Rust | the `null` branch is no variant: the union is nullable. With one other branch, there is no union: the schema is that branch (an object next to `null` is a struct). Where it is used, the nullable union follows [`schema.nullable`](../type-mapping.md#objects): an optional property is `Option<Option<U>>` |
| Wire | `null` decodes to `Some(None)`, an absent property to `None` |
| Options | `nullableUnionFields` (baseline). Without it a nullable union property is `Option<U>`, where `null` and absent are the same and a request cannot send `null` |
| Tests | `ModelUnionsTest#anObjectNextToNullIsAStruct`, `#aNullableReferenceDoesNotRewriteItsTarget`, `ExtraRulesTest#nullableUnionFieldsCanHoldNull`, `#nullableUnionFieldsAreOptionsWithoutTheOption` |

### `schema.oneOf.enum-branch`

| | |
| --- | --- |
| OpenAPI | a branch that is an inline string `enum` |
| Rust | an enum of its own, `<Union><Variant>Enum` (`LogBatchSizeTextVariantEnum`), re-exported from `models`; the variant holds it (`TextVariant(models::LogBatchSizeTextVariantEnum)`) |
| Options | `modelUnions` (baseline). With `openEnums` (baseline) it gets an `Unknown` variant, unless it tells two variants apart |
| Tests | `ModelUnionsTest#theIntegerBranchSurvivesAnEnumNextToIt`, `OpenEnumsTest#whatTellsVariantsApartIsGuarded` |

### `schema.oneOf.inline`

| | |
| --- | --- |
| OpenAPI | an inline `oneOf` or `anyOf` as the value of a property, with inline branches of which at least one is an object or array |
| Rust | the union becomes a model of its own, `<Schema><Property>` (`ThingChoice`), and the property holds it. Untitled primitive branches are titled `Text` and `Integer` |
| Options | always |
| Tests | `SpecRulesTest#genericRulesShapeTheNames` |

### `schema.oneOf.helpers`

| | |
| --- | --- |
| OpenAPI | any `oneOf` or `anyOf` rendered as an enum |
| Rust | `Display`: a string or enum variant prints itself, any other its JSON. With a string variant also `From<String>`, `From<&str>` and `new_text`; constructors per variant such as `new_arrayofparts` |
| Options | `modelUnions` (baseline) |
| Tests | `ModelUnionsTest#helpersKeepTheirNames`, `#aStringVariantGoesOutAsPlainText`, `#aJsonVariantPrintsItsJson` |

### `schema.oneOf.implicit-tag`

| | |
| --- | --- |
| OpenAPI | a `oneOf` or `anyOf` without a `discriminator`, where every branch requires one property with fixed string values that tell two variants apart |
| Rust | the same enum as [`schema.oneOf`](#schemaoneof), decoded by that property as if it were the discriminator ([`discriminator.propertyName`](discriminator.md#discriminatorpropertyname)). A branch that is a union has the property when all its arms do. When several properties qualify, the one with the fewest values on two variants wins, then `type`, then the first by name |
| Wire | as [`discriminator.propertyName`](discriminator.md#discriminatorpropertyname) |
| Options | `unionDispatch` (baseline). Without it the union decodes in variant order. A union where only some branches require the property, with a branch that is no object, or where the property has the same values everywhere, stays untagged |
| Tests | `UnionDispatchTest#aRequiredPropertyWithFixedValuesOnEveryBranchIsATag`, `#aBranchThatIsAUnionCountsThroughItsArms`, `#anInlineBranchOfABranchCountsToo`, `#thePropertyThatSharesFewestValuesIsTheTag`, `#whatHasNoTagIsNotDispatched` |

## `anyOf`

### `schema.anyOf`

| | |
| --- | --- |
| OpenAPI | `anyOf` with two or more branches that are not `type: null` |
| Rust | as [`schema.oneOf`](#schemaoneof), and every `schema.oneOf.*` rule applies the same way. A value that matches several branches decodes to the first in variant order |
| Options | `modelUnions` (baseline). Without it stock renders a discriminated `anyOf` as an empty type |
| Tests | `ModelUnionsTest#aDiscriminatedAnyOfHasItsVariants` |

### `schema.anyOf.untyped`

| | |
| --- | --- |
| OpenAPI | an `anyOf` with no typed branch (`anyOf: [{}, {type: null}]`) |
| Rust | `serde_json::Value` |
| Options | always |
| Tests | `ModelUnionsTest#aVariantHoldingAnUntypedAnyOfHoldsJsonValues` |

## `allOf`

### `schema.allOf.union`

| | |
| --- | --- |
| OpenAPI | a schema that is `allOf` of exactly one union |
| Rust | an enum with that union's variants, and its tag |
| Options | `modelUnions` (baseline) |
| Tests | `ModelUnionsTest#aModelThatIsAllOfOneUnionHasThatUnionsVariants`, `UnionDispatchTest#aModelThatIsAllOfOneUnionHasItsTag` |

`allOf` of objects is still in [type-mapping.md](../type-mapping.md#composition-and-names).
