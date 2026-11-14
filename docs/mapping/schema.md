# Schema Object

Rules for the [Schema Object](https://spec.openapis.org/oas/v3.0.3#schema-object), in the order of its fields: `type`
and `format`, the validation keywords, object fields (`properties`, `required`, `nullable`, `additionalProperties`,
`readOnly`, `writeOnly`, `default`, `deprecated`, `description`), arrays (`items`, `uniqueItems`), `enum`, `title`, and
composition (`allOf`, `oneOf`, `anyOf`). `$ref` is the
[Reference Object](reference.md); component names are the [Components Object](components.md).

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `SchemaMappingTest` renders
`src/test/resources/schema_mapping.yaml` without options.

## `type` and `format`

A format has one Rust type wherever it appears: in a model, a parameter (path, query, header) and a body or form
field ([decision 6](../decisions.md#6-uuid-parameters-are-str)). The crate depends on the format's crate (`chrono`,
`uuid`) only when the API uses the format: in a model, a parameter or a response. A fallback to `String` exists only where a
user needs it: `useChrono=false`. `email`, `uri` and `decimal` stay `String`.

### `schema.type.string`

| | |
| --- | --- |
| OpenAPI | `type: string`, without a format below (also `format: email`, `uri`, `hostname`, or one the generator does not know) |
| Rust | `String` |
| Tests | [`SchemaMappingTest#scalarsAndFormats`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L67) |

### `schema.format.date-time`

| | |
| --- | --- |
| OpenAPI | `type: string` with `format: date-time` or `format: date` |
| Rust | `chrono::DateTime<chrono::FixedOffset>` (`date-time`) and `chrono::NaiveDate` (`date`); the crate depends on `chrono` when a model, a parameter or a response has one |
| Wire | RFC 3339 text, through chrono's serde support. A path, query or header parameter, an urlencoded form field and a multipart text part are written the same way: a `date-time` with `to_rfc3339()` (`2026-01-02T03:04:05+01:00`, not chrono's `Display`, `2026-01-02 03:04:05 +01:00`), a `date` as `YYYY-MM-DD`, each item of an array alike; percent-encoded in the path and the query (`%3A`, `%2B`) |
| Options | `useChrono` (default `true`, as in stock). With `useChrono=false` both are `String`, sent as they are, and the crate has no `chrono` dependency (openai-client-base sets this; langfuse-client-base keeps the default). Stock sends a parameter with chrono's `Display` |
| Tests | [`SchemaMappingTest#datesAreChronoUnlessUseChronoIsOff`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L82), [`DateParamsTest#aDateTimeIsSentInRfc3339`](../../src/test/java/be/timvw/openapi/rust/DateParamsTest.java#L71), [`#aDateIsSentWithDisplay`](../../src/test/java/be/timvw/openapi/rust/DateParamsTest.java#L108), [`#aDateTimeFormFieldIsSentInRfc3339`](../../src/test/java/be/timvw/openapi/rust/DateParamsTest.java#L90), [`#withoutChronoTheyAreStringsSentAsTheyAre`](../../src/test/java/be/timvw/openapi/rust/DateParamsTest.java#L120), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/DateParamsTest.java#L137) |

### `schema.format.uuid`

| | |
| --- | --- |
| OpenAPI | `type: string, format: uuid` |
| Rust | `uuid::Uuid` everywhere: in a model, and as a path, query or header parameter or form field, taken by value (`Option<uuid::Uuid>` when optional, `Vec<uuid::Uuid>` for an array); the crate depends on `uuid` when a model, a parameter or a response (success or error) has one. Stock takes a parameter as `&str` |
| Wire | the hyphenated lower-case form (`67e55044-10b1-426f-9247-bb680e5fe0c8`), through serde in a body and `Display` in a parameter or form field; URL-safe, so it goes into the path as it is. An array is joined with `,` (path, header, form field, multipart text part, query with `explode: false`), repeated in the query with `explode: true` |
| Tests | [`SchemaMappingTest#scalarsAndFormats`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L67), [`#aUuidParameterIsAUuid`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L101), [`UuidParamsTest#aUuidParameterIsAUuid`](../../src/test/java/be/timvw/openapi/rust/UuidParamsTest.java#L81), [`#itIsSentWithDisplay`](../../src/test/java/be/timvw/openapi/rust/UuidParamsTest.java#L95), [`#theCrateDependsOnUuidOnlyWhenTheApiUsesIt`](../../src/test/java/be/timvw/openapi/rust/UuidParamsTest.java#L115), [`#theCrateDependsOnChronoWhenOnlyAResponseUsesIt`](../../src/test/java/be/timvw/openapi/rust/UuidParamsTest.java#L130), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/UuidParamsTest.java#L142) |

### `schema.format.byte`

| | |
| --- | --- |
| OpenAPI | `type: string, format: byte` |
| Rust | `Vec<u8>`, with `#[serde_as(as = "serde_with::base64::Base64")]` |
| Wire | base64 text |
| Tests | [`SchemaMappingTest#scalarsAndFormats`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L67) |

### `schema.format.binary`

| | |
| --- | --- |
| OpenAPI | `type: string, format: binary` (a file) |
| Rust | as a request body, multipart part or parameter: `timvw_rust_runtime::Upload`, what to send (a path, bytes, a reader or a stream; the builder setter takes `impl Into<Upload>`, a `PathBuf` too). As a response: `timvw_rust_runtime::Response`, to read as bytes or a stream, or `save_to` a file |
| Wire | the file's bytes, streamed from disk with a `Content-Length` (a part typed from the file's extension); the response body as it comes |
| Library | `timvw_rust_runtime::Response`; `timvw_rust_runtime::Upload`, sent as a `timvw_rust_runtime::Body` or `timvw_rust_runtime::multipart::Part::upload`, and opened again by a retry |
| Options | `fileProperties` renders listed `Schema.property` entries as files |
| Tests | [`SchemaMappingTest#binaryIsAFileToSendOrAResponseToRead`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L107), [`EventStreamsTest#requestBodiesGoOutAsTheirMediaType`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L150) |

### `schema.type.integer`

| | |
| --- | --- |
| OpenAPI | `type: integer` |
| Rust | `i64`; `format: int32` gives `i32`. Every other format (`int64`, `unixtime`, none, unknown, `int8`, `int16`, `uint8` to `uint64`) gives `i64`. Bounds (`minimum`, `maximum`) never pick a type, and nothing is unsigned or `NonZero`. This holds everywhere an integer appears: fields, items, map values, parameters, multipart fields, responses, constructors, builders and union variants |
| Options | always (stock renders `i32` unless `format: int64`, and `i8`/`i16` and `u8` to `u64` for the sized formats). Bounds never pick a type: stock's `preferUnsignedInt` and `bestFitInt` are rejected |
| Tests | [`SchemaMappingTest#scalarsAndFormats`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L67), [`IntegersAsI64Test#onlyInt32StaysI32OnModelFields`](../../src/test/java/be/timvw/openapi/rust/IntegersAsI64Test.java#L64), [`#sizedAndUnsignedFormatsAreI64`](../../src/test/java/be/timvw/openapi/rust/IntegersAsI64Test.java#L72), [`#containerItemsFollowTheRule`](../../src/test/java/be/timvw/openapi/rust/IntegersAsI64Test.java#L84), [`#pathQueryAndHeaderParametersFollowTheRule`](../../src/test/java/be/timvw/openapi/rust/IntegersAsI64Test.java#L120) |

### `schema.type.integer-with-float-format`

| | |
| --- | --- |
| OpenAPI | `type: integer` with `format: float` or `format: double` |
| Rust | read as `type: number` (logged) |
| Options | always |
| Tests | [`GenerationTest#integerWithFloatFormatIsRenderedAsNumber`](../../src/test/java/be/timvw/openapi/rust/GenerationTest.java#L106), [`SpecNormalizerTest#rewritesIntegerWithFloatFormatWhereverItAppears`](../../src/test/java/be/timvw/openapi/rust/SpecNormalizerTest.java#L31) |

### `schema.type.number`

| | |
| --- | --- |
| OpenAPI | `type: number` |
| Rust | `f64`; `format: float` gives `f32` |
| Tests | [`SchemaMappingTest#scalarsAndFormats`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L67), [`IntegersAsI64Test#numbersAreUntouched`](../../src/test/java/be/timvw/openapi/rust/IntegersAsI64Test.java#L99) |

### `schema.type.boolean`

| | |
| --- | --- |
| OpenAPI | `type: boolean` |
| Rust | `bool` |
| Tests | [`SchemaMappingTest#scalarsAndFormats`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L67) |

### `schema.type.scalar-component`

| | |
| --- | --- |
| OpenAPI | a component schema that is only a scalar (`Alias: {type: string}`) |
| Rust | no type of its own: a reference to it is the scalar, documented with the component's description |
| Options | `stringIdSchemas` renders listed components as `String` whatever they hold; `stringIdProperties` and `stringProperties` render listed properties as `String` (a `stringProperties` entry that can be null stays nullable) |
| Tests | [`SchemaMappingTest#aScalarComponentHasNoTypeOfItsOwn`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L154), [`SpecRulesTest#idSchemasAndIdPropertiesBecomeStrings`](../../src/test/java/be/timvw/openapi/rust/SpecRulesTest.java#L111), [`#stringProperties`](../../src/test/java/be/timvw/openapi/rust/SpecRulesTest.java#L124), [`StringFirstUnionsTest#stringPropertiesKeepTheirNullability`](../../src/test/java/be/timvw/openapi/rust/StringFirstUnionsTest.java#L99) |

### `schema.pattern`

| | |
| --- | --- |
| OpenAPI | the validation keywords: `pattern`, `minLength`, `maxLength`, `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum` (3.0 booleans and 3.1 numbers), `multipleOf`, `minItems`, `maxItems`, `uniqueItems`, `minProperties`, `maxProperties` |
| Rust | the type is the one of the rules above. Every model implements `crate::models::validation::Validate` (`fn validate(&self) -> Result<(), ValidationErrors>`), also a model without constraints of its own (an enum's `validate()` is `Ok(())`). It checks the keywords of the model's fields: `minLength`/`maxLength` of a `String` in characters, `pattern` with the `regex` crate (`regex::Regex`, compiled once in a `LazyLock`), the number bounds and `multipleOf` (an integer against an integer literal, a float against a literal of its own type, `multipleOf` of a float within a relative tolerance), `minItems`/`maxItems`/`uniqueItems` of a `Vec`, `minProperties`/`maxProperties` of a map; and recursively every nested model, `Vec` item, map value, `Option` that holds a value, `Box` and the active variant of a union. Enums, `serde_json::Value`, byte arrays, files, dates and other typed scalars have nothing to check; `minProperties` of a struct is not checked. A float bound beyond `f32` is compared as `f64`; one beyond `f64`, and an integer bound every value of its type meets, is not checked. `ValidationError` holds the path in wire names (`messages[2].content`, `metadata["key"]`), the `Rule` and the offending value or size, and implements `Display` and `std::error::Error`. `validate()` returns every one, as `ValidationErrors` (never empty; `len()`, `iter()`, `into_vec()`, `IntoIterator` for it and `&` it, `Display` one line per error, `std::error::Error`): every rule of every field, in field (declaration) order, nested values depth-first, the entries of a map ordered by their rendered (escaped) key. The checks push onto a `Vec` passed down with the path (`Validate::validate_into(&self, path: &Path<'_>, errors: &mut Vec<ValidationError>)`, which a hand-written impl implements), and the path is written out only for an error, so a valid value allocates nothing. The field's documentation lists its constraints (``Validation: `minLength: 1`, `maxLength: 10`.``) |
| Wire | unchanged: nothing generated calls `validate()`, neither before a request nor after decoding a response, and `Error` has no validation variant. A `pattern` is checked only when it uses constructs both engines read alike and `regex` compiles (literals, `.`, anchors, groups `(...)`, `(?:...)`, `(?<name>...)`, quantifiers, `\d \w \s \b` and their negations, code point escapes, general categories `\p{L}`, escaped punctuation, classes with single-character ranges in order, code points that are Unicode scalar values, distinct group names, repetition counts that multiply to at most 1000, or 100 for `\w`, `\p{..}` and other large Unicode classes); any other pattern (lookaround, backreferences, `\Z`, `\c`, `[]`, `[^]`, `a{,3}`, `\uD83D`, `[z-a]`, a group name twice, `\w{1000}`, nested classes, set operations, inline flags, ...) is skipped with a generation warning naming the construct. `regex` classes such as `\d` are Unicode-aware where ECMA-262's are ASCII. A limit in `ValidationError::rule` is an `f64`, so an integer limit above 2^53 shows rounded |
| Options | `validateMethods` (default `true`): `false` generates no `models::validation` module, no impls and no `regex` dependency; the field documentation stays. `regex` is a dependency only when a checked `pattern` exists. A schema whose module would be `models::validation` fails generation unless the option is `false` |
| Tests | [`ValidateTest#everyModelImplementsTheTrait`](../../src/test/java/be/timvw/openapi/rust/ValidateTest.java#L73), [`#stringsNumbersListsAndMapsAreChecked`](../../src/test/java/be/timvw/openapi/rust/ValidateTest.java#L109), [`#nestedModelsAreChecked`](../../src/test/java/be/timvw/openapi/rust/ValidateTest.java#L131), [`#unionsCheckTheActiveVariant`](../../src/test/java/be/timvw/openapi/rust/ValidateTest.java#L142), [`#whatHasNothingToCheckIsSkipped`](../../src/test/java/be/timvw/openapi/rust/ValidateTest.java#L153), [`#aPatternIsMatchedWithARegexCompiledOnce`](../../src/test/java/be/timvw/openapi/rust/ValidateTest.java#L165), [`#withoutAPatternThereIsNoRegexDependency`](../../src/test/java/be/timvw/openapi/rust/ValidateTest.java#L175), [`#openApi31ExclusiveBoundsAreNumbers`](../../src/test/java/be/timvw/openapi/rust/ValidateTest.java#L181), [`#constraintsAreDocumented`](../../src/test/java/be/timvw/openapi/rust/ValidateTest.java#L191), [`#theOptionTurnsTheGenerationOff`](../../src/test/java/be/timvw/openapi/rust/ValidateTest.java#L203), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/ValidateTest.java#L248), [`SchemaMappingTest#validationKeywordsAreCheckedByValidateAndDocumented`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L92) |

## Objects

### `schema.properties`

| | |
| --- | --- |
| OpenAPI | `type: object` with `properties` |
| Rust | `pub struct` with `#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, bon::Builder)]`, one `pub` field per property with `#[serde(rename = "<name>")]`, and `new(<required fields>)` that sets the optional ones to `None`. Field names are `snake_case`; two properties that become the same field (`foo-bar` and `foo_bar`) fail generation, naming both |
| Options | `bonBuilders` (default `true`) adds the `bon::Builder` derive |
| Tests | [`SchemaMappingTest#propertiesAreAStructWithAConstructorOfTheRequiredFields`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L114), [`GenerationTest#modelStructsDeriveBonBuilderAndEnumsDoNot`](../../src/test/java/be/timvw/openapi/rust/GenerationTest.java#L47), [`UnionParamsTest#twoPropertiesWithTheSameRustNameFail`](../../src/test/java/be/timvw/openapi/rust/UnionParamsTest.java#L97) |

### `schema.required`

| | |
| --- | --- |
| OpenAPI | a property listed in `required`, or not |
| Rust | required: `T`, a parameter of `new()`. Optional: `Option<T>` with `skip_serializing_if = "Option::is_none"` |
| Wire | an optional field that is `None` is left out |
| Options | `optionalProperties` removes listed properties from `required`, for properties the API leaves out (a required nullable one becomes optional and nullable) |
| Tests | [`SchemaMappingTest#propertiesAreAStructWithAConstructorOfTheRequiredFields`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L114), [`OptionalPropertiesTest#aListedPropertyIsAnOptionSkippedWhenNone`](../../src/test/java/be/timvw/openapi/rust/OptionalPropertiesTest.java#L89), [`#aRequiredNullablePropertyBecomesAnOptionalNullableOne`](../../src/test/java/be/timvw/openapi/rust/OptionalPropertiesTest.java#L112) |

### `schema.nullable`

| | |
| --- | --- |
| OpenAPI | `nullable: true` (OpenAPI 3.1: `type: [T, "null"]`) |
| Rust | optional: `Option<Option<T>>` through `serde_with::rust::double_option`. Required: `Option<T>`, which must be present. A nullable union follows [`schema.oneOf.null-branch`](#schemaoneofnull-branch) |
| Wire | optional: `None` leaves the field out, `Some(None)` sends `null`. Required: `None` sends `null` |
| Tests | [`SchemaMappingTest#anOptionalNullablePropertyCanSendNull`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L125), [`DefaultOnlyWhereValidTest#aRequiredNullableFieldCountsAsRequired`](../../src/test/java/be/timvw/openapi/rust/DefaultOnlyWhereValidTest.java#L72), [`ModelUnionsTest#aNullableReferenceDoesNotRewriteItsTarget`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L103) |

### `schema.nullable.items`

| | |
| --- | --- |
| OpenAPI | the `items` of an array or the `additionalProperties` of a map that can be null: `nullable: true` (3.0); `type: [T, "null"]` or a `oneOf`/`anyOf` with a `null` branch (3.1); or a `$ref` to a schema that is one of these (directly or as the single entry of an `allOf`). At any depth, and for an inline enum too |
| Rust | `Option<T>` as the item or value: `Vec<Option<T>>`, `HashMap<String, Option<T>>`, `HashMap<String, Option<Vec<Option<T>>>>`, `Vec<Option<<Property>>>` for an inline enum. Free-form values stay `serde_json::Value`, which holds `null` itself (also a 3.1 `anyOf`/`oneOf: [{}, {type: "null"}]` and `type: "null"` items, [`schema.type.null`](#schematypenull)). The items of a query, path, header or cookie parameter and of a form field (urlencoded or multipart, a file part included) stay `T`, because `null` cannot be written in those values. A union's array variant of nullable items is named `ArrayOfNullable<Items>` (`ArrayOfNullableStrings`). `validate()` checks the items that are `Some` and skips the ones that are `None` |
| Wire | `null` decodes to `None`. `None` serializes as `null` and is never skipped |
| Tests | [`NullableItemsTest#nullableItemsAndValuesAreOptionsAtAnyDepth`](../../src/test/java/be/timvw/openapi/rust/NullableItemsTest.java#L54), [`#theNullableFormsOf31AreOptionsToo`](../../src/test/java/be/timvw/openapi/rust/NullableItemsTest.java#L86), [`#responsesAndBodiesFollowTheRuleParametersAndFormFieldsDoNot`](../../src/test/java/be/timvw/openapi/rust/NullableItemsTest.java#L98), [`#nullItemsDecodeSerializeAndValidate`](../../src/test/java/be/timvw/openapi/rust/NullableItemsTest.java#L111) |

### `schema.properties.reference`

| | |
| --- | --- |
| OpenAPI | a property that references another model |
| Rust | the model, by value (`Option<models::Owner>`). A reference that closes a cycle of the model graph is boxed (`Option<Box<models::ThreadNode>>`), including a cycle through a union's variants, an inline union's too (`Top` → `TopChild` → `Leaf` → `Top`); `Vec` and map items are never boxed, since they already are on the heap |
| Options | always (stock boxes every model reference) |
| Tests | [`BoxOnlyCyclesTest#acyclicReferencesAreHeldByValue`](../../src/test/java/be/timvw/openapi/rust/BoxOnlyCyclesTest.java#L47), [`#selfReferenceIsBoxedButNotInsideVec`](../../src/test/java/be/timvw/openapi/rust/BoxOnlyCyclesTest.java#L59), [`#mutualRecursionIsBoxedBothWays`](../../src/test/java/be/timvw/openapi/rust/BoxOnlyCyclesTest.java#L69), [`ParamSerializationTest#aCycleThroughAnInlineUnionIsBoxed`](../../src/test/java/be/timvw/openapi/rust/ParamSerializationTest.java#L118) |

### `schema.additionalProperties`

| | |
| --- | --- |
| OpenAPI | `additionalProperties: <schema>`, or `true` |
| Rust | `std::collections::HashMap<String, T>`; `true` gives `HashMap<String, serde_json::Value>`. Values that are an inline enum give `HashMap<String, <Property>>`, with the enum `<Property>` rendered once next to the struct; at any depth of lists and maps the enum is typed at every level (`HashMap<String, Vec<<Property>>>`, `Vec<HashMap<String, <Property>>>`, `HashMap<String, HashMap<String, <Property>>>`, `HashMap<String, Vec<Vec<<Property>>>>`, ...), a list of the enum itself with `uniqueItems` being a `HashSet` and a list of lists or maps a `Vec`. `validate()` checks the `minProperties`/`maxProperties` and `minItems`/`maxItems`/`uniqueItems` of every map and list that holds the enum, like any map's ([`schema.pattern`](#schemapattern)). An operation's inline enum (a request body or response that is a map or list of one) stays `String`, in containers as alone |
| Tests | [`SchemaMappingTest#mapsAndFreeFormObjects`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L132), [`SmallFixesTest#aMapOfAnInlineEnumIsAMapOfTheEnum`](../../src/test/java/be/timvw/openapi/rust/SmallFixesTest.java#L92), [`#anInlineEnumNestedAtAnyDepthIsTypedAtEveryLevel`](../../src/test/java/be/timvw/openapi/rust/SmallFixesTest.java#L216) |

### `schema.type.free-form-object`

| | |
| --- | --- |
| OpenAPI | `type: object` without `properties`, or `{}` |
| Rust | `serde_json::Value`. `{}` is nullable as well: an optional property is `Option<Option<serde_json::Value>>` |
| Tests | [`SchemaMappingTest#mapsAndFreeFormObjects`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L132) |

### `schema.type.null`

| | |
| --- | --- |
| OpenAPI | a schema of only `null` (OpenAPI 3.1 `type: "null"`, `type: ["null"]`, `enum: [null]`, a single-entry `allOf` of one, a union of only `null` branches), or of any value or `null` (`oneOf` or `anyOf: [{}, {type: "null"}]`: every branch `{}` or `type: "null"`, nothing else next to them; a 3.0 spec written so too). As a property, list item, map value, component, union variant, request body or response. A `type: "null"` branch of a union with other branches, or a `$ref` branch to a component of only `null`, makes that union nullable instead ([`schema.oneOf.null-branch`](#schemaoneofnull-branch)). A union with a branch of any value or `null` next to other branches (`oneOf: [{$ref: Obj}, {anyOf: [{}, {type: "null"}]}]`, with nothing else next to the union) holds any value: it is `{}` too |
| Rust | `serde_json::Value`, as [`{}`](#schematypefree-form-object): nullable, so an optional property is `Option<Option<serde_json::Value>>` and a required one `Option<serde_json::Value>`; a list item or map value is `serde_json::Value` without an `Option` (it holds `null`, `Value::Null`); a component is no model of its own, its references are `serde_json::Value`. A `type: "null"` schema is not restricted to `null` in Rust: the type holds any value. A query, path, header or cookie parameter or a form field of only `null` (or a list of it) fails generation, naming it: it has no `null` to send. A form field of any value or `null` is `serde_json::Value`, sent as its JSON text ([`requestBody.content.form`](request-body.md#requestbodycontentform)) |
| Wire | `null` decodes to `Value::Null` (a property to `Some(None)`, or `None` when required) and is written back as `null`; any other value decodes to that value |
| Options | always; before, these referenced models that were never written (`models::Null`, `models::OneOfLessThanGreaterThan`), which did not compile, and a component of `oneOf: [{}, {type: "null"}]` was an empty struct, which `null` did not decode to. A union of an object and a branch of any value or `null` (`anyOf`) was an untagged enum of the object and `serde_json::Value`; it is `serde_json::Value`, which decodes the same values |
| Tests | [`NullShapesTest#nullAndAnyOrNullAreJsonValuesEverywhere`](../../src/test/java/be/timvw/openapi/rust/NullShapesTest.java#L64), [`#nullAndValuesRoundTrip`](../../src/test/java/be/timvw/openapi/rust/NullShapesTest.java#L216), [`#aParameterOfOnlyNullFailsGeneration`](../../src/test/java/be/timvw/openapi/rust/NullShapesTest.java#L120), [`#everyParameterOfOnlyNullFailsGeneration`](../../src/test/java/be/timvw/openapi/rust/NullShapesTest.java#L146), [`#aFormFieldOfOnlyNullFailsGeneration`](../../src/test/java/be/timvw/openapi/rust/NullShapesTest.java#L126), [`#aFormFieldOfOnlyNullBehindAllOfFailsGeneration`](../../src/test/java/be/timvw/openapi/rust/NullShapesTest.java#L171) |

### `schema.readOnly`

| | |
| --- | --- |
| OpenAPI | `readOnly: true` or `writeOnly: true` |
| Rust | an ordinary field, in requests and in responses |
| Gap | a read-only field can be sent, and a write-only field is expected back; a required one makes `new()` ask for it |
| Tests | [`SchemaMappingTest#readOnlyAndWriteOnlyAreFieldsInBothDirections`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L140) |

### `schema.default`

| | |
| --- | --- |
| OpenAPI | `default: <value>` |
| Rust | not applied: the field is `Option<T>` like any optional field, and the default is noted in its documentation (``Defaults to `false`.``) |
| Options | always |
| Tests | [`ExtraRulesTest#defaultsAreDocumented`](../../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java#L147) |

### `schema.properties.default-impl`

| | |
| --- | --- |
| OpenAPI | any object |
| Rust | `Default` is derived only on a struct without required fields (a required nullable field counts as required), so a default value is one the API accepts. No enum implements `Default` (see [`schema.enum.default`](#schemaenumdefault)) |
| Options | always (stock derives `Default` on every struct) |
| Tests | [`DefaultOnlyWhereValidTest#aRequiredFieldTakesTheDeriveAway`](../../src/test/java/be/timvw/openapi/rust/DefaultOnlyWhereValidTest.java#L62), [`#aRequiredNullableFieldCountsAsRequired`](../../src/test/java/be/timvw/openapi/rust/DefaultOnlyWhereValidTest.java#L72), [`#anAllOptionalStructKeepsItWhateverItsFieldTypes`](../../src/test/java/be/timvw/openapi/rust/DefaultOnlyWhereValidTest.java#L81) |

### `schema.properties.display`

| | |
| --- | --- |
| OpenAPI | any object |
| Rust | no `Display`. Where a struct goes into a path, query, header, form or multipart text part, the API code serializes it with `serde_json::to_string` |
| Wire | the struct's JSON; a serialization failure is `Error::Serde` |
| Options | always |
| Tests | [`NoStructDisplayTest#structsHaveNoDisplay`](../../src/test/java/be/timvw/openapi/rust/NoStructDisplayTest.java#L58), [`#pathParameter`](../../src/test/java/be/timvw/openapi/rust/NoStructDisplayTest.java#L68), [`#nothingStillCallsDisplayOnAStruct`](../../src/test/java/be/timvw/openapi/rust/NoStructDisplayTest.java#L131) |

### `schema.deprecated`

| | |
| --- | --- |
| OpenAPI | `deprecated: true` on a property or a schema |
| Rust | no marker |
| Options | `deprecatedAttributes` (langfuse-client-base) adds `#[deprecated]` to the field or type, and generated files start with `#![allow(deprecated)]` |
| Tests | [`ExtraRulesTest#deprecatedFieldsAndModelsAreMarked`](../../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java#L156), [`#nothingIsDeprecatedWithoutTheOption`](../../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java#L166) |

### `schema.description`

| | |
| --- | --- |
| OpenAPI | `description` |
| Rust | `///` on the type or field; bare URLs are wrapped in `<...>` (rustdoc's `bare_urls`). A type without a description has no empty `///` |
| Tests | [`SchemaMappingTest#descriptionsAreDocCommentsWithBareUrlsWrapped`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L147), [`GenerationTest#bareUrlsInDocCommentsAreWrapped`](../../src/test/java/be/timvw/openapi/rust/GenerationTest.java#L90), [`ExtraRulesTest#enumsWithoutADescriptionHaveNoEmptyDocComment`](../../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java#L206) |

## Arrays

### `schema.items`

| | |
| --- | --- |
| OpenAPI | `type: array` with `items` |
| Rust | `Vec<T>`; items are never boxed. Inline object items are a model named after the array ([`schema.title`](#schematitle)) |
| Tests | [`SchemaMappingTest#arraysAreVecsAndUniqueItemsChangesNothing`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L160), [`BoxOnlyCyclesTest#selfReferenceIsBoxedButNotInsideVec`](../../src/test/java/be/timvw/openapi/rust/BoxOnlyCyclesTest.java#L59) |

### `schema.uniqueItems`

| | |
| --- | --- |
| OpenAPI | `uniqueItems: true` |
| Rust | `Vec<T>`, as without it. An array whose items are an inline enum itself is a `std::collections::HashSet`; an array of lists or maps of one stays a `Vec`. The model's `validate()` reports two equal items of a `Vec` (by `PartialEq`, so any item type works; see [`schema.pattern`](#schemapattern)) |
| Wire | duplicates are neither rejected when decoding nor removed |
| Tests | [`SchemaMappingTest#arraysAreVecsAndUniqueItemsChangesNothing`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L160) |

## `enum`

### `schema.enum`

| | |
| --- | --- |
| OpenAPI | `type: string` with `enum`, as a component |
| Rust | `pub enum` with a unit variant per value (`#[serde(rename = "<value>")]`), deriving `Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Hash, Serialize, Deserialize`, and a `Display` that prints the value |
| Tests | [`SchemaMappingTest#aStringEnumIsAUnitEnumWithDisplay`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L168) |

### `schema.enum.inline`

| | |
| --- | --- |
| OpenAPI | an inline string `enum` as a property |
| Rust | an enum of the same shape, in the struct's file, named after the property (`Mode`, `Role`) |
| Options | always; it has the `Display` stock does not give a property enum |
| Tests | [`SchemaMappingTest#anInlineEnumIsAnEnumInItsStructsFile`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L178), [`EnumFromStrTest#propertyEnumsInAStructFileGetDisplayToo`](../../src/test/java/be/timvw/openapi/rust/EnumFromStrTest.java#L72) |

### `schema.enum.integer`

| | |
| --- | --- |
| OpenAPI | `type: integer` (any `format`) with `enum`: a component, an inline enum property, or the items or values of lists and maps holding an inline enum at any depth |
| Rust | `#[repr(i64)]` enum through `serde_repr`, variants `Variant1 = 1`, ... The innermost items decide: `type: array, items: {type: integer, enum: [1, 2]}` is `Vec<<Property>>` of such an enum, typed in its containers as [`schema.additionalProperties`](#schemaadditionalproperties) describes |
| Wire | a JSON number: `[1]` and `{"a": 2}` decode, and serialize back as numbers. An open integer enum's `Unknown` is `i64::MAX`, so one that lists `9223372036854775807` fails generation, naming the schema |
| Tests | [`SchemaMappingTest#anIntegerEnumIsReprI64`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L186), [`IntegersAsI64Test#integerEnumsKeepTheirRendering`](../../src/test/java/be/timvw/openapi/rust/IntegersAsI64Test.java#L106), [`SmallFixesTest#anIntegerInlineEnumIsAnIntegerEnumAtAnyDepth`](../../src/test/java/be/timvw/openapi/rust/SmallFixesTest.java#L267) |
| Gap | `type: number` with `enum` is a string enum (`#[serde(rename = "1.5")]`), which a JSON number does not decode to: `serde_repr` takes integers only |

### `schema.enum.from-str`

| | |
| --- | --- |
| OpenAPI | any enum of the rules above |
| Rust | `FromStr`, `TryFrom<&str>`, `TryFrom<&String>` and `TryFrom<String>`, accepting exactly what `Display` prints and returning `models::ParseEnumError` otherwise. Unions and structs get none. A schema named `ParseEnumError` fails generation |
| Options | always |
| Tests | [`EnumFromStrTest#stringEnumAcceptsWhatItsDisplayPrints`](../../src/test/java/be/timvw/openapi/rust/EnumFromStrTest.java#L51), [`#integerEnumAcceptsItsNumbers`](../../src/test/java/be/timvw/openapi/rust/EnumFromStrTest.java#L65), [`#unionsAndStructsGetNothing`](../../src/test/java/be/timvw/openapi/rust/EnumFromStrTest.java#L85), [`#schemaTakingTheErrorNameFails`](../../src/test/java/be/timvw/openapi/rust/EnumFromStrTest.java#L99), [`#emittedCodeCompilesAndRoundTrips`](../../src/test/java/be/timvw/openapi/rust/EnumFromStrTest.java#L235) |

### `schema.enum.open`

| | |
| --- | --- |
| OpenAPI | an enum that a response can hold |
| Rust | an extra unit variant `Unknown` (`#[serde(rename = "unknown", other)]`; an integer enum `Unknown = i64::MAX` through `#[serde(other)]`). It keeps `Copy`, `Eq`, `Hash` and `Ord`. An enum with a real value `unknown` keeps that as `Unknown`, and the catch-all is `UnknownValue` (`"unknown_value"`) |
| Wire | a value the spec does not list decodes to `Unknown` instead of failing the whole response. `Unknown` serializes as `"unknown"` (an integer enum: `i64::MAX`), which no listed value spells, so the API rejects it in a request; `Display` prints `unknown`; no string parses to it |
| Options | always |
| Tests | [`OpenEnumsTest#whatAResponseHoldsIsOpen`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L76), [`#whatOnlyARequestHoldsStaysClosed`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L85), [`#anInlineEnumFollowsItsStruct`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L95), [`#anInlineIntegerEnumOpensWhereItIsAnIntegerEnum`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L103), [`#whatTellsVariantsApartIsGuarded`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L116), [`#theCatchAllTakesANameAndAStringNoValueHas`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L137), [`#fromStrSkipsTheCatchAll`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L147), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L161) |

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
| Options | always |
| Tests | [`DefaultOnlyWhereValidTest#noEnumImplementsDefaultWhateverItsKind`](../../src/test/java/be/timvw/openapi/rust/DefaultOnlyWhereValidTest.java#L94), [`SchemaMappingTest#aStringEnumIsAUnitEnumWithDisplay`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L168) |

## `title`

### `schema.title`

| | |
| --- | --- |
| OpenAPI | an inline schema that becomes a model (an object property, array items, map values, a union branch, an operation's body, parameter or response), with or without a `title` |
| Rust | the model takes its `title` as its name only when the title is unique: no component and no other model has that Rust name, and every inline schema with the title has the same shape. Otherwise it is named after where it stands: property `p` of `P` is `PP`, items `PInner`, map values `PValue`, a branch `P<Title>` (untitled: `POneOf`/`PAnyOf`, or its position when two would share a name), an operation's body `<Op>Request`, parameter `<Op><Param>Parameter`, response `<Op><Code>Response`. The result does not depend on the order of the spec. Two structurally different schemas that would get one Rust name fail generation, naming both |
| Options | `titleNaming=false` (openai-client-base) stops component and array titles from naming, merging or replacing types. Stock names number a taken title (`Object0_17`) and can overwrite one model with another |
| Tests | [`QualifiedInlineNamesTest#aUniqueTitleNamesTheModelAndWhatLiesBelowIt`](../../src/test/java/be/timvw/openapi/rust/QualifiedInlineNamesTest.java#L107), [`#unnamedBranchesAreQualifiedInsteadOfNumbered`](../../src/test/java/be/timvw/openapi/rust/QualifiedInlineNamesTest.java#L93), [`#itemsAndValuesAreNamedAfterTheirContainer`](../../src/test/java/be/timvw/openapi/rust/QualifiedInlineNamesTest.java#L189), [`#operationSchemasAreNamedAfterTheOperation`](../../src/test/java/be/timvw/openapi/rust/QualifiedInlineNamesTest.java#L198), [`#theOutcomeDoesNotDependOnTheOrderOfTheSpec`](../../src/test/java/be/timvw/openapi/rust/QualifiedInlineNamesTest.java#L148), [`#anInlineSchemaNamedLikeAComponentFails`](../../src/test/java/be/timvw/openapi/rust/QualifiedInlineNamesTest.java#L223), [`TitleNamingTest#withTitleNamingOffEveryComponentKeepsItsOwnTypeAndArraysNameItemsByPath`](../../src/test/java/be/timvw/openapi/rust/TitleNamingTest.java#L91) |

## `allOf`

### `schema.allOf`

| | |
| --- | --- |
| OpenAPI | `allOf` of objects |
| Rust | one struct holding the properties of every part, required where any part requires them; each referenced part keeps its own struct |
| Tests | [`SchemaMappingTest#allOfObjectsIsOneStructWithEveryPartsProperties`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L194), [`StringFirstUnionsTest#anAllOfPropertyReferencingAStringFirstUnionIsTheUnion`](../../src/test/java/be/timvw/openapi/rust/StringFirstUnionsTest.java#L70) |

### `schema.allOf.union`

| | |
| --- | --- |
| OpenAPI | a schema that is `allOf` of exactly one union |
| Rust | an enum with that union's variants, and its tag |
| Options | always |
| Tests | [`ModelUnionsTest#aModelThatIsAllOfOneUnionHasThatUnionsVariants`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L95), [`UnionDispatchTest#aModelThatIsAllOfOneUnionHasItsTag`](../../src/test/java/be/timvw/openapi/rust/UnionDispatchTest.java#L101) |

## `oneOf`

### `schema.oneOf`

| | |
| --- | --- |
| OpenAPI | `oneOf` with two or more branches that are not `type: null` |
| Rust | a `pub enum` with `#[serde(untagged)]` and a variant per branch, holding the branch's type (`User(models::UserMessage)`, `Text(String)`, `ArrayOfStrings(Vec<String>)`). No `Default`. A variant is boxed only where it closes a cycle (`Branch(Box<models::Branch>)`) |
| Wire | decodes to the first variant, in [variant order](#schemaoneofvariant-order), that accepts the value; with a tag, by the tag ([`discriminator.propertyName`](discriminator.md#discriminatorpropertyname), [`schema.oneOf.implicit-tag`](#schemaoneofimplicit-tag)). Encodes as the variant's value, without a wrapper. As a path, query, header or cookie parameter or a form field (and per item of an array of them), it is its `Display`: a scalar variant as its plain text (`status=active`, `5`, `true`, a uuid hyphenated, a date `YYYY-MM-DD`, a date-time RFC 3339), any other as its JSON (`%5B%22active%22%5D`). A uuid or chrono variant makes the crate depend on `uuid` or `chrono` |
| Options | always (stock renders a discriminated `oneOf` as `#[serde(tag = "...")]`, which does not decode when the branch structs keep the tag field, and writes the tag twice) |
| Tests | [`ModelUnionsTest#aDiscriminatedOneOfIsUntaggedSoTheTagIsWrittenOnce`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L75), [`#nothingIsEmptyAndNoUnionHasADefault`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L201), [`#aCycleThroughAUnionIsBoxedOnlyThere`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L244), [`UnionParamsTest#aUnionIsSentAsItsDisplayNotItsJson`](../../src/test/java/be/timvw/openapi/rust/UnionParamsTest.java#L61), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/UnionParamsTest.java#L110), [`UnionParamsTest#uuidAndDateVariantsPrintTheirText`](../../src/test/java/be/timvw/openapi/rust/UnionParamsTest.java#L71), [`#aUnionVariantMakesTheCrateDependOnItsCrate`](../../src/test/java/be/timvw/openapi/rust/UnionParamsTest.java#L80) |

### `schema.oneOf.variant-names`

| | |
| --- | --- |
| OpenAPI | the branches of a `oneOf` or `anyOf` |
| Rust | each variant is named, in order of preference, after the discriminator `mapping` key, the referenced schema, the branch's `title`, then its type (`Text`, `Integer`, `Number`, `ArrayOfStrings`, `ArrayOfIntegerArrays`). Arrays that share a title are named after their items |
| Options | always; an inline branch's model carries the union's name (`models::FormatPcm`), which the variant does not repeat (`Pcm`). A variant never carries `--model-name-prefix` or `--model-name-suffix` (`Circle(models::ApiCircle)`, `Text(String)`) |
| Tests | [`ModelUnionsTest#thePlanNamesEveryVariantWithItsBranch`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L251), [`#arraysSharingATitleAreNamedAfterTheirItems`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L169), [`#anInlineBranchKeepsItsOwnModel`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L125), [`ModelNamesTest#unionVariantsAndHoistedEnumsGetThePrefixAndSuffixOnce`](../../src/test/java/be/timvw/openapi/rust/ModelNamesTest.java#L120) |

### `schema.oneOf.variant-order`

| | |
| --- | --- |
| OpenAPI | the branches of a `oneOf` or `anyOf` |
| Rust | the variants are reordered so that, of two variants that accept the same JSON kind, the narrower comes first. Variants this says nothing about keep the order of the spec |
| Wire | an untagged enum decodes to the first variant that accepts the value, so the order decides what decodes |
| Options | always |
| Tests | [`ModelUnionsTest#anIntegerGoesBeforeAFloatAndAnEnumBeforeAString`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L145), [`#aStructGoesBeforeOneThatWouldDecodeItsValues`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L161), [`#aUnionWithARicherArmGoesBeforeAStructItCouldLoseFieldsTo`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L152), [`#whatAcceptsEverythingComesLast`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L175) |

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
| Rust | the `null` branch is no variant: the union is nullable. With one other branch, there is no union: the schema is that branch (an object next to `null` is a struct). Where it is used, the nullable union follows [`schema.nullable`](../type-mapping.md#objects): an optional property is `Option<Option<U>>`. A required query, header or cookie parameter of a nullable union is an `Option` too, and `None` sends nothing (no header either: one used to be sent empty); a path parameter is the union, never an `Option`; an inline one of a parameter or form field follows [`parameter.schema.null-branch`](parameter.md#parameterschemanull-branch) |
| Wire | `null` decodes to `Some(None)`, an absent property to `None` |
| Options | always |
| Tests | [`ModelUnionsTest#anObjectNextToNullIsAStruct`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L116), [`#aNullableReferenceDoesNotRewriteItsTarget`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L103), [`ExtraRulesTest#nullableUnionFieldsCanHoldNull`](../../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java#L137), [`UnionParamsTest#aRequiredNullableUnionParameterIsAnOption`](../../src/test/java/be/timvw/openapi/rust/UnionParamsTest.java#L87), [`StringFirstUnionsTest#aRequiredNullableStringFirstUnionIsAnOptionOfTheUnion`](../../src/test/java/be/timvw/openapi/rust/StringFirstUnionsTest.java#L78) |

### `schema.oneOf.enum-branch`

| | |
| --- | --- |
| OpenAPI | a branch that is an inline string `enum` |
| Rust | an enum of its own, `<Union><Variant>Enum` (`LogBatchSizeTextVariantEnum`), re-exported from `models`; the variant holds it (`TextVariant(models::LogBatchSizeTextVariantEnum)`) |
| Options | always. Where a response can hold it, it gets an `Unknown` variant, unless it tells two variants apart |
| Tests | [`ModelUnionsTest#theIntegerBranchSurvivesAnEnumNextToIt`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L133), [`OpenEnumsTest#whatTellsVariantsApartIsGuarded`](../../src/test/java/be/timvw/openapi/rust/OpenEnumsTest.java#L116) |

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
| Options | always |
| Tests | [`ModelUnionsTest#helpersKeepTheirNames`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L234), [`#aStringVariantGoesOutAsPlainText`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L214), [`#aJsonVariantPrintsItsJson`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L227) |

### `schema.oneOf.implicit-tag`

| | |
| --- | --- |
| OpenAPI | a `oneOf` or `anyOf` without a `discriminator`, where every branch requires one property with fixed string values that tell two variants apart |
| Rust | the same enum as [`schema.oneOf`](#schemaoneof), decoded by that property as if it were the discriminator ([`discriminator.propertyName`](discriminator.md#discriminatorpropertyname)). A branch that is a union has the property when all its arms do. When several properties qualify, the one with the fewest values on two variants wins, then `type`, then the first by name |
| Wire | as [`discriminator.propertyName`](discriminator.md#discriminatorpropertyname) |
| Options | always. A union where only some branches require the property, with a branch that is no object, or where the property has the same values everywhere, stays untagged and decodes in variant order |
| Tests | [`UnionDispatchTest#aRequiredPropertyWithFixedValuesOnEveryBranchIsATag`](../../src/test/java/be/timvw/openapi/rust/UnionDispatchTest.java#L107), [`#aBranchThatIsAUnionCountsThroughItsArms`](../../src/test/java/be/timvw/openapi/rust/UnionDispatchTest.java#L113), [`#anInlineBranchOfABranchCountsToo`](../../src/test/java/be/timvw/openapi/rust/UnionDispatchTest.java#L120), [`#thePropertyThatSharesFewestValuesIsTheTag`](../../src/test/java/be/timvw/openapi/rust/UnionDispatchTest.java#L128), [`#whatHasNoTagIsNotDispatched`](../../src/test/java/be/timvw/openapi/rust/UnionDispatchTest.java#L133) |

## `anyOf`

### `schema.anyOf`

| | |
| --- | --- |
| OpenAPI | `anyOf` with two or more branches that are not `type: null` |
| Rust | as [`schema.oneOf`](#schemaoneof), and every `schema.oneOf.*` rule applies the same way. A value that matches several branches decodes to the first in variant order |
| Options | always (stock renders a discriminated `anyOf` as an empty type) |
| Tests | [`ModelUnionsTest#aDiscriminatedAnyOfHasItsVariants`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L86) |

### `schema.anyOf.untyped`

| | |
| --- | --- |
| OpenAPI | an `anyOf` with no typed branch (`anyOf: [{}, {}]`; `anyOf: [{}, {type: null}]` is [`schema.type.null`](#schematypenull)) |
| Rust | `serde_json::Value` |
| Options | always |
| Tests | [`ModelUnionsTest#aVariantHoldingAnUntypedAnyOfHoldsJsonValues`](../../src/test/java/be/timvw/openapi/rust/ModelUnionsTest.java#L266) |
