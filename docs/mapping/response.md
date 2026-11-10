# Response Object

Rules for the [Response Object](https://spec.openapis.org/oas/v3.0.3#response-object). These rules are about a success
(`2XX`) response; error responses are the [Responses Object](responses.md).

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OperationMappingTest` renders
`src/test/resources/operation_mapping.yaml` without options.

### `response.content.json`

| | |
| --- | --- |
| OpenAPI | a success response with `application/json` content |
| Rust | the function returns `Result<T, Error<..>>`, `T` the schema's type, decoded from the body. A body of another content type is `Error::Serde` |
| Options | `responseFunctions` adds `<operation>_response`, returning the body undecoded with the status and headers ([`response.content.response-function`](#responsecontentresponse-function)) |
| Tests | [`OperationMappingTest#aJsonResponseIsDecodedAndATextResponseIsAString`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L137) |

### `response.content.text`

| | |
| --- | --- |
| OpenAPI | a success response with `text/plain` content |
| Rust | `String`, the body as it came |
| Options | `responseFunctions`: `<operation>_response` returns `ApiResponse<String>`; `text()` is the body ([`response.content.response-function`](#responsecontentresponse-function)) |
| Tests | [`OperationMappingTest#aJsonResponseIsDecodedAndATextResponseIsAString`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L137) |

### `response.content.binary`

| | |
| --- | --- |
| OpenAPI | a success response with `application/octet-stream` (or another binary media type) and `format: binary` |
| Rust | `reqwest::Response`, to read as bytes or a stream ([`schema.format.binary`](schema.md#schemaformatbinary)) |
| Library | `reqwest::Response` |
| Tests | [`SchemaMappingTest#binaryIsAFileToSendOrAResponseToRead`](../../src/test/java/be/timvw/openapi/rust/SchemaMappingTest.java#L107) |

### `response.content.none`

| | |
| --- | --- |
| OpenAPI | a success response without `content` (`204`) |
| Rust | `Result<(), Error<..>>` |
| Options | `responseFunctions`: `<operation>_response` returns `ApiResponse<()>`, for the status and headers ([`response.content.response-function`](#responsecontentresponse-function)) |
| Tests | [`OperationMappingTest#aResponseWithoutABodyIsUnit`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L144) |

### `response.content.response-function`

| | |
| --- | --- |
| OpenAPI | an operation whose success response has a body or none: JSON (`response.content.json`), text (`response.content.text`), no content (`response.content.none`) |
| Rust | with `responseFunctions`, next to the operation's function `get_item`, a function `get_item_response` with the same arguments and builder, returning `Result<apis::ApiResponse<T>, Error<GetItemError>>`. `T` is what `get_item` returns: the schema's type, `String` for text, `()` without content (`GetItemSuccess` with `supportMultipleResponses`). `ApiResponse<T>` (`src/apis/api_response.rs`, re-exported as `apis::ApiResponse`, written only with the option) has the public fields `status: reqwest::StatusCode` and `headers: reqwest::header::HeaderMap`, and the body: `json()` decodes it as `T`, `json_as::<U>()` as any `U: DeserializeOwned` (a subset of `T`), `json_borrowed::<U>()` as a `U: Deserialize<'a>` that borrows from the response (`&'a str`, `Cow<'a, str>` with `#[serde(borrow)]`, `&RawValue` with serde_json's `raw_value` feature), and `text()` hands it out as `&str`; the three decoders return `serde_json::Error`. For a text success read `text()`: `json()` of `ApiResponse<String>` expects a JSON string literal. `ApiResponse::new` builds one (for tests); it is `Send`, `Sync`, `Clone` and `Debug` whatever `T` is. The operation's own function does not change. Every method of an operation gets one: the `contentTypeMethods` request variants (`create_edit_json_response`) and response variants with a body (`create_transcription_text_response`). A binary response (`response.content.binary`) gets none, since its function already returns the `reqwest::Response` with status and headers; neither does a `text/event-stream` method (`_stream`, `response.content.event-stream`), whose body is a stream, nor a webhook (a webhook function describes a request the API sends to the client, so there is no response to read). A method that would take the name of another method of its API module (an operation `getItemResponse` next to `getItem`) fails generation, naming both |
| Wire | the same request as the operation's function. A success is not checked against its content type (the operation's function fails on a non-JSON body); the body is read as text and kept. An error status is the operation's typed `Error::ResponseError`, rendered by the same code |
| Library | `reqwest::StatusCode`, `reqwest::header::HeaderMap` |
| Options | `responseFunctions`, off by default. Stock has no such function; `supportMultipleResponses` returns a `ResponseContent` with the status and the body text, without headers. See the [guide](../guides/response-functions.md) |
| Tests | [`ResponseFunctionsTest#aJsonOperationGetsAResponseTwinReturningApiResponse`](../../src/test/java/be/timvw/openapi/rust/ResponseFunctionsTest.java#L92), [`#theOperationItselfIsUnchanged`](../../src/test/java/be/timvw/openapi/rust/ResponseFunctionsTest.java#L105), [`#theErrorBranchIsTheOperations`](../../src/test/java/be/timvw/openapi/rust/ResponseFunctionsTest.java#L112), [`#theTwinIsDocumentedAndHasABuilder`](../../src/test/java/be/timvw/openapi/rust/ResponseFunctionsTest.java#L120), [`#aUnitOrTextSuccessHasOneAndAFileDoesNot`](../../src/test/java/be/timvw/openapi/rust/ResponseFunctionsTest.java#L133), [`#apiResponseIsWrittenOnlyWithTheOption`](../../src/test/java/be/timvw/openapi/rust/ResponseFunctionsTest.java#L142), [`#contentTypeMethodsGetOneEventStreamsAndBytesDoNot`](../../src/test/java/be/timvw/openapi/rust/ResponseFunctionsTest.java#L152), [`#aNameThatIsTakenFailsGeneration`](../../src/test/java/be/timvw/openapi/rust/ResponseFunctionsTest.java#L165), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/ResponseFunctionsTest.java#L199) |

### `response.content`

| | |
| --- | --- |
| OpenAPI | a success response whose `content` lists one or more media types |
| Rust | the operation's function decodes the first media type the spec lists |
| Options | `contentTypeMethods` (openai-client-base) adds a function per other response shape: `_json` (JSON), `_text` (other `text/*`), `_bytes` (anything else). One per shape, since the media types of a shape render to the same Rust type. `text/event-stream` is [`response.content.event-stream`](#responsecontentevent-stream) |
| Tests | [`EventStreamsTest#everyMediaTypeHasAMethodNamedWithItsSuffix`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L110), [`#suffixes`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L206), [`#withoutTheOptionsNothingChanges`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L196) |

### `response.content.event-stream`

| | |
| --- | --- |
| OpenAPI | a success response with `text/event-stream` content |
| Rust | a function returning `Result<apis::sse::EventStream<Event, OperationError>, Error<OperationError>>`. It is the operation's own function when the event stream is the response's first media type, `<operation>_stream` otherwise. `EventStream` implements `futures_core::Stream<Item = Result<Event, Error<E>>>`; `EventStream::from_response` and `from_bytes` make one from a body read elsewhere, and `apis::sse::Decoder` is the parser underneath. The crate then depends on `futures-core` and reqwest's `stream` feature, and `src/apis/sse.rs` is written only when an operation streams |
| Wire | sends `Accept: text/event-stream`. An error status is the operation's typed `Error::ResponseError`; a response that is no event stream is `Error::Serde`. Each event's `data:` payload decodes to the event schema (a union by its `type` tag); the SSE `event:` name is not used. `data: [DONE]` or the end of the body ends the stream; an event without data, a comment or an empty payload yields nothing. A payload that does not decode yields `Err(Error::Serde(_))` and the stream goes on; a failure reading the body yields `Err(Error::Reqwest(_))` and ends it. The parser handles CR, LF and CRLF, multi-line data, comments and a byte order mark, and drops an unterminated event at the end |
| Library | the stream reads the `reqwest::Response`'s bytes; a read failure is `Error::Reqwest` |
| Options | `typedEventStreams` (openai-client-base). Without it the response is decoded as JSON, which fails, in every method of the operation (also a `contentTypeMethods` method for another request media type), and `sse.rs` is not written. An event schema that models the SSE envelope (objects with required `event` and `data`, like the Assistants API's `AssistantStreamEvent`) gets no stream, since the payload holds only `data`; the generator logs these operations. With `supportMultipleResponses` the stream function still returns the `EventStream` (the other functions return `ResponseContent<<Op>Success>`) |
| Tests | [`EventStreamsTest#aStreamAsksForItAndChecksTheContentType`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L162), [`#theSseEnvelopeGetsNoStream`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L177), [`#streamsWithoutContentTypeMethods`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L188), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L244), [`#aStreamMethodReturnsTheStreamWithSupportMultipleResponses`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L230), [`#withoutTheOptionsNothingChanges`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L196), [`SmallFixesTest#withoutTypedEventStreamsNoMethodReturnsAStream`](../../src/test/java/be/timvw/openapi/rust/SmallFixesTest.java#L151) |
