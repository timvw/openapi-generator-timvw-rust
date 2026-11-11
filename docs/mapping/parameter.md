# Parameter Object

Rules for the [Parameter Object](https://spec.openapis.org/oas/v3.0.3#parameter-object). A parameter's argument follows
[`operation.parameters`](operation.md#operationparameters).

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OperationMappingTest` renders
`src/test/resources/operation_mapping.yaml` without options.

### `parameter.required`

| | |
| --- | --- |
| OpenAPI | `required` |
| Rust | required: the type; optional: `Option<..>`, and a `None` is not sent. A string is `&str`, an array `Vec<T>`, an integer follows [`schema.type.integer`](schema.md#schematypeinteger) |
| Tests | [`OperationMappingTest#parametersAreArgumentsInSpecOrder`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L75) |

### `parameter.in.path`

| | |
| --- | --- |
| OpenAPI | `in: path` |
| Rust | `&str` or the scalar, URL-encoded into the path (`crate::apis::urlencode`, a space as `%20`). An array (`style: simple`) URL-encodes each item's text, whatever its type (`Vec<String>`, `Vec<i64>`, ...), and joins them with `,` (`a%20b,c%2Fd`). An enum is URL-encoded too (`c%2Fd`), and a struct or map is its JSON, URL-encoded. A path parameter is never an `Option`, a nullable one (`type: [T, null]`, `nullable: true`, a `null` branch) included: a path always has a value (it was `Option<..>` and panicked on `None`) |
| Wire | a `date-time` is RFC 3339 (`to_rfc3339()`), URL-encoded (`2026-01-02T03%3A04%3A05%2B01%3A00`); an array of them encodes each item and joins them with `,`; a `date` is `YYYY-MM-DD` ([`schema.format.date-time`](schema.md#schemaformatdate-time)) |
| Tests | [`OperationMappingTest#aPathParameterIsUrlEncodedIntoThePath`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L88), [`DateParamsTest#aDateTimeIsSentInRfc3339`](../../src/test/java/be/timvw/openapi/rust/DateParamsTest.java#L71), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/DateParamsTest.java#L137), [`ParamSerializationTest#pathArraysEncodeEachItem`](../../src/test/java/be/timvw/openapi/rust/ParamSerializationTest.java#L58), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/ParamSerializationTest.java#L129), [`NullBranchParamsTest#aNullablePathParameterIsItsType`](../../src/test/java/be/timvw/openapi/rust/NullBranchParamsTest.java#L147) |

### `parameter.in.query`

| | |
| --- | --- |
| OpenAPI | `in: query` |
| Rust | added with `.query(..)`, as the value's text. An array is repeated (`?tag=a&tag=b`) with `explode: true`, for `style: form` (the default) and, as OpenAPI 3.2 spells out, for `pipeDelimited` and `spaceDelimited` too; otherwise its items are joined with the style's delimiter: `,` for `form`, `\|` for `pipeDelimited`, a space for `spaceDelimited` (then form-encoded: `x%7Cy`, `x+y`). An item without a text form of its own (an array of arrays, of maps or of structs) is its JSON (`matrix=%5B1%2C2%5D&matrix=%5B3%5D`) |
| Wire | a `date-time` is RFC 3339 (`to_rfc3339()`), a `date` `YYYY-MM-DD`, each item of an array alike; `.query` percent-encodes the value (`from=2026-01-01T00%3A00%3A00%2B00%3A00`) ([`schema.format.date-time`](schema.md#schemaformatdate-time)) |
| Options | `resetQueryParameterStyle=true` (openai-client-base) gives every query parameter the default style, so arrays are always repeated |
| Library | `reqwest::RequestBuilder::query` |
| Tests | [`OperationMappingTest#queryParametersFollowTheirStyle`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L94), [`SpecRulesTest#objectQueryParametersBecomeStringsUnlessAllowed`](../../src/test/java/be/timvw/openapi/rust/SpecRulesTest.java#L170), [`DateParamsTest#aDateTimeIsSentInRfc3339`](../../src/test/java/be/timvw/openapi/rust/DateParamsTest.java#L71), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/DateParamsTest.java#L137), [`ParamSerializationTest#delimitedAndNestedQueryArrays`](../../src/test/java/be/timvw/openapi/rust/ParamSerializationTest.java#L82) |

### `parameter.style.deep-object`

| | |
| --- | --- |
| OpenAPI | a query parameter whose schema is an object (inline, or a reference to an object schema) |
| Rust | `&str`: the caller passes the text to send (as `name=<text>`). A parameter the spec declares `style: deepObject` keeps its type (the model, or `HashMap<String, String>` for `additionalProperties`) and is sent in `deepObject` style, `name[field]=value` for each field that is set (`crate::apis::parse_deep_object`), required or optional. `deepObject` on anything that is not an object is treated as `form` |
| Options | `deepObjectQueryParameters=<path>?<name>` sends the listed ones in `deepObject` style too, whatever their declared style |
| Tests | [`OperationMappingTest#queryParametersFollowTheirStyle`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L94), [`SpecRulesTest#objectQueryParametersBecomeStringsUnlessAllowed`](../../src/test/java/be/timvw/openapi/rust/SpecRulesTest.java#L170), [`GenerationTest#deepObjectParsingDoesNotPanic`](../../src/test/java/be/timvw/openapi/rust/GenerationTest.java#L83), [`ParamSerializationTest#aDeclaredDeepObjectModelIsSentAsDeepObject`](../../src/test/java/be/timvw/openapi/rust/ParamSerializationTest.java#L75), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/ParamSerializationTest.java#L129) |

### `parameter.in.header`

| | |
| --- | --- |
| OpenAPI | `in: header` |
| Rust | `.header(<name>, value)`; an array is joined with `,`: strings as they are, other items (`i64`, uuids, enums, ...) by their text, structs by their JSON; a map (`additionalProperties`) is sent as its JSON, as a struct is |
| Wire | a `date-time` is RFC 3339 (`to_rfc3339()`, `2026-01-02T03:04:05+01:00`), a `date` `YYYY-MM-DD`, each item of an array alike ([`schema.format.date-time`](schema.md#schemaformatdate-time)) |
| Options | always: a struct is serialized with `serde_json::to_string` ([`schema.properties.display`](schema.md#schemapropertiesdisplay)) |
| Tests | [`OperationMappingTest#headersAreSentAndArraysJoined`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L103), [`NoStructDisplayTest#headers`](../../src/test/java/be/timvw/openapi/rust/NoStructDisplayTest.java#L99), [`ExtraRulesTest#arrayHeadersOfEnumsJoinTheirDisplay`](../../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java#L87), [`DateParamsTest#aDateTimeIsSentInRfc3339`](../../src/test/java/be/timvw/openapi/rust/DateParamsTest.java#L71), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/DateParamsTest.java#L137), [`CookieParamsTest#aMapIsSentAsItsJson`](../../src/test/java/be/timvw/openapi/rust/CookieParamsTest.java#L90), [`ParamSerializationTest#aHeaderArrayOfIntegersJoinsTheirText`](../../src/test/java/be/timvw/openapi/rust/ParamSerializationTest.java#L69) |

### `parameter.in.cookie`

| | |
| --- | --- |
| OpenAPI | `in: cookie` |
| Rust | an argument like any other, of its format's type (`uuid::Uuid`, `chrono::DateTime<..>`, ...); every cookie parameter that is set goes into one `Cookie` header, `name=value` pairs joined with `; `, in the spec's order, then an api key sent as a cookie ([`securityScheme.type.api-key`](security-scheme.md#securityschemetypeapi-key)). An unset optional one is left out; with none set, there is no `Cookie` header. The generated header replaces a client default `Cookie` header on operations that send cookies; a middleware that adds cookies must merge into the existing header rather than append a second one |
| Wire | the value is its text (a `date-time` RFC 3339, [`schema.format.date-time`](schema.md#schemaformatdate-time); a struct or a map its JSON), with every byte that is not an RFC 6265 cookie-octet, and `%`, percent-encoded (`crate::apis::cookie_encode`: space, `"`, `,`, `;`, `\`, controls and non-ASCII bytes become `%XX`). An array (`style: form`): with `explode: true` (the default) a pair per item (`tag=a; tag=b`), with `explode: false` one pair with the encoded items joined by `,` (`tag=a,b`) |
| Library | `reqwest::header::COOKIE` |
| Tests | [`OperationMappingTest#aCookieParameterIsSentInTheCookieHeader`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L110), [`CookieParamsTest#theArgumentsKeepTheirTypes`](../../src/test/java/be/timvw/openapi/rust/CookieParamsTest.java#L63), [`#eachCookieIsAPairOfOneHeader`](../../src/test/java/be/timvw/openapi/rust/CookieParamsTest.java#L70), [`#aMapIsSentAsItsJson`](../../src/test/java/be/timvw/openapi/rust/CookieParamsTest.java#L90), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/CookieParamsTest.java#L114) |

### `parameter.schema.null-branch`

| | |
| --- | --- |
| OpenAPI | a parameter whose schema is an inline `oneOf` or `anyOf` of one schema and `type: null` (OpenAPI 3.1), such as `oneOf: [{$ref: '#/components/schemas/Status'}, {type: 'null'}]`; the one schema may wrap the reference in one-element `oneOf`/`anyOf`/`allOf`s (`anyOf: [{description, oneOf: [$ref]}, {type: 'null'}]`). In paths, webhooks, callbacks and `components.parameters` |
| Rust | the parameter takes that schema: the referenced type (`models::Status`, `uuid::Uuid`, `chrono::DateTime<..>`, `i64`, a union, `&str` for a string or a query object), as the plain `$ref` would. Its description, if it has none, is the union's or the branch's. It is nullable like a `type: [T, null]` parameter: optional, `Option<T>`; required in the query, a header or a cookie, `Option<T>` as well, because the spec allows `null` there and a parameter's `null` is "not sent": a server that really requires a value but declares the parameter nullable should fix its contract, and the generator follows the contract as written; in the path, `T` ([`parameter.in.path`](#parameterinpath)). A form field of that shape (urlencoded or multipart) likewise takes the schema's type and is nullable like a `type: [T, null]` field ([`requestBody.content.form`](request-body.md#requestbodycontentform)); the component it belongs to keeps the field as [`schema.oneOf.null-branch`](schema.md#schemaoneofnull-branch) says. A plain (not nullable) `allOf: [$ref]` form field takes the referenced type too: a `uuid` one was `models::uuid::Uuid`, which did not compile, and a multipart `date-time` one is sent in RFC 3339 |
| Wire | as the plain reference: the enum's value, a uuid hyphenated, a `date-time` RFC 3339, a union its text, in the path, the query, a header, a cookie and a form. A parameter has no `null`: `None` sends nothing, for a required query, header or cookie parameter too (no header is sent; a required nullable header left `None` was sent empty). A required urlencoded or multipart field left `None` is sent empty |
| Options | always; before, such a parameter was `Option<&str>` (a path one too) and such a form field of a `uuid` or `date-time` did not compile (`models::uuid::Uuid`) |
| Tests | [`NullBranchParamsTest#aParameterTakesTheReferencedType`](../../src/test/java/be/timvw/openapi/rust/NullBranchParamsTest.java#L64), [`#aRequiredOneIsAnOptionExceptInThePath`](../../src/test/java/be/timvw/openapi/rust/NullBranchParamsTest.java#L84), [`#itIsSentAsThePlainReferenceIs`](../../src/test/java/be/timvw/openapi/rust/NullBranchParamsTest.java#L95), [`#aFormFieldTakesTheReferencedType`](../../src/test/java/be/timvw/openapi/rust/NullBranchParamsTest.java#L106), [`#theModelKeepsItsNullableField`](../../src/test/java/be/timvw/openapi/rust/NullBranchParamsTest.java#L163), [`#theNonNullBranch`](../../src/test/java/be/timvw/openapi/rust/NullBranchParamsTest.java#L169), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/NullBranchParamsTest.java#L198), [`#aWrappedReferenceIsUnwrapped`](../../src/test/java/be/timvw/openapi/rust/NullBranchParamsTest.java#L135), [`#aNullablePathParameterIsItsType`](../../src/test/java/be/timvw/openapi/rust/NullBranchParamsTest.java#L147), [`#aRequiredNullableHeaderLeftNoneIsNotSent`](../../src/test/java/be/timvw/openapi/rust/NullBranchParamsTest.java#L155), [`#aPlainAllOfFormFieldTakesTheReferencedType`](../../src/test/java/be/timvw/openapi/rust/NullBranchParamsTest.java#L123) |

### `parameter.schema.null`

| | |
| --- | --- |
| OpenAPI | a query, path, header or cookie parameter whose schema can only be `null` (`type: "null"`, `enum: [null]`, a union of only `null` branches, a list or map (`deepObject`) of these, through `$ref`s and single-entry `allOf`s), in an operation or a `$ref` to a component parameter an operation uses |
| Rust | none: generation fails, naming the parameter and the operation, since a parameter has no `null` to send and could never be sent. A component parameter no operation uses is not checked. A form field of only `null` (also in a form reached through `allOf: [$ref]`) fails the same way ([`schema.type.null`](schema.md#schematypenull)). A parameter of any value or `null` (`oneOf: [{}, {type: "null"}]`) is any value, as `{}` is: a string in the query, path or a header |
| Options | always; before, it was `models::Null`, which did not compile |
| Tests | [`NullShapesTest#aParameterOfOnlyNullFailsGeneration`](../../src/test/java/be/timvw/openapi/rust/NullShapesTest.java#L120), [`#everyParameterOfOnlyNullFailsGeneration`](../../src/test/java/be/timvw/openapi/rust/NullShapesTest.java#L146), [`#anUnusedComponentParameterOfOnlyNullDoesNotFail`](../../src/test/java/be/timvw/openapi/rust/NullShapesTest.java#L199), [`#nullAndAnyOrNullAreJsonValuesEverywhere`](../../src/test/java/be/timvw/openapi/rust/NullShapesTest.java#L64) |

### `parameter.schema.enum`

| | |
| --- | --- |
| OpenAPI | a parameter whose schema is an inline `enum` (or an array of one) |
| Rust | `&str` (`Vec<String>` for an array): the value is not checked |
| Options | `typedEnumParameters` (langfuse-client-base) moves the enum to a component `<OperationId><Param>` (`models::ListModelsOrder`), which the function takes and sends as its value; arrays use `_item`. A name that is taken fails generation |
| Tests | [`OperationMappingTest#anInlineEnumParameterIsAString`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L119), [`ExtraRulesTest#enumParametersTakeTheEnum`](../../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java#L78), [`#enumParametersStayStringsWithoutTheOption`](../../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java#L108), [`#aTakenNameFailsGeneration`](../../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java#L115) |
