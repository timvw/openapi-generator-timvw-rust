# Guide: errors

Every generated function returns `Result<T, apis::Error<OperationError>>`. `OperationError` is an enum generated
per operation (`GetItemError` for `get_item`), with a variant per error response the spec declares. This page shows
what each part holds and how to handle it. The rules behind it are
[`responses.statusCode`](../mapping/responses.md#responsesstatuscode) and
[`responses.statusCode.transport-error`](../mapping/responses.md#responsesstatuscodetransport-error).

The examples use [`docs_examples.yaml`](../../src/test/resources/docs_examples.yaml), generated as `my_client`. They
are compiled by the tests.

## The `Error` type

<!-- not compiled: the generated types, abridged -->
```rust
pub enum Error<T> {
    Reqwest(reqwest::Error),                       // sending the request or reading the response failed
    ReqwestMiddleware(reqwest_middleware::Error),  // a middleware failed
    Serde(serde_json::Error),                      // a success body did not decode, or a parameter did not serialize
    Io(std::io::Error),                            // a file to upload could not be read
    ResponseError(Box<ResponseContent<T>>),        // the server answered with a 4xx or 5xx status
}

pub struct ResponseContent<T> {
    pub status: reqwest::StatusCode,
    pub content: String,                           // the body, as text
    pub entity: Option<T>,                         // the body, decoded as the variant the status selects
    pub headers: reqwest::header::HeaderMap,       // every header of the response
}
```

With `useSerdePathToError` there is also `SerdePathToError(serde_path_to_error::Error<serde_json::Error>)`, the
decoding error with the path of the value.

`Error<T>` implements `std::error::Error` (when `T: Debug`, which every generated error enum is), `Display` and
`Debug`, and converts from each of the wrapped errors. `?` takes it into `Box<dyn std::error::Error>`, `anyhow::Error`
or an error type of yours.

## Error statuses

A `4xx` or `5xx` status is `Error::ResponseError`. The status chooses the variant of `entity`:

1. the response with that exact code (`404` gives `Status404`);
2. else the response of its range (`503` gives `Status5XX` when the spec declares `5XX`);
3. else `default` (`DefaultResponse`).

The body is decoded as that response's type. A body that does not decode as it, or a status that none of the three
covers, is `UnknownValue(serde_json::Value)` when the body is JSON. When it is not JSON either, `entity` is `None`,
and `content` still has the body.

For `get_item`, which declares `404` and `5XX`:

```rust
use my_client::apis::configuration::Configuration;
use my_client::apis::{items_api, Error};

async fn item_name(configuration: &Configuration, id: &str) -> Result<Option<String>, Error<items_api::GetItemError>> {
    match items_api::get_item().configuration(configuration).id(id).call().await {
        Ok(item) => Ok(Some(item.name)),
        Err(Error::ResponseError(response))
            if matches!(response.entity, Some(items_api::GetItemError::Status404(_))) =>
        {
            Ok(None)
        }
        Err(error) => Err(error),
    }
}
```

A response the spec declares without a body has a variant without a value, whatever the server sends: in a spec whose
`409` has no content, that is `Status409()`.
A response whose media type is `text/plain` with a `string` schema holds the body as it is. `create_item` declares
`400` as text:

```rust
match items_api::create_item().configuration(&configuration).new_item(&new_item).call().await {
    Err(Error::ResponseError(response)) => match &response.entity {
        Some(items_api::CreateItemError::Status400(message)) => println!("rejected: {message}"),
        Some(items_api::CreateItemError::Status409(problem)) => println!("exists: {}", problem.detail),
        Some(items_api::CreateItemError::Status429(_)) => println!("slow down"),
        Some(items_api::CreateItemError::UnknownValue(value)) => println!("{}: {value}", response.status),
        None => println!("{}: {}", response.status, response.content),
    },
    Err(other) => return Err(other.into()),
    Ok(item) => println!("created {}", item.id),
}
```

The enum is `#[serde(untagged)]` and derives `Serialize`, `Deserialize`, `Clone` and `Debug`, so you can store or
forward the decoded error.

## Headers

`headers` holds every header of the error response, declared in the spec or not. Typical uses are `retry-after`, a
request id or rate limits:

```rust
fn retry_after(response: &ResponseContent<items_api::CreateItemError>) -> Option<std::time::Duration> {
    let seconds = response.headers.get(reqwest::header::RETRY_AFTER)?.to_str().ok()?;
    seconds.parse().ok().map(std::time::Duration::from_secs)
}
```

Header names are matched without regard to case. A header can occur more than once (`get_all`). The headers a spec
declares are not typed; read them by name. With [`responseFunctions`](response-functions.md) they are: the error
headers of each operation are a struct `<Operation>ErrorHeaders`, read with
`response.typed_headers::<items_api::CreateItemErrorHeaders>()`
([declared error headers, typed](response-functions.md#declared-error-headers-typed)). The headers of a success are
in the `_response` functions of that option.

## Boxing

`ResponseError` holds a `Box`, so a `Result` stays small (clippy's `result_large_err`) whatever the error holds. A
match reads the fields through the box as if it were not there (`response.status`, `&response.entity`). To move the
`ResponseContent` out, dereference it:

```rust
if let Err(Error::ResponseError(response)) = result {
    let content: ResponseContent<items_api::GetItemError> = *response;
    println!("{}", content.status);
}
```

To build one, in a test or a mock, box it (`Box::new` or `.into()`):

```rust
let error: Error<items_api::GetItemError> = Error::ResponseError(Box::new(ResponseContent {
    status: reqwest::StatusCode::NOT_FOUND,
    content: r#"{"detail":"no such item"}"#.to_owned(),
    entity: Some(items_api::GetItemError::Status404(models::Problem::new("no such item".to_owned()))),
    headers: reqwest::header::HeaderMap::new(),
}));
assert_eq!(error.to_string(), "error in response: status code 404 Not Found");
```

## Logging

`Display` prints the kind and, for an error status, only the status: `error in response: status code 404 Not Found`.
`Debug` (`{:?}`) prints everything, the body and every response header included. That can include `set-cookie` or
other secrets the server echoes. Log errors with `Display`, or log the fields you choose:

```rust
if let Err(error) = &result {
    match error {
        Error::ResponseError(response) => {
            let request_id = response.headers.get("x-request-id").and_then(|value| value.to_str().ok());
            eprintln!("{error} (request {})", request_id.unwrap_or("-"));
        }
        other => eprintln!("{other}"),
    }
}
```

## Other failures

- `Error::Serde` on a success means the body did not decode as the generated type. A success with an unexpected
  content type (HTML from a proxy) is reported the same way, with a message that names the content type.
- An event stream (`typedEventStreams`) fails as above when the response is an error status. Once it streams, an
  event that does not decode is an `Err(Error::Serde(_))` item and the stream goes on. A broken connection is an
  `Err(Error::Reqwest(_))` item and ends it.
- A `_response` function of `responseFunctions` fails exactly like its operation; decoding happens later, in
  `ApiResponse::json()`, as a `serde_json::Error`.
- `validate()` errors are a separate type, `ValidationErrors` (a list of `ValidationError`); no generated function
  returns them ([validation](validation.md)).
