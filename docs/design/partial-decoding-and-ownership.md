# Design: decoding only what is needed, and ownership

Status: decided, in step 2 of the [mapping roadmap](../history.md#the-mapping-roadmap-done-in-021). The snippets
show the types of the time (`reqwest::StatusCode`); today they are `timvw_rust_runtime`'s. Option A is implemented as the option `responseFunctions`
([guide](../guides/response-functions.md)); option C as [decision 2](../decisions.md#2-request-bodies-by-reference).

Consumers often read a few fields of a large response, but the generated function decodes all of it into owned
values. This page measures what that costs, lists what could change, and recommends what to do.

## How it works today

A generated function for a JSON response ([`response.content.json`](../mapping/response.md#responsecontentjson)):

```rust
let content = resp.text().await?;                         // the body, as a String
match content_type {
    ContentType::Json => serde_json::from_str(&content).map_err(Error::from),   // the whole generated type
    ...
}
```

- The whole body is decoded into the generated type: every `String`, `Vec`, map and nested struct is allocated,
  including the fields the caller never reads. serde skips JSON keys the type does not name, but the generated type
  names them all.
- The value is returned by value. That is a move, not a copy; the cost is in building it.
- Request bodies are taken by value (`item: models::Item`), although the function only borrows it
  (`.json(&p_body_item)`). A caller that keeps the value clones it first.
- A function cannot return a reference: the body it would point into is a local of the function. Borrowing needs an
  owner that outlives the call, so it needs a different API, not a different return type.

## Measurements

[`decode-bench`](decode-bench/) decodes two realistic bodies with the published crates (openai-client-base 0.26.0,
langfuse-client-base 0.18.0): a chat completion with token log probabilities (2 choices, 400 tokens with 5
alternatives each) and a page of 100 Langfuse traces with chat messages as input and output. Each variant decodes
the same bytes; time is the median per decode, allocations are those of one decode. Apple M3 Pro, rustc 1.98.1,
`cargo run --release`; two runs agreed within 10%.

**OpenAI chat completion with logprobs (306 KiB)**

| Variant | Time | vs. today | Allocations | Allocated |
| --- | --- | --- | --- | --- |
| generated type, from text (today) | 663 µs | 1.00× | 12023 | 1235 KiB |
| generated type, from bytes | 721 µs | 1.09× | 12022 | 929 KiB |
| generated type, `logprobs` as raw JSON | 337 µs | 0.51× | 8 | 5 KiB |
| caller's subset (id, message text), owned | 330 µs | 0.50× | 5 | 4 KiB |
| caller's subset, borrowed | 330 µs | 0.50× | 4 | 4 KiB |

**Langfuse trace list, 100 traces (151 KiB)**

| Variant | Time | vs. today | Allocations | Allocated |
| --- | --- | --- | --- | --- |
| generated type, from text (today) | 210 µs | 1.00× | 5307 | 761 KiB |
| generated type, from bytes | 237 µs | 1.13× | 5306 | 610 KiB |
| `input`, `output`, `metadata` as raw JSON | 132 µs | 0.63× | 1907 | 117 KiB |
| caller's subset (id, name, latency), owned | 81 µs | 0.39× | 207 | 17 KiB |
| caller's subset, borrowed | 80 µs | 0.38× | 107 | 16 KiB |

What this shows:

1. **A subset halves the time or better and removes almost every allocation** (12023 to 5, 5307 to 207). The time
   does not go to zero: the parser still scans every byte, which is the floor.
2. **Borrowing adds nothing measurable** on top of a subset: the strings left are few and small. Zero-copy models
   would pay off only for a large string the caller keeps, such as a long message text, and only in memory.
3. **Reading bytes instead of text is slower**, although it allocates less: `from_slice` validates UTF-8 per string,
   which `from_str` skips because the `String` is already valid. Not worth changing.
4. **Leaving heavy fields as raw JSON** gets most of the subset's gain while keeping the generated type for the rest.
5. **In absolute terms decoding is small**: 0.7 ms for a 306 KiB body, which takes tens of milliseconds to arrive.
   The case for change is memory and allocation pressure in services that make many calls, not latency.

## Options

### A. A response function that hands out the body (recommended)

Each operation also gets `<operation>_response`, which does everything the operation's function does (request,
authentication, status handling, the typed error) but returns the body undecoded:

```rust
pub struct ApiResponse<T> {
    pub status: reqwest::StatusCode,
    pub headers: reqwest::header::HeaderMap,
    body: String,
    _type: PhantomData<T>,
}

impl<T: DeserializeOwned> ApiResponse<T> {
    pub fn json(&self) -> Result<T, serde_json::Error>;                       // the generated type, as today
}
impl<T> ApiResponse<T> {
    pub fn json_as<U: DeserializeOwned>(&self) -> Result<U, serde_json::Error>; // a caller's subset, owned
    pub fn json_borrowed<'a, U: Deserialize<'a>>(&'a self) -> Result<U, serde_json::Error>; // borrowing from the body
    pub fn text(&self) -> &str;
}

pub async fn get_item_response(configuration: &Configuration, item_id: &str)
    -> Result<ApiResponse<models::Item>, Error<GetItemError>>;
```

```rust
#[derive(Deserialize)]
struct ItemName<'a> { #[serde(borrow)] name: Cow<'a, str> }

let response = items_api::get_item_response(&configuration, "42").await?;
let name: ItemName = response.json_borrowed()?;   // borrows from `response`
```

- Covers subsets, owned or borrowed, without generating anything per subset.
- Also closes the [`header.schema`](../mapping/header.md#headerschema) gap: the response headers are there.
- The operation's own function stays as it is (`get_item` decodes as today), so nothing breaks; the option adds one
  function per operation. A name that is taken (an operation called `get_item_response`) fails generation.
- Errors stay typed: an error status is still `Error::ResponseError` with the decoded error entity.
- Cost: one more function per JSON operation (openai-client-base has 417 functions), and an `ApiResponse` type in
  `apis`. `reqwest::StatusCode` and `HeaderMap` come from the HTTP library, like `ResponseContent` today.

A generic function (`get_item_as::<U>()`) would cover owned subsets with less API, but not borrowing or headers.

### B. Raw JSON for chosen properties

An option `rawJsonProperties=Schema.property,...` renders those properties as `Box<serde_json::value::RawValue>`:
the JSON text, checked but not parsed, decoded later with `serde_json::from_str(raw.get())`. The measurement used a
borrowed `&RawValue`; an owned `Box<RawValue>` adds one allocation per field, still far below parsing it.

- Fits heavy fields that callers rarely read (`logprobs`, Langfuse `input`/`output`/`metadata`, which are already
  untyped `serde_json::Value`).
- Changes the public type of those fields, so it is a breaking change per listed property, and it is spec-specific
  configuration. Worth it only where A does not fit, such as a list where the caller wants every field but one.

### C. Request bodies by reference

Render a request body argument as `&models::Item` instead of `models::Item`. The function only serializes it, so
the caller keeps its value without cloning. Multipart files (`PathBuf`) and form fields stay as they are.

- Breaking for every caller (`create_item(&c, item)` becomes `create_item(&c, &item)`), and the bon builders need a
  lifetime parameter, which bon supports.
- An option, `borrowedRequestBodies`, turned on with a breaking release of a client.

### D. Borrowed models (not recommended)

A second set of generated types with lifetimes (`Item<'a> { name: Cow<'a, str>, ... }`), so a whole response can be
decoded without copying strings. The lifetime reaches every union, `Vec` and nested model, which roughly doubles the
generated code, and the measurement shows no time gain over a subset. A's `json_borrowed` already lets a caller
borrow the fields it chooses.

### E. Bytes instead of text (not recommended)

`resp.bytes()` with `serde_json::from_slice` allocates a quarter less but is about 10% slower (measurement 3).

### Not possible: returning a reference

`fn get_item(..) -> Result<&models::Item, ..>` has nothing to borrow from once the function returns. Borrowing needs
an owner the caller holds, which is what A's `ApiResponse` is.

## Recommendation

1. **A**, as an option (`responseFunctions`), off by default, so the clients opt in without a breaking change. It
   is the general mechanism: subsets, borrowing and headers, for any spec.
2. **C** with the next breaking release of each client, as an option.
3. **B** only if a client has a concrete field where A does not fit.
4. Not D or E.

Each becomes a rule of the mapping when adopted (`response.content.json` for A, `requestBody.content.json` for C),
with tests, and is measured against both clients before they turn it on.
