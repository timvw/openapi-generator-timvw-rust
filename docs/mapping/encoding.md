# Encoding Object

Rules for the [Encoding Object](https://spec.openapis.org/oas/v3.0.3#encoding-object), part of a
[Media Type Object](https://spec.openapis.org/oas/v3.0.3#media-type-object). Which media type a function sends or
decodes is [`requestBody.content`](request-body.md#requestbodycontent) and
[`response.content`](response.md#responsecontent).

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OperationMappingTest` renders
`src/test/resources/operation_mapping.yaml` without options.

### `encoding.contentType`

| | |
| --- | --- |
| OpenAPI | `encoding: {<property>: {contentType: ..}}` of a `multipart/form-data` body |
| Rust | ignored |
| Options | `multipartHelper` (openai-client-base) applies it to scalar and object parts; a file part keeps the file's own type |
| Tests | [`MultipartTest#helperRulesPerParameterShape`](../../src/test/java/be/timvw/openapi/rust/MultipartTest.java#L73), [`MultipartFileUnionsTest#anEncodingContentTypeIsForTheOtherVariantsNotTheFile`](../../src/test/java/be/timvw/openapi/rust/MultipartFileUnionsTest.java#L105) |
