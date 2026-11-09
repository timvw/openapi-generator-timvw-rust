# Schema Object

Rules for the [Schema Object](https://spec.openapis.org/oas/v3.0.3#schema-object), in the order of its fields: `type`
and `format`, the validation keywords, object fields (`properties`, `required`, `nullable`, `additionalProperties`,
`readOnly`, `writeOnly`, `default`, `deprecated`, `description`), arrays (`items`, `uniqueItems`), `enum`, `title`, and
composition (`allOf`, `oneOf`, `anyOf`). The [baseline](README.md#baseline) options apply. `$ref` is the
[Reference Object](reference.md); component names are the [Components Object](components.md).

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `SchemaMappingTest` renders
`src/test/resources/schema_mapping.yaml` with the baseline options.

## `type` and `format`

### `schema.type.string`

| | |
| --- | --- |
| OpenAPI | `type: string`, without a format below (also `format: email`, `uri`, `hostname`, or one the generator does not know) |
| Rust | `String` |
| Tests | [`SchemaMappingTest#scalarsAndFormats`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L75) |

### `schema.format.date-time`

| | |
| --- | --- |
| OpenAPI | `type: string` with `format: date-time` or `format: date` |
| Rust | `chrono::DateTime<chrono::FixedOffset>` (`date-time`) and `chrono::NaiveDate` (`date`); the crate depends on `chrono` |
| Wire | RFC 3339 text, through chrono's serde support |
| Options | `useChrono` (default `true`, as in stock). With `useChrono=false` both are `String` and the crate has no `chrono` dependency (openai-client-base sets this; langfuse-client-base keeps the default) |
| Tests | [`SchemaMappingTest#datesAreChronoUnlessUseChronoIsOff`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L90) |

### `schema.format.uuid`

| | |
| --- | --- |
| OpenAPI | `type: string, format: uuid` |
| Rust | `uuid::Uuid` in a model; `&str` as a path, query or header parameter |
| Gap | a parameter takes `&str`, not `uuid::Uuid` |
| Tests | [`SchemaMappingTest#scalarsAndFormats`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L75), [`#aUuidParameterIsAString`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L107) |

### `schema.format.byte`

| | |
| --- | --- |
| OpenAPI | `type: string, format: byte` |
| Rust | `Vec<u8>`, with `#[serde_as(as = "serde_with::base64::Base64")]` |
| Wire | base64 text |
| Tests | [`SchemaMappingTest#scalarsAndFormats`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L75) |

### `schema.format.binary`

| | |
| --- | --- |
| OpenAPI | `type: string, format: binary` (a file) |
| Rust | as a request body, multipart part or parameter: `std::path::PathBuf`, the file to send. As a response: `reqwest::Response`, to read as bytes or a stream |
| Wire | the file's bytes, streamed from disk; the response body as it comes |
| Library | `reqwest::Response`; the file is opened with tokio and streamed as a `reqwest::Body` or `reqwest::multipart::Part` |
| Options | `fileProperties` renders listed `Schema.property` entries as files |
| Tests | [`SchemaMappingTest#binaryIsAFileToSendOrAResponseToRead`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L113), [`EventStreamsTest#requestBodiesGoOutAsTheirMediaType`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L146) |

### `schema.type.integer`

| | |
| --- | --- |
| OpenAPI | `type: integer` |
| Rust | `i64`; `format: int32` gives `i32`. Every other format (`int64`, `unixtime`, none, unknown, `int8`, `int16`, `uint8` to `uint64`) gives `i64`. Bounds (`minimum`, `maximum`) never pick a type, and nothing is unsigned or `NonZero`. This holds everywhere an integer appears: fields, items, map values, parameters, multipart fields, responses, constructors, builders and union variants |
| Options | `integersAsI64` (baseline). Without it the stock rendering: `i32` unless `format: int64`, and on openapi-generator 7.27 `i8`/`i16` and `u8` to `u64` for the sized formats. Bounds never pick a type: stock's `preferUnsignedInt` and `bestFitInt` are rejected |
| Tests | [`SchemaMappingTest#scalarsAndFormats`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L75), [`IntegersAsI64Test#onlyInt32StaysI32OnModelFields`](../../src/test/java/be/timvw/openapi/rust/IntegersAsI64Test.java#L69), [`#sizedAndUnsignedFormatsAreI64`](../../src/test/java/be/timvw/openapi/rust/IntegersAsI64Test.java#L77), [`#containerItemsFollowTheRule`](../../src/test/java/be/timvw/openapi/rust/IntegersAsI64Test.java#L89), [`#pathQueryAndHeaderParametersFollowTheRule`](../../src/test/java/be/timvw/openapi/rust/IntegersAsI64Test.java#L126), [`#withoutTheOptionIntegersStayAsInStock`](../../src/test/java/be/timvw/openapi/rust/IntegersAsI64Test.java#L151) |

### `schema.type.integer-with-float-format`

| | |
| --- | --- |
| OpenAPI | `type: integer` with `format: float` or `format: double` |
| Rust | read as `type: number` (logged) |
| Options | always |
| Tests | [`GenerationTest#integerWithFloatFormatIsRenderedAsNumber`](../../src/test/java/be/timvw/openapi/rust/GenerationTest.java#L105), [`SpecNormalizerTest#rewritesIntegerWithFloatFormatWhereverItAppears`](../../src/test/java/be/timvw/openapi/rust/SpecNormalizerTest.java#L31) |

### `schema.type.number`

| | |
| --- | --- |
| OpenAPI | `type: number` |
| Rust | `f64`; `format: float` gives `f32` |
| Tests | [`SchemaMappingTest#scalarsAndFormats`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L75), [`IntegersAsI64Test#numbersAreUntouched`](../../src/test/java/be/timvw/openapi/rust/IntegersAsI64Test.java#L104) |

### `schema.type.boolean`

| | |
| --- | --- |
| OpenAPI | `type: boolean` |
| Rust | `bool` |
| Tests | [`SchemaMappingTest#scalarsAndFormats`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L75) |

### `schema.type.scalar-component`

| | |
| --- | --- |
| OpenAPI | a component schema that is only a scalar (`Alias: {type: string}`) |
| Rust | no type of its own: a reference to it is the scalar, documented with the component's description |
| Options | `stringIdSchemas` renders listed components as `String` whatever they hold; `stringIdProperties` and `stringProperties` render listed properties as `String` |
| Tests | [`SchemaMappingTest#aScalarComponentHasNoTypeOfItsOwn`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L160), [`SpecRulesTest#idSchemasAndIdPropertiesBecomeStrings`](../../src/test/java/be/timvw/openapi/rust/SpecRulesTest.java#L111), [`#stringProperties`](../../src/test/java/be/timvw/openapi/rust/SpecRulesTest.java#L124) |

### `schema.pattern`

| | |
| --- | --- |
| OpenAPI | the validation keywords: `pattern`, `minLength`, `maxLength`, `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, `multipleOf`, `minItems`, `maxItems`, `minProperties`, `maxProperties` |
| Rust | nothing: the type is the one of the rules above, and no value is checked |
| Gap | values outside the bounds are neither rejected when decoding nor when building a request |
| Tests | [`SchemaMappingTest#validationKeywordsAreNotChecked`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L100) |

## Objects

### `schema.properties`

| | |
| --- | --- |
| OpenAPI | `type: object` with `properties` |
| Rust | `pub struct` with `#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, bon::Builder)]`, one `pub` field per property with `#[serde(rename = "<name>")]`, and `new(<required fields>)` that sets the optional ones to `None`. Field names are `snake_case` |
| Options | `bonBuilders` (default `true`) adds the `bon::Builder` derive |
| Tests | [`SchemaMappingTest#propertiesAreAStructWithAConstructorOfTheRequiredFields`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L120), [`GenerationTest#modelStructsDeriveBonBuilderAndEnumsDoNot`](../../src/test/java/be/timvw/openapi/rust/GenerationTest.java#L47) |

### `schema.required`

| | |
| --- | --- |
| OpenAPI | a property listed in `required`, or not |
| Rust | required: `T`, a parameter of `new()`. Optional: `Option<T>` with `skip_serializing_if = "Option::is_none"` |
| Wire | an optional field that is `None` is left out |
| Tests | [`SchemaMappingTest#propertiesAreAStructWithAConstructorOfTheRequiredFields`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L120) |

### `schema.nullable`

| | |
| --- | --- |
| OpenAPI | `nullable: true` (OpenAPI 3.1: `type: [T, "null"]`) |
| Rust | optional: `Option<Option<T>>` through `serde_with::rust::double_option`. Required: `Option<T>`, which must be present. A nullable union follows [`schema.oneOf.null-branch`](#schemaoneofnull-branch) |
| Wire | optional: `None` leaves the field out, `Some(None)` sends `null`. Required: `None` sends `null` |
| Tests | [`SchemaMappingTest#anOptionalNullablePropertyCanSendNull`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L131), [`DefaultOnlyWhereValidTest#aRequiredNullableFieldCountsAsRequired`](../../src/test/java/be/timvw/openapi/rust/DefaultOnlyWhereValidTest.java#L77), [`ModelUnionsTest#aNullableReferenceDoesNotRewriteItsTarget`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L120) |

### `schema.properties.reference`

| | |
| --- | --- |
| OpenAPI | a property that references another model |
| Rust | the model, by value (`Option<models::Owner>`). A reference that closes a cycle of the model graph is boxed (`Option<Box<models::ThreadNode>>`); `Vec` and map items are never boxed, since they already are on the heap |
| Options | `boxOnlyCycles` (baseline). Without it every model reference is boxed, as in stock |
| Tests | [`BoxOnlyCyclesTest#acyclicReferencesAreHeldByValue`](../../src/test/java/be/timvw/openapi/rust/BoxOnlyCyclesTest.java#L54), [`#selfReferenceIsBoxedButNotInsideVec`](../../src/test/java/be/timvw/openapi/rust/BoxOnlyCyclesTest.java#L66), [`#mutualRecursionIsBoxedBothWays`](../../src/test/java/be/timvw/openapi/rust/BoxOnlyCyclesTest.java#L76), [`#withoutTheOptionEveryModelReferenceIsBoxed`](../../src/test/java/be/timvw/openapi/rust/BoxOnlyCyclesTest.java#L100) |

### `schema.additionalProperties`

| | |
| --- | --- |
| OpenAPI | `additionalProperties: <schema>`, or `true` |
| Rust | `std::collections::HashMap<String, T>`; `true` gives `HashMap<String, serde_json::Value>` |
| Tests | [`SchemaMappingTest#mapsAndFreeFormObjects`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L138) |

### `schema.type.free-form-object`

| | |
| --- | --- |
| OpenAPI | `type: object` without `properties`, or `{}` |
| Rust | `serde_json::Value`. `{}` is nullable as well: an optional property is `Option<Option<serde_json::Value>>` |
| Tests | [`SchemaMappingTest#mapsAndFreeFormObjects`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L138) |

### `schema.readOnly`

| | |
| --- | --- |
| OpenAPI | `readOnly: true` or `writeOnly: true` |
| Rust | an ordinary field, in requests and in responses |
| Gap | a read-only field can be sent, and a write-only field is expected back; a required one makes `new()` ask for it |
| Tests | [`SchemaMappingTest#readOnlyAndWriteOnlyAreFieldsInBothDirections`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L146) |

### `schema.default`

| | |
| --- | --- |
| OpenAPI | `default: <value>` |
| Rust | not applied: the field is `Option<T>` like any optional field, and the default is noted in its documentation (``Defaults to `false`.``) |
| Options | `schemaDefaultDocs` (baseline). Without it the default is not mentioned |
| Tests | [`ExtraRulesTest#defaultsAreDocumented`](../../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java#L167) |

### `schema.properties.default-impl`

| | |
| --- | --- |
| OpenAPI | any object |
| Rust | `Default` is derived only on a struct without required fields (a required nullable field counts as required), so a default value is one the API accepts. No enum implements `Default` (see [`schema.enum.default`](#schemaenumdefault)) |
| Options | `defaultOnlyWhereValid` (baseline). Without it every struct derives `Default` |
| Tests | [`DefaultOnlyWhereValidTest#aRequiredFieldTakesTheDeriveAway`](../../src/test/java/be/timvw/openapi/rust/DefaultOnlyWhereValidTest.java#L67), [`#aRequiredNullableFieldCountsAsRequired`](../../src/test/java/be/timvw/openapi/rust/DefaultOnlyWhereValidTest.java#L77), [`#anAllOptionalStructKeepsItWhateverItsFieldTypes`](../../src/test/java/be/timvw/openapi/rust/DefaultOnlyWhereValidTest.java#L86), [`#withoutTheOptionEverythingKeepsDefault`](../../src/test/java/be/timvw/openapi/rust/DefaultOnlyWhereValidTest.java#L121) |

### `schema.properties.display`

| | |
| --- | --- |
| OpenAPI | any object |
| Rust | no `Display`. Where a struct goes into a path, query, header, form or multipart text part, the API code serializes it with `serde_json::to_string` |
| Wire | the struct's JSON; a serialization failure is `Error::Serde` |
| Options | `noStructDisplay` (baseline). Without it API code calls `to_string()` on a struct parameter, which has no `Display`, and does not compile |
| Tests | [`NoStructDisplayTest#structsHaveNoDisplay`](../../src/test/java/be/timvw/openapi/rust/NoStructDisplayTest.java#L65), [`#pathParameter`](../../src/test/java/be/timvw/openapi/rust/NoStructDisplayTest.java#L78), [`#nothingStillCallsDisplayOnAStruct`](../../src/test/java/be/timvw/openapi/rust/NoStructDisplayTest.java#L142) |

### `schema.deprecated`

| | |
| --- | --- |
| OpenAPI | `deprecated: true` on a property or a schema |
| Rust | no marker |
| Options | `deprecatedAttributes` (langfuse-client-base) adds `#[deprecated]` to the field or type, and generated files start with `#![allow(deprecated)]` |
| Tests | [`ExtraRulesTest#deprecatedFieldsAndModelsAreMarked`](../../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java#L178), [`#nothingIsDeprecatedWithoutTheOption`](../../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java#L188) |

### `schema.description`

| | |
| --- | --- |
| OpenAPI | `description` |
| Rust | `///` on the type or field; bare URLs are wrapped in `<...>` (rustdoc's `bare_urls`). A type without a description has no empty `///` |
| Tests | [`SchemaMappingTest#descriptionsAreDocCommentsWithBareUrlsWrapped`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L153), [`GenerationTest#bareUrlsInDocCommentsAreWrapped`](../../src/test/java/be/timvw/openapi/rust/GenerationTest.java#L89), [`ExtraRulesTest#enumsWithoutADescriptionHaveNoEmptyDocComment`](../../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java#L223) |

## Arrays

### `schema.items`

| | |
| --- | --- |
| OpenAPI | `type: array` with `items` |
| Rust | `Vec<T>`; items are never boxed. Inline object items are a model named after the array ([`schema.title`](#schematitle)) |
| Tests | [`SchemaMappingTest#arraysAreVecsAndUniqueItemsChangesNothing`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L166), [`BoxOnlyCyclesTest#selfReferenceIsBoxedButNotInsideVec`](../../src/test/java/be/timvw/openapi/rust/BoxOnlyCyclesTest.java#L66) |

### `schema.uniqueItems`

| | |
| --- | --- |
| OpenAPI | `uniqueItems: true` |
| Rust | `Vec<T>`, as without it |
| Gap | not a set: duplicates are neither rejected nor removed |
| Tests | [`SchemaMappingTest#arraysAreVecsAndUniqueItemsChangesNothing`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L166) |

## `enum`

### `schema.enum`

| | |
| --- | --- |
| OpenAPI | `type: string` with `enum`, as a component |
| Rust | `pub enum` with a unit variant per value (`#[serde(rename = "<value>")]`), deriving `Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Hash, Serialize, Deserialize`, and a `Display` that prints the value |
| Tests | [`SchemaMappingTest#aStringEnumIsAUnitEnumWithDisplay`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L174) |

### `schema.enum.inline`

| | |
| --- | --- |
| OpenAPI | an inline string `enum` as a property |
| Rust | an enum of the same shape, in the struct's file, named after the property (`Mode`, `Role`) |
| Options | `enumFromStr` (baseline) gives it the `Display` it otherwise lacks |
| Tests | [`SchemaMappingTest#anInlineEnumIsAnEnumInItsStructsFile`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L184), [`EnumFromStrTest#propertyEnumsInAStructFileGetDisplayToo`](../../src/test/java/be/timvw/openapi/rust/EnumFromStrTest.java#L79) |

### `schema.enum.integer`

| | |
| --- | --- |
| OpenAPI | `type: integer` with `enum` |
| Rust | `#[repr(i64)]` enum through `serde_repr`, variants `Variant1 = 1`, ... |
| Tests | [`SchemaMappingTest#anIntegerEnumIsReprI64`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L192), [`IntegersAsI64Test#integerEnumsKeepTheirRendering`](../../src/test/java/be/timvw/openapi/rust/IntegersAsI64Test.java#L111) |

### `schema.enum.from-str`

| | |
| --- | --- |
| OpenAPI | any enum of the rules above |
| Rust | `FromStr`, `TryFrom<&str>`, `TryFrom<&String>` and `TryFrom<String>`, accepting exactly what `Display` prints and returning `models::ParseEnumError` otherwise. Unions and structs get none. A schema named `ParseEnumError` fails generation |
| Options | `enumFromStr` (baseline) |
| Tests | [`EnumFromStrTest#stringEnumAcceptsWhatItsDisplayPrints`](../../src/test/java/be/timvw/openapi/rust/EnumFromStrTest.java#L58), [`#integerEnumAcceptsItsNumbers`](../../src/test/java/be/timvw/openapi/rust/EnumFromStrTest.java#L72), [`#unionsAndStructsGetNothing`](../../src/test/java/be/timvw/openapi/rust/EnumFromStrTest.java#L92), [`#schemaTakingTheErrorNameFails`](../../src/test/java/be/timvw/openapi/rust/EnumFromStrTest.java#L114), [`#emittedCodeCompilesAndRoundTrips`](../../src/test/java/be/timvw/openapi/rust/EnumFromStrTest.java#L250) |

### `schema.enum.open`

| | |
| --- | --- |
| OpenAPI | an enum that a response can hold |
| Rust | an extra unit variant `Unknown` (`#[serde(rename = "unknown", other)]`; an integer enum `Unknown = i64::MAX` through `#[serde(other)]`). It keeps `Copy`, `Eq`, `Hash` and `Ord`. An enum with a real value `unknown` keeps that as `Unknown`, and the catch-all is `UnknownValue` (`"unknown_value"`) |
| Wire | a value the spec does not list decodes to `Unknown` instead of failing the whole response. `Unknown` serializes as `"unknown"` (an integer enum: `i64::MAX`), which no listed value spells, so the API rejects it in a request; `Display` prints `unknown`; no string parses to it |
| Options | `openEnums` (baseline). Without it an unlisted value fails decoding |
| Tests | [`OpenEnumsTest#whatAResponseHoldsIsOpen`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L92), [`#whatOnlyARequestHoldsStaysClosed`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L101), [`#anInlineEnumFollowsItsStruct`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L111), [`#anInlineIntegerEnumOpensWhereItIsAnIntegerEnum`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L119), [`#whatTellsVariantsApartIsGuarded`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L132), [`#theCatchAllTakesANameAndAStringNoValueHas`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L153), [`#fromStrSkipsTheCatchAll`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L163), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L212) |

What opens: what a response body can hold, in any media type and of any status (event streams and error bodies
count), and what a webhook delivers. The generator walks the schemas from there through references, properties,
items, map values, branches and `allOf`. A type that requests use too opens; a type only requests reach stays closed.
What travels outside the API operations (a WebSocket event) is not reached.

What stays closed although a response can hold it: an enum that a union relies on to tell variants apart. Decoding
that tries variants in turn needs an earlier variant to reject what a later one is for, and a catch-all would change
what known input decodes to. So a closed enum in front of a free string or another enum, a `role` that tells two
structs apart, and anything in a struct in front of a map or `serde_json::Value` stay closed. Closed is decided per
enum. `unionPlanFile` lists them under `openEnums`. A value of another JSON kind (a number for a string enum) is still
an error. Neither enums nor unions get `#[non_exhaustive]`.

A tagged union opens too: [`discriminator.propertyName.unknown-value`](discriminator.md#discriminatorpropertynameunknown-value).

### `schema.enum.default`

| | |
| --- | --- |
| OpenAPI | any enum |
| Rust | no `Default` (stock picks the first variant, which is arbitrary) |
| Options | `defaultOnlyWhereValid` (baseline). Without it the first variant is the default |
| Tests | [`DefaultOnlyWhereValidTest#noEnumImplementsDefaultWhateverItsKind`](../../src/test/java/be/timvw/openapi/rust/DefaultOnlyWhereValidTest.java#L99), [`SchemaMappingTest#aStringEnumIsAUnitEnumWithDisplay`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L174) |

## `title`

### `schema.title`

| | |
| --- | --- |
| OpenAPI | an inline schema that becomes a model (an object property, array items, map values, a union branch, an operation's body, parameter or response), with or without a `title` |
| Rust | the model takes its `title` as its name only when the title is unique: no component and no other model has that Rust name, and every inline schema with the title has the same shape. Otherwise it is named after where it stands: property `p` of `P` is `PP`, items `PInner`, map values `PValue`, a branch `P<Title>` (untitled: `POneOf`/`PAnyOf`, or its position when two would share a name), an operation's body `<Op>Request`, parameter `<Op><Param>Parameter`, response `<Op><Code>Response`. The result does not depend on the order of the spec. Two structurally different schemas that would get one Rust name fail generation, naming both |
| Options | `qualifiedInlineNames` (baseline). Without it the stock names, which number a taken title (`Object0_17`) and can overwrite one model with another. `titleNaming=false` (openai-client-base) stops component and array titles from naming, merging or replacing types |
| Tests | [`QualifiedInlineNamesTest#aUniqueTitleNamesTheModelAndWhatLiesBelowIt`](../../src/test/java/be/timvw/openapi/rust/QualifiedInlineNamesTest.java#L148), [`#unnamedBranchesAreQualifiedInsteadOfNumbered`](../../src/test/java/be/timvw/openapi/rust/QualifiedInlineNamesTest.java#L131), [`#itemsAndValuesAreNamedAfterTheirContainer`](../../src/test/java/be/timvw/openapi/rust/QualifiedInlineNamesTest.java#L232), [`#operationSchemasAreNamedAfterTheOperation`](../../src/test/java/be/timvw/openapi/rust/QualifiedInlineNamesTest.java#L241), [`#theOutcomeDoesNotDependOnTheOrderOfTheSpec`](../../src/test/java/be/timvw/openapi/rust/QualifiedInlineNamesTest.java#L189), [`#anInlineSchemaNamedLikeAComponentFails`](../../src/test/java/be/timvw/openapi/rust/QualifiedInlineNamesTest.java#L266), [`TitleNamingTest#withTitleNamingOffEveryComponentKeepsItsOwnTypeAndArraysNameItemsByPath`](../../src/test/java/be/timvw/openapi/rust/TitleNamingTest.java#L91) |

## `allOf`

### `schema.allOf`

| | |
| --- | --- |
| OpenAPI | `allOf` of objects |
| Rust | one struct holding the properties of every part, required where any part requires them; each referenced part keeps its own struct |
| Tests | [`SchemaMappingTest#allOfObjectsIsOneStructWithEveryPartsProperties`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L200) |

### `schema.allOf.union`

| | |
| --- | --- |
| OpenAPI | a schema that is `allOf` of exactly one union |
| Rust | an enum with that union's variants, and its tag |
| Options | `modelUnions` (baseline) |
| Tests | [`ModelUnionsTest#aModelThatIsAllOfOneUnionHasThatUnionsVariants`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L112), [`UnionDispatchTest#aModelThatIsAllOfOneUnionHasItsTag`](../../src/test/java/be/timvw/openapi/rust/UnionDispatchTest.java#L115) |

## `oneOf`

### `schema.oneOf`

| | |
| --- | --- |
| OpenAPI | `oneOf` with two or more branches that are not `type: null` |
| Rust | a `pub enum` with `#[serde(untagged)]` and a variant per branch, holding the branch's type (`User(models::UserMessage)`, `Text(String)`, `ArrayOfStrings(Vec<String>)`). No `Default`. A variant is boxed only where it closes a cycle (`Branch(Box<models::Branch>)`) |
| Wire | decodes to the first variant, in [variant order](#schemaoneofvariant-order), that accepts the value; with a tag, by the tag ([`discriminator.propertyName`](discriminator.md#discriminatorpropertyname), [`schema.oneOf.implicit-tag`](#schemaoneofimplicit-tag)). Encodes as the variant's value, without a wrapper |
| Options | `modelUnions` (baseline). Without it: stock rendering, where a discriminated `oneOf` is `#[serde(tag = "...")]` that does not decode when the branch structs keep the tag field, and writes the tag twice |
| Tests | [`ModelUnionsTest#aDiscriminatedOneOfIsUntaggedSoTheTagIsWrittenOnce`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L91), [`#nothingIsEmptyAndNoUnionHasADefault`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L219), [`#aCycleThroughAUnionIsBoxedOnlyThere`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L262), [`#withoutTheOptionNothingChanges`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L284) |

### `schema.oneOf.variant-names`

| | |
| --- | --- |
| OpenAPI | the branches of a `oneOf` or `anyOf` |
| Rust | each variant is named, in order of preference, after the discriminator `mapping` key, the referenced schema, the branch's `title`, then its type (`Text`, `Integer`, `Number`, `ArrayOfStrings`, `ArrayOfIntegerArrays`). Arrays that share a title are named after their items |
| Options | `qualifiedInlineNames` (baseline) also gives an inline branch's model the union's name (`models::FormatPcm`), which the variant does not repeat (`Pcm`) |
| Tests | [`ModelUnionsTest#thePlanNamesEveryVariantWithItsBranch`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L269), [`#arraysSharingATitleAreNamedAfterTheirItems`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L187), [`#anInlineBranchKeepsItsOwnModel`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L141) |

### `schema.oneOf.variant-order`

| | |
| --- | --- |
| OpenAPI | the branches of a `oneOf` or `anyOf` |
| Rust | the variants are reordered so that, of two variants that accept the same JSON kind, the narrower comes first. Variants this says nothing about keep the order of the spec |
| Wire | an untagged enum decodes to the first variant that accepts the value, so the order decides what decodes |
| Options | `modelUnions` (baseline) |
| Tests | [`ModelUnionsTest#anIntegerGoesBeforeAFloatAndAnEnumBeforeAString`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L163), [`#aStructGoesBeforeOneThatWouldDecodeItsValues`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L179), [`#aUnionWithARicherArmGoesBeforeAStructItCouldLoseFieldsTo`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L170), [`#whatAcceptsEverythingComesLast`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L193) |

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
| Tests | [`ModelUnionsTest#anObjectNextToNullIsAStruct`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L132), [`#aNullableReferenceDoesNotRewriteItsTarget`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L120), [`ExtraRulesTest#nullableUnionFieldsCanHoldNull`](../../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java#L143), [`#nullableUnionFieldsAreOptionsWithoutTheOption`](../../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java#L151) |

### `schema.oneOf.enum-branch`

| | |
| --- | --- |
| OpenAPI | a branch that is an inline string `enum` |
| Rust | an enum of its own, `<Union><Variant>Enum` (`LogBatchSizeTextVariantEnum`), re-exported from `models`; the variant holds it (`TextVariant(models::LogBatchSizeTextVariantEnum)`) |
| Options | `modelUnions` (baseline). With `openEnums` (baseline) it gets an `Unknown` variant, unless it tells two variants apart |
| Tests | [`ModelUnionsTest#theIntegerBranchSurvivesAnEnumNextToIt`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L151), [`OpenEnumsTest#whatTellsVariantsApartIsGuarded`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L132) |

### `schema.oneOf.inline`

| | |
| --- | --- |
| OpenAPI | an inline `oneOf` or `anyOf` as the value of a property, with inline branches of which at least one is an object or array |
| Rust | the union becomes a model of its own, `<Schema><Property>` (`ThingChoice`), and the property holds it. Untitled primitive branches are titled `Text` and `Integer` |
| Options | always |
| Tests | [`SpecRulesTest#genericRulesShapeTheNames`](../../src/test/java/be/timvw/openapi/rust/SpecRulesTest.java#L130) |

### `schema.oneOf.helpers`

| | |
| --- | --- |
| OpenAPI | any `oneOf` or `anyOf` rendered as an enum |
| Rust | `Display`: a string or enum variant prints itself, any other its JSON. With a string variant also `From<String>`, `From<&str>` and `new_text`; constructors per variant such as `new_arrayofparts` |
| Options | `modelUnions` (baseline) |
| Tests | [`ModelUnionsTest#helpersKeepTheirNames`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L252), [`#aStringVariantGoesOutAsPlainText`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L232), [`#aJsonVariantPrintsItsJson`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L245) |

### `schema.oneOf.implicit-tag`

| | |
| --- | --- |
| OpenAPI | a `oneOf` or `anyOf` without a `discriminator`, where every branch requires one property with fixed string values that tell two variants apart |
| Rust | the same enum as [`schema.oneOf`](#schemaoneof), decoded by that property as if it were the discriminator ([`discriminator.propertyName`](discriminator.md#discriminatorpropertyname)). A branch that is a union has the property when all its arms do. When several properties qualify, the one with the fewest values on two variants wins, then `type`, then the first by name |
| Wire | as [`discriminator.propertyName`](discriminator.md#discriminatorpropertyname) |
| Options | `unionDispatch` (baseline). Without it the union decodes in variant order. A union where only some branches require the property, with a branch that is no object, or where the property has the same values everywhere, stays untagged |
| Tests | [`UnionDispatchTest#aRequiredPropertyWithFixedValuesOnEveryBranchIsATag`](../../src/test/java/be/timvw/openapi/rust/UnionDispatchTest.java#L121), [`#aBranchThatIsAUnionCountsThroughItsArms`](../../src/test/java/be/timvw/openapi/rust/UnionDispatchTest.java#L127), [`#anInlineBranchOfABranchCountsToo`](../../src/test/java/be/timvw/openapi/rust/UnionDispatchTest.java#L134), [`#thePropertyThatSharesFewestValuesIsTheTag`](../../src/test/java/be/timvw/openapi/rust/UnionDispatchTest.java#L142), [`#whatHasNoTagIsNotDispatched`](../../src/test/java/be/timvw/openapi/rust/UnionDispatchTest.java#L147) |

## `anyOf`

### `schema.anyOf`

| | |
| --- | --- |
| OpenAPI | `anyOf` with two or more branches that are not `type: null` |
| Rust | as [`schema.oneOf`](#schemaoneof), and every `schema.oneOf.*` rule applies the same way. A value that matches several branches decodes to the first in variant order |
| Options | `modelUnions` (baseline). Without it stock renders a discriminated `anyOf` as an empty type |
| Tests | [`ModelUnionsTest#aDiscriminatedAnyOfHasItsVariants`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L102) |

### `schema.anyOf.untyped`

| | |
| --- | --- |
| OpenAPI | an `anyOf` with no typed branch (`anyOf: [{}, {type: null}]`) |
| Rust | `serde_json::Value` |
| Options | always |
| Tests | [`ModelUnionsTest#aVariantHoldingAnUntypedAnyOfHoldsJsonValues`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L291) |
