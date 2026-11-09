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
| Rust | `&str` or the scalar, URL-encoded into the path (`crate::apis::urlencode`) |
| Wire | a `date-time` is RFC 3339 (`to_rfc3339()`), URL-encoded (`2026-01-02T03%3A04%3A05%2B01%3A00`); an array of them encodes each item and joins them with `,`; a `date` is `YYYY-MM-DD` ([`schema.format.date-time`](schema.md#schemaformatdate-time)) |
| Tests | [`OperationMappingTest#aPathParameterIsUrlEncodedIntoThePath`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L88), [`DateParamsTest#aDateTimeIsSentInRfc3339`](../../src/test/java/be/timvw/openapi/rust/DateParamsTest.java#L70), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/DateParamsTest.java#L130) |

### `parameter.in.query`

| | |
| --- | --- |
| OpenAPI | `in: query` |
| Rust | added with `.query(..)`, as the value's text. An array is repeated (`?tag=a&tag=b`) with `style: form, explode: true` (the default), and joined with `,` otherwise |
| Wire | a `date-time` is RFC 3339 (`to_rfc3339()`), a `date` `YYYY-MM-DD`, each item of an array alike; `.query` percent-encodes the value (`from=2026-01-01T00%3A00%3A00%2B00%3A00`) ([`schema.format.date-time`](schema.md#schemaformatdate-time)) |
| Options | `resetQueryParameterStyle=true` (openai-client-base) gives every query parameter the default style, so arrays are always repeated |
| Library | `reqwest::RequestBuilder::query` |
| Tests | [`OperationMappingTest#queryParametersFollowTheirStyle`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L94), [`SpecRulesTest#objectQueryParametersBecomeStringsUnlessAllowed`](../../src/test/java/be/timvw/openapi/rust/SpecRulesTest.java#L170), [`DateParamsTest#aDateTimeIsSentInRfc3339`](../../src/test/java/be/timvw/openapi/rust/DateParamsTest.java#L70), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/DateParamsTest.java#L130) |

### `parameter.style.deep-object`

| | |
| --- | --- |
| OpenAPI | a query parameter whose schema is an object (inline, or a reference to an object schema) |
| Rust | `&str`: the caller passes the text to send |
| Options | `deepObjectQueryParameters=<path>?<name>` sends listed ones in `deepObject` style, as `HashMap<String, String>` |
| Tests | [`OperationMappingTest#queryParametersFollowTheirStyle`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L94), [`SpecRulesTest#objectQueryParametersBecomeStringsUnlessAllowed`](../../src/test/java/be/timvw/openapi/rust/SpecRulesTest.java#L170), [`GenerationTest#deepObjectParsingDoesNotPanic`](../../src/test/java/be/timvw/openapi/rust/GenerationTest.java#L82) |

### `parameter.in.header`

| | |
| --- | --- |
| OpenAPI | `in: header` |
| Rust | `.header(<name>, value)`; an array is joined with `,` |
| Wire | a `date-time` is RFC 3339 (`to_rfc3339()`, `2026-01-02T03:04:05+01:00`), a `date` `YYYY-MM-DD`, each item of an array alike ([`schema.format.date-time`](schema.md#schemaformatdate-time)) |
| Options | always: a struct is serialized with `serde_json::to_string` ([`schema.properties.display`](schema.md#schemapropertiesdisplay)) |
| Tests | [`OperationMappingTest#headersAreSentAndArraysJoined`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L103), [`NoStructDisplayTest#headers`](../../src/test/java/be/timvw/openapi/rust/NoStructDisplayTest.java#L97), [`ExtraRulesTest#arrayHeadersOfEnumsJoinTheirDisplay`](../../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java#L87), [`DateParamsTest#aDateTimeIsSentInRfc3339`](../../src/test/java/be/timvw/openapi/rust/DateParamsTest.java#L70), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/DateParamsTest.java#L130) |

### `parameter.in.cookie`

| | |
| --- | --- |
| OpenAPI | `in: cookie` |
| Rust | an argument like any other, which the function does not use |
| Gap | the cookie is not sent |
| Tests | [`OperationMappingTest#aCookieParameterIsNotSent`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L110) |

### `parameter.schema.enum`

| | |
| --- | --- |
| OpenAPI | a parameter whose schema is an inline `enum` (or an array of one) |
| Rust | `&str` (`Vec<String>` for an array): the value is not checked |
| Options | `typedEnumParameters` (langfuse-client-base) moves the enum to a component `<OperationId><Param>` (`models::ListModelsOrder`), which the function takes and sends as its value; arrays use `_item`. A name that is taken fails generation |
| Tests | [`OperationMappingTest#anInlineEnumParameterIsAString`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L118), [`ExtraRulesTest#enumParametersTakeTheEnum`](../../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java#L78), [`#enumParametersStayStringsWithoutTheOption`](../../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java#L107), [`#aTakenNameFailsGeneration`](../../src/test/java/be/timvw/openapi/rust/ExtraRulesTest.java#L114) |
