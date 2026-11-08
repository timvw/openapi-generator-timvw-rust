# Parameter Object

Rules for the [Parameter Object](https://spec.openapis.org/oas/v3.0.3#parameter-object). The
[baseline](README.md#baseline) options apply. A parameter's argument follows
[`operation.parameters`](operation.md#operationparameters).

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OperationMappingTest` renders
`src/test/resources/operation_mapping.yaml` with the baseline options.

### `parameter.required`

| | |
| --- | --- |
| OpenAPI | `required` |
| Rust | required: the type; optional: `Option<..>`, and a `None` is not sent. A string is `&str`, an array `Vec<T>`, an integer follows [`schema.type.integer`](schema.md#schematypeinteger) |
| Tests | `OperationMappingTest#parametersAreArgumentsInSpecOrder` |

### `parameter.in.path`

| | |
| --- | --- |
| OpenAPI | `in: path` |
| Rust | `&str` or the scalar, URL-encoded into the path (`crate::apis::urlencode`) |
| Tests | `OperationMappingTest#aPathParameterIsUrlEncodedIntoThePath` |

### `parameter.in.query`

| | |
| --- | --- |
| OpenAPI | `in: query` |
| Rust | added with `.query(..)`, as the value's text. An array is repeated (`?tag=a&tag=b`) with `style: form, explode: true` (the default), and joined with `,` otherwise |
| Options | `resetQueryParameterStyle=true` (openai-client-base) gives every query parameter the default style, so arrays are always repeated |
| Library | `reqwest::RequestBuilder::query` |
| Tests | `OperationMappingTest#queryParametersFollowTheirStyle`, `SpecRulesTest#objectQueryParametersBecomeStringsUnlessAllowed` |

### `parameter.style.deep-object`

| | |
| --- | --- |
| OpenAPI | a query parameter whose schema is an object (inline, or a reference to an object schema) |
| Rust | `&str`: the caller passes the text to send |
| Options | `deepObjectQueryParameters=<path>?<name>` sends listed ones in `deepObject` style, as `HashMap<String, String>` |
| Tests | `OperationMappingTest#queryParametersFollowTheirStyle`, `SpecRulesTest#objectQueryParametersBecomeStringsUnlessAllowed`, `GenerationTest#deepObjectParsingDoesNotPanic` |

### `parameter.in.header`

| | |
| --- | --- |
| OpenAPI | `in: header` |
| Rust | `.header(<name>, value)`; an array is joined with `,` |
| Options | `noStructDisplay` (baseline) serializes a struct with `serde_json::to_string` ([`schema.properties.display`](schema.md#schemapropertiesdisplay)) |
| Tests | `OperationMappingTest#headersAreSentAndArraysJoined`, `NoStructDisplayTest#headers`, `ExtraRulesTest#arrayHeadersOfEnumsJoinTheirDisplay` |

### `parameter.in.cookie`

| | |
| --- | --- |
| OpenAPI | `in: cookie` |
| Rust | an argument like any other, which the function does not use |
| Gap | the cookie is not sent |
| Tests | `OperationMappingTest#aCookieParameterIsNotSent` |

### `parameter.schema.enum`

| | |
| --- | --- |
| OpenAPI | a parameter whose schema is an inline `enum` (or an array of one) |
| Rust | `&str` (`Vec<String>` for an array): the value is not checked |
| Options | `typedEnumParameters` (langfuse-client-base) moves the enum to a component `<OperationId><Param>` (`models::ListModelsOrder`), which the function takes and sends as its value; arrays use `_item`. A name that is taken fails generation |
| Tests | `OperationMappingTest#anInlineEnumParameterIsAString`, `ExtraRulesTest#enumParametersTakeTheEnum`, `#enumParametersStayStringsWithoutTheOption`, `#aTakenNameFailsGeneration` |
