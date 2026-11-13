# Request Body Object

Rules for the [Request Body Object](https://spec.openapis.org/oas/v3.0.3#request-body-object). The body's argument comes
after the parameters ([`operation.parameters`](operation.md#operationparameters)).

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OperationMappingTest` renders
`src/test/resources/operation_mapping.yaml` without options.

### `requestBody.content`

| | |
| --- | --- |
| OpenAPI | a request body whose `content` lists one or more media types |
| Rust | the operation's function sends the body as the first media type the spec lists |
| Options | `contentTypeMethods` (openai-client-base) adds a function per other media type, named after the operation with a suffix: `_json` (`application/json`, `*/*+json`), `_multipart` (`multipart/form-data`), `_form` (`application/x-www-form-urlencoded`), `_text` (`text/plain`), `_bytes` (`application/octet-stream`), and otherwise the subtype (`application/sdp`: `_sdp`, a string body sent as is with that media type). Combined with a response suffix ([`response.content`](response.md#responsecontent)) the request suffix comes first (`create_image_edit_json_stream`). Such a function returns the operation's error enum and its documentation says what it sends and decodes |
| Tests | [`EventStreamsTest#everyMediaTypeHasAMethodNamedWithItsSuffix`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L110), [`#theOperationsOwnMethodKeepsWhatItDid`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L124), [`#theMethodsOfAnOperationShareItsErrorEnum`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L136), [`#requestBodiesGoOutAsTheirMediaType`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L150), [`#suffixes`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L207), [`#withoutTheOptionsNothingChanges`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L197) |

### `requestBody.content.json`

| | |
| --- | --- |
| OpenAPI | `application/json` (or `*/*+json`) |
| Rust | one argument named after the schema, borrowing the body, since the function only serializes it: a model `item: &models::Item`, a union `&models::Shape`, an array a slice (`&[models::Item]`, `&[i64]`), a map `&std::collections::HashMap<..>`, any value `&serde_json::Value`; an array of a non-model type is named after its items' Rust type (`uuid: &[uuid::Uuid]`); optional or nullable (a `oneOf` with a `null` branch), `Option<&models::Item>`. The builder takes the same reference (`.item(&item)`, `.maybe_item(Some(&item))`). A boolean, number, `date` or `date-time` stays by value (it is `Copy`: `i64`, `chrono::NaiveDate`, `chrono::DateTime<chrono::FixedOffset>`). A body whose type is `String` is `&str`: a string, a string enum (without `typedEnumParameters`) and `format: byte`. Sent with `.json(..)`. The same holds for every method of an operation (`contentTypeMethods`, `typedEventStreams`) and for webhooks |
| Wire | the body's JSON, unchanged; an absent optional body is sent as `null` |
| Options | always; stock takes the body by value (`item: models::Item`) |
| Library | `timvw_rust_runtime::RequestBuilder::json` |
| Tests | [`OperationMappingTest#aJsonBodyIsSentWithJson`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L124), [`#anOperationIsAnAsyncFunctionWithABuilder`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L68), [`RequestBodiesTest#aModelBodyIsBorrowedAndSentAsIs`](../../src/test/java/be/timvw/openapi/rust/RequestBodiesTest.java#L63), [`#anOptionalBodyIsAnOptionalReference`](../../src/test/java/be/timvw/openapi/rust/RequestBodiesTest.java#L70), [`#anArrayBodyIsASlice`](../../src/test/java/be/timvw/openapi/rust/RequestBodiesTest.java#L77), [`#aMapAndAnyValueAreBorrowed`](../../src/test/java/be/timvw/openapi/rust/RequestBodiesTest.java#L83), [`#aWebhookBodyIsBorrowedToo`](../../src/test/java/be/timvw/openapi/rust/RequestBodiesTest.java#L113), [`#anArrayOfNumbersIsASliceToo`](../../src/test/java/be/timvw/openapi/rust/RequestBodiesTest.java#L89), [`#aUnionIsBorrowedAndANullBranchMakesItOptional`](../../src/test/java/be/timvw/openapi/rust/RequestBodiesTest.java#L95), [`#datesStayByValueAndStringTypedBodiesAreStr`](../../src/test/java/be/timvw/openapi/rust/RequestBodiesTest.java#L102), [`#numbersStringsAndFilesKeepTheirType`](../../src/test/java/be/timvw/openapi/rust/RequestBodiesTest.java#L120), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/RequestBodiesTest.java#L129), [`EventStreamsTest#requestBodiesGoOutAsTheirMediaType`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L150), [`ParamSerializationTest#anArrayOfUuidsBodyIsNamedAfterThem`](../../src/test/java/be/timvw/openapi/rust/ParamSerializationTest.java#L113) |

### `requestBody.content.form`

| | |
| --- | --- |
| OpenAPI | `application/x-www-form-urlencoded` with an object schema |
| Rust | an argument per property, as a [parameter](parameter.md#parameterrequired) is, sent with `.form(..)` as a list of `(name, text)` pairs in the spec's order; a struct value goes as its JSON ([`schema.properties.display`](schema.md#schemapropertiesdisplay)), and so does a field of any value (`{}`, or any value or `null`), which is `serde_json::Value` (it was `models::serde_json::Value`, which did not compile). A field of only `null` fails generation ([`schema.type.null`](schema.md#schematypenull)). An unset optional field is left out; a required nullable one left `None` is sent empty (`note=`) |
| Wire | a field is its value's text, as a query parameter's is: a `date-time` RFC 3339 (`to_rfc3339()`), a `date` `YYYY-MM-DD` ([`schema.format.date-time`](schema.md#schemaformatdate-time)). An array is a pair per item (`style: form, explode: true`, the default for a form field: `tags=a&tags=b`), or one pair joined with the encoding style's delimiter (`,`, `\|`, space) where the body's `encoding` gives it `explode: false` (the default for `pipeDelimited` and `spaceDelimited`); `.form` percent-encodes it |
| Library | `timvw_rust_runtime::RequestBuilder::form` |
| Tests | [`OperationMappingTest#aFormBodyIsAnArgumentPerPropertySentWithForm`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L129), [`NoStructDisplayTest#urlencodedForms`](../../src/test/java/be/timvw/openapi/rust/NoStructDisplayTest.java#L108), [`DateParamsTest#aDateTimeFormFieldIsSentInRfc3339`](../../src/test/java/be/timvw/openapi/rust/DateParamsTest.java#L90), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/DateParamsTest.java#L137), [`NullShapesTest#nullAndAnyOrNullAreJsonValuesEverywhere`](../../src/test/java/be/timvw/openapi/rust/NullShapesTest.java#L64), [`ParamSerializationTest#urlencodedFormArraysRepeatAndANullableFieldIsSentEmpty`](../../src/test/java/be/timvw/openapi/rust/ParamSerializationTest.java#L94) |

### `requestBody.content.multipart`

| | |
| --- | --- |
| OpenAPI | `multipart/form-data` with an object schema |
| Rust | an argument per property: a file (`format: binary`) as `timvw_rust_runtime::Upload` (`impl Into<Upload>` on the builder: a `PathBuf`, bytes), text as `&str` or the type. A model parameter's type is qualified once (stock: `models::models::X`) |
| Wire | a text part is its value's text: a `date-time` RFC 3339 (`to_rfc3339()`), a `date` `YYYY-MM-DD` ([`schema.format.date-time`](schema.md#schemaformatdate-time)) |
| Library | built as a `timvw_rust_runtime::multipart::Form`; a file is streamed with tokio as a `timvw_rust_runtime::multipart::Part::upload` named after the file and typed from its extension, the form with a `Content-Length` |
| Options | `multipartHelper` (openai-client-base) sends the parts through `src/apis/multipart_helper.rs`: an optional array sends a part per item, an object one JSON part, and an [`encoding`](encoding.md#encodingcontenttype) `contentType` is applied. A union with file variants sends a file, an array of files, or the other variant's value. `fileProperties=<Schema.property,..>` makes listed properties files. Without the helper an optional string enum part carries its wire value, not its JSON |
| Tests | [`MultipartTest#helperRulesPerParameterShape`](../../src/test/java/be/timvw/openapi/rust/MultipartTest.java#L73), [`#modelParametersAreQualifiedOnce`](../../src/test/java/be/timvw/openapi/rust/MultipartTest.java#L55), [`#filePropertiesTurnAPropertyIntoAFile`](../../src/test/java/be/timvw/openapi/rust/MultipartTest.java#L44), [`#withoutTheOptionTheStockBlockRendersAndNoHelperIsWritten`](../../src/test/java/be/timvw/openapi/rust/MultipartTest.java#L62), [`MultipartFileUnionsTest#aRequiredUnionMatchesAndItsFileGoesThroughTheHelper`](../../src/test/java/be/timvw/openapi/rust/MultipartFileUnionsTest.java#L143), [`#anArraySendsOnePartPerFileUnderTheDocumentedName`](../../src/test/java/be/timvw/openapi/rust/MultipartFileUnionsTest.java#L167), [`#filesInAShapeMultipartCannotCarryFailGeneration`](../../src/test/java/be/timvw/openapi/rust/MultipartFileUnionsTest.java#L224), [`TitleNamingTest#optionalEnumMultipartPartsUseTheWireValueNotJson`](../../src/test/java/be/timvw/openapi/rust/TitleNamingTest.java#L109), [`DateParamsTest#aDateTimeFormFieldIsSentInRfc3339`](../../src/test/java/be/timvw/openapi/rust/DateParamsTest.java#L90), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/DateParamsTest.java#L137) |

### `requestBody.content.octet-stream`

| | |
| --- | --- |
| OpenAPI | `application/octet-stream` with `format: binary` |
| Rust | `timvw_rust_runtime::Upload`, what to send ([`schema.format.binary`](schema.md#schemaformatbinary)) |
| Library | streamed as a `timvw_rust_runtime::Body`, with a `Content-Length` when its length is known |
| Tests | [`SchemaMappingTest#binaryIsAFileToSendOrAResponseToRead`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L107) |
