# Request Body Object

Rules for the [Request Body Object](https://spec.openapis.org/oas/v3.0.3#request-body-object). The
[baseline](README.md#baseline) options apply. How each media type's body is rendered (JSON, form, multipart, bytes) is
still in [type-mapping.md](../type-mapping.md#request-bodies).

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`.

### `requestBody.content`

| | |
| --- | --- |
| OpenAPI | a request body whose `content` lists one or more media types |
| Rust | the operation's function sends the body as the first media type the spec lists |
| Options | `contentTypeMethods` (openai-client-base) adds a function per other media type, named after the operation with a suffix: `_json` (`application/json`, `*/*+json`), `_multipart` (`multipart/form-data`), `_form` (`application/x-www-form-urlencoded`), `_text` (`text/plain`), `_bytes` (`application/octet-stream`), and otherwise the subtype (`application/sdp`: `_sdp`, a string body sent as is with that media type). Combined with a response suffix ([`response.content`](response.md#responsecontent)) the request suffix comes first (`create_image_edit_json_stream`). Such a function returns the operation's error enum and its documentation says what it sends and decodes |
| Tests | `EventStreamsTest#everyMediaTypeHasAMethodNamedWithItsSuffix`, `#theOperationsOwnMethodKeepsWhatItDid`, `#theMethodsOfAnOperationShareItsErrorEnum`, `#requestBodiesGoOutAsTheirMediaType`, `#suffixes`, `#withoutTheOptionsNothingChanges` |
