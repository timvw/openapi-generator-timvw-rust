# Guide: response functions (`responseFunctions`)

Every generated function decodes the whole response body into the generated type and returns only that value:

```rust
let item: models::Item = items_api::get_item()
    .configuration(&configuration)
    .id("42")
    .call()
    .await?;
```

That has two consequences. The status and the headers of a success are lost. And the whole body is decoded: every
`String`, `Vec`, map and nested struct of the generated type is allocated, including the fields the caller never
reads.

With `responseFunctions`, each such operation also gets a function named `<operation>_response`. It sends the same
request and fails the same way, but returns the success undecoded, as an [`ApiResponse<T>`](#apiresponset), with its
status and headers. The caller then decides how much of the body to decode, and into what.

The rule is [`response.content.response-function`](../mapping/response.md#responsecontentresponse-function); this
page is about using it. The design and its measurements are in
[decoding only what is needed, and ownership](../design/partial-decoding-and-ownership.md) (option A), and the
decision is [decision 1](../decisions.md#1-response-functions-returning-apiresponset).

## When to use it

- **You need a header or the exact status of a success**: a request id (`x-request-id`), rate-limit headers, a
  `Location`, an `ETag`, or telling `200` from `201`.
- **You read a few fields of a large response**: decode a subset of your own (`json_as`) instead of the generated
  type. On the measured bodies that halves the decode time or better and removes almost every allocation (see
  [performance](#performance)).
- **You keep strings of a response only while you hold it**: decode a subset that borrows from the body
  (`json_borrowed`).
- **You want the raw body**: to log it, store it or decode it with another library (`text`).

When you need the whole generated value and nothing else, call the operation's own function: it is unchanged, and
it checks the content type for you.

## Enabling it

The option is off by default. Turn it on with `--additional-properties=responseFunctions=true`, or in a `-c` config
file:

```json
{
  "responseFunctions": true
}
```

It adds functions and `src/apis/api_response.rs`; nothing that exists changes, so turning it on is not a breaking
change. Turning it off again removes the functions and the `mod` line, but `src/apis/api_response.rs` stays on disk
until it is deleted (openapi-generator does not remove files it no longer writes), unless the client's generation
script cleans the output directory first.

## What is generated

For an operation

```yaml
/items/{id}:
  get:
    operationId: getItem
    responses:
      "200":
        content:
          application/json:
            schema: { $ref: '#/components/schemas/Item' }
      "404":
        content:
          application/json:
            schema: { $ref: '#/components/schemas/Problem' }
```

the API module has, next to `get_item`:

```rust
#[bon::builder]
pub async fn get_item_response(configuration: &configuration::Configuration, id: &str)
    -> Result<super::ApiResponse<models::Item>, Error<GetItemError>>
```

- The same arguments in the same order, and the same builder (`get_item_response().configuration(&c).id("42")
  .call()`), as `get_item`.
- `T` in `ApiResponse<T>` is what `get_item` returns: the schema's type for JSON, `String` for a `text/plain`
  success, `()` for a success without content (a `204`), and `GetItemSuccess` with `supportMultipleResponses`.
- The same error type, `Error<GetItemError>`. An error status is `Error::ResponseError` with the typed entity,
  rendered by the same code as in `get_item`.
- Its documentation says which function it mirrors and what `json()` decodes to.

Which functions get a twin:

| The method returns | `_response` twin |
| --- | --- |
| a JSON body (`models::Item`, `Vec<models::Item>`, ...) | yes, `ApiResponse<models::Item>` |
| a text body (`String`) | yes, `ApiResponse<String>`; read it with `text()` (`json()` expects a JSON string literal, `"..."`) |
| nothing (`()`) | yes, `ApiResponse<()>`, for the status and headers |
| a binary body (`reqwest::Response`) | no: that already is the response, with `status()`, `headers()` and the bytes |
| an event stream (`typedEventStreams`, `EventStream<..>`) | no: the body is a stream, not a value to hold |
| a webhook (`webhookModule`) | no: a webhook function describes a request the API sends to you, so there is no response to read |

With `contentTypeMethods`, every method of an operation that returns a body gets one: `create_edit_json_response`
for the JSON request variant of `create_edit`, `create_transcription_text_response` for its text response variant.
The `_stream` and `_bytes` methods get none.

A twin's name must be free: an operation `getItemResponse` in the same API module as `getItem` fails generation,
naming both. Rename one of them (its `operationId`) or leave the option off. An operation whose name already ends in
`_response` gets a twin ending in `_response_response`: in openai-client-base `create_response`, `get_response`,
`cancel_response` and `delete_response` (and their `beta_` versions) get `create_response_response` and so on.

## `ApiResponse<T>`

```rust
pub struct ApiResponse<T> {
    pub status: reqwest::StatusCode,
    pub headers: reqwest::header::HeaderMap,
    // the body, as text
}

impl<T> ApiResponse<T> {
    pub fn new(status: reqwest::StatusCode, headers: reqwest::header::HeaderMap, body: String) -> Self;
    pub fn text(&self) -> &str;
    pub fn json_as<U: DeserializeOwned>(&self) -> Result<U, serde_json::Error>;
    pub fn json_borrowed<'a, U: Deserialize<'a>>(&'a self) -> Result<U, serde_json::Error>;
}

impl<T: DeserializeOwned> ApiResponse<T> {
    pub fn json(&self) -> Result<T, serde_json::Error>;
}
```

It is in `src/apis/api_response.rs` and re-exported as `apis::ApiResponse`. It holds no `T`, so it is `Send`, `Sync`,
`Clone` and `Debug` whatever `T` is, and a `_response` future can be spawned. Every method borrows the response, so
you can decode it more than once, or in two ways.

## Examples

The examples use the operation above, `get_item` returning `models::Item` (`id`, `name`, optional `tags` and
`owner`), and `configuration` an `apis::configuration::Configuration`.

### The whole body, as the generated type

```rust
let response = items_api::get_item_response()
    .configuration(&configuration)
    .id("42")
    .call()
    .await?;
let item: models::Item = response.json()?;
```

This is what `get_item` returns. The difference is that `response` still has the status and headers, and that a
body that is not JSON fails in `json()` as a `serde_json::Error`, not in the call (see [caveats](#caveats)).

### A subset, owned

Declare the fields you read; serde skips the others without allocating them:

```rust
use serde::Deserialize;

#[derive(Deserialize)]
struct ItemSummary {
    id: String,
    name: String,
}

let summary: ItemSummary = response.json_as()?;
```

A subset can nest (`owner: Owner` with only `name`), skip optional fields (`Option<T>`), and use any serde attribute
(`#[serde(rename = "...")]`, `#[serde(default)]`). It does not have to be a subset: any type the body decodes to will
do, `serde_json::Value` included.

### A subset, borrowed

When you only need the strings while you hold the response, borrow them from the body:

```rust
use std::borrow::Cow;
use serde::Deserialize;

#[derive(Deserialize)]
struct ItemNames<'a> {
    id: &'a str,                 // fails if the JSON string has an escape (\", \n, \u...)
    #[serde(borrow)]
    name: Cow<'a, str>,          // borrows when it can, copies when it has to
}

let names: ItemNames = response.json_borrowed()?;   // lives as long as `response`
println!("{} {}", names.id, names.name);
```

Use `Cow<'a, str>` with `#[serde(borrow)]` for strings that may contain escapes, and `&'a str` only where they
cannot. `&'a serde_json::value::RawValue` keeps a part of the body as JSON text, checked but not parsed, to decode
later or pass on; it needs serde_json's `raw_value` feature in your `Cargo.toml`
(`serde_json = { version = "1", features = ["raw_value"] }`). The value cannot outlive `response`; to keep it, decode with `json_as` instead.

### Status and headers

```rust
let response = items_api::get_item_response()
    .configuration(&configuration)
    .id("42")
    .call()
    .await?;

if response.status == reqwest::StatusCode::NON_AUTHORITATIVE_INFORMATION {
    // ...
}
let request_id = response
    .headers
    .get("x-request-id")
    .and_then(|value| value.to_str().ok());
let item: models::Item = response.json()?;
```

`headers` is a `reqwest::header::HeaderMap`: lookups ignore case, and a header can occur more than once
(`get_all`). The headers a spec declares for a response are not typed; read them by name.

For a success without content, the twin is how you get its headers at all:

```rust
let deleted = items_api::delete_item_response()
    .configuration(&configuration)
    .id("42")
    .call()
    .await?;
assert_eq!(deleted.status, reqwest::StatusCode::NO_CONTENT);
let location = deleted.headers.get(reqwest::header::LOCATION);
```

### The raw body

```rust
let body: &str = response.text();
```

For a `text/plain` success (`ApiResponse<String>`) this is the text. For JSON, it is the body as it arrived, for
logging, storing or decoding with something else.

### Errors

The error is the operation's, and is handled the same way:

```rust
use myclient::apis::Error;

match items_api::get_item_response()
    .configuration(&configuration)
    .id("42")
    .call()
    .await
{
    Ok(response) => {
        let item: models::Item = response.json()?;
    }
    Err(Error::ResponseError(content)) => match content.entity {
        Some(items_api::GetItemError::Status404(problem)) => println!("not found: {}", problem.detail),
        _ => println!("status {}: {}", content.status, content.content),
    },
    Err(other) => return Err(other),
}
```

A transport failure is `Error::Reqwest` or `Error::ReqwestMiddleware`, as in the operation's function. Decoding
happens afterwards, in `json()`, `json_as()` or `json_borrowed()`, and fails as a `serde_json::Error`, which
converts into the crate's `Error` (`Error::Serde`) with `?` in a function returning it.

`ApiResponse` covers successes. An error status is the operation's `Error::ResponseError`, which holds a
`Box<ResponseContent<GetItemError>>` with the `status`, the body text (`content`), the decoded `entity` and the error
response's `headers` (a `Retry-After` of a `429`, say), as described in [`responses`](../mapping/responses.md).

### In tests

`ApiResponse::new` builds a response without a server, for code that takes one:

```rust
let response: ApiResponse<models::Item> = ApiResponse::new(
    reqwest::StatusCode::OK,
    reqwest::header::HeaderMap::new(),
    r#"{"id":"42","name":"first"}"#.to_owned(),
);
assert_eq!(response.json()?.name, "first");
```

The generated `src/apis/api_response.rs` has a doctest along these lines that runs with `cargo test --doc`.

## Performance

Measured with [`decode-bench`](../design/decode-bench/) on the published crates (openai-client-base 0.26.0,
langfuse-client-base 0.18.0): the median time per decode and the allocations of one decode, Apple M3 Pro, rustc
1.98.1, release build.

**OpenAI chat completion with logprobs (306 KiB)**

| Decoding | Time | vs. the generated type | Allocations | Allocated |
| --- | --- | --- | --- | --- |
| the generated type (`json()`, what the operation's function does) | 663 µs | 1.00× | 12023 | 1235 KiB |
| a subset, owned (`json_as()`: id, message text) | 330 µs | 0.50× | 5 | 4 KiB |
| a subset, borrowed (`json_borrowed()`) | 330 µs | 0.50× | 4 | 4 KiB |

**Langfuse trace list, 100 traces (151 KiB)**

| Decoding | Time | vs. the generated type | Allocations | Allocated |
| --- | --- | --- | --- | --- |
| the generated type | 210 µs | 1.00× | 5307 | 761 KiB |
| a subset, owned (id, name, latency) | 81 µs | 0.39× | 207 | 17 KiB |
| a subset, borrowed | 80 µs | 0.38× | 107 | 16 KiB |

What to take from it:

- **A subset is the gain**: half the time or less, and almost no allocations. The time does not go to zero, since
  the parser still reads every byte.
- **Borrowing adds little on top of a subset.** Borrow when you would otherwise copy large strings you only read;
  otherwise `json_as` is simpler.
- **Decoding is small next to the network**: under a millisecond for a body that takes tens of milliseconds to
  arrive. The case for a subset is memory and allocation pressure in a service that makes many calls, not the
  latency of one call.

`ApiResponse` keeps the body as one `String` for as long as you hold it. That is the allocation the operation's
function makes too, before it decodes and drops it.

## Caveats

- **The content type is not checked.** The operation's function fails a success that is not JSON (or text, where the
  spec allows it) with a message naming the content type. The twin returns whatever came, and `json()` fails later
  with serde's message. Look at `response.headers.get(reqwest::header::CONTENT_TYPE)` if it matters.
- **`json()` decodes with `serde_json`** and returns a `serde_json::Error`, also when the crate is generated with
  `useSerdePathToError`, so its error does not carry the path. Where you need the path, decode the text yourself:

  ```rust
  let item: models::Item =
      serde_path_to_error::deserialize(&mut serde_json::Deserializer::from_str(response.text()))?;
  ```
- **`json()` of `ApiResponse<String>`** expects a JSON string literal (`"1.2.3"` with the quotes), which a
  `text/plain` body is not. Read text with `text()`.
- **`json()` of `ApiResponse<()>`** expects a JSON `null`, which a `204` does not have. Use `status`, `headers` and
  `text()` for those.
- **Two functions per operation** make the API modules about twice as long (each twin repeats the request code), so
  the crate takes longer to compile. Unused functions cost nothing at run time.
