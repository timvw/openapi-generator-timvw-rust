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
| Tests | [`OperationMappingTest#aJsonResponseIsDecodedAndATextResponseIsAString`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L136) |

### `response.content.text`

| | |
| --- | --- |
| OpenAPI | a success response with `text/plain` content |
| Rust | `String`, the body as it came |
| Tests | [`OperationMappingTest#aJsonResponseIsDecodedAndATextResponseIsAString`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L136) |

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
| Tests | [`OperationMappingTest#aResponseWithoutABodyIsUnit`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L143) |

### `response.content`

| | |
| --- | --- |
| OpenAPI | a success response whose `content` lists one or more media types |
| Rust | the operation's function decodes the first media type the spec lists |
| Options | `contentTypeMethods` (openai-client-base) adds a function per other response shape: `_json` (JSON), `_text` (other `text/*`), `_bytes` (anything else). One per shape, since the media types of a shape render to the same Rust type. `text/event-stream` is [`response.content.event-stream`](#responsecontentevent-stream) |
| Tests | [`EventStreamsTest#everyMediaTypeHasAMethodNamedWithItsSuffix`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L99), [`#suffixes`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L194), [`#withoutTheOptionsNothingChanges`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L184) |

### `response.content.event-stream`

| | |
| --- | --- |
| OpenAPI | a success response with `text/event-stream` content |
| Rust | a function returning `Result<apis::sse::EventStream<Event, OperationError>, Error<OperationError>>`. It is the operation's own function when the event stream is the response's first media type, `<operation>_stream` otherwise. `EventStream` implements `futures_core::Stream<Item = Result<Event, Error<E>>>`; `EventStream::from_response` and `from_bytes` make one from a body read elsewhere, and `apis::sse::Decoder` is the parser underneath. The crate then depends on `futures-core` and reqwest's `stream` feature, and `src/apis/sse.rs` is written only when an operation streams |
| Wire | sends `Accept: text/event-stream`. An error status is the operation's typed `Error::ResponseError`; a response that is no event stream is `Error::Serde`. Each event's `data:` payload decodes to the event schema (a union by its `type` tag); the SSE `event:` name is not used. `data: [DONE]` or the end of the body ends the stream; an event without data, a comment or an empty payload yields nothing. A payload that does not decode yields `Err(Error::Serde(_))` and the stream goes on; a failure reading the body yields `Err(Error::Reqwest(_))` and ends it. The parser handles CR, LF and CRLF, multi-line data, comments and a byte order mark, and drops an unterminated event at the end |
| Library | the stream reads the `reqwest::Response`'s bytes; a read failure is `Error::Reqwest` |
| Options | `typedEventStreams` (openai-client-base). Without it the response is decoded as JSON, which fails. An event schema that models the SSE envelope (objects with required `event` and `data`, like the Assistants API's `AssistantStreamEvent`) gets no stream, since the payload holds only `data`; the generator logs these operations |
| Tests | [`EventStreamsTest#aStreamAsksForItAndChecksTheContentType`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L150), [`#theSseEnvelopeGetsNoStream`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L165), [`#streamsWithoutContentTypeMethods`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L176), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L214), [`#withoutTheOptionsNothingChanges`](../../src/test/java/be/timvw/openapi/rust/EventStreamsTest.java#L184) |
