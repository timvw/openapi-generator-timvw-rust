# From OpenAPI to Rust

How `timvw-rust` renders each OpenAPI construct. The **Rust** column is the rendering without options
(openai-client-base also sets `multipartHelper`, `typedEventStreams` and `contentTypeMethods`). The **Option** column
names what changes it, and where stock `-g rust` renders something else, what. The former baseline options
(`boxOnlyCycles`, `defaultOnlyWhereValid`, `enumFromStr`, `noStructDisplay`, `integersAsI64`, `qualifiedInlineNames`,
`modelUnions`, `unionDispatch`, `openEnums`, `schemaDefaultDocs`, `nullableUnionFields`) are always applied and cannot
be set. The [README](../README.md) explains each option; the [generator comparison](comparison/README.md) holds the same
constructs against stock `-g rust` and progenitor.

The rendering assumes `library=reqwest`, the only HTTP library `timvw-rust` supports. Rows marked *(reqwest)*
render types that come from the HTTP library; the others do not depend on it
([mapping: HTTP library](mapping/README.md#http-library)).

Every example here was generated from [`comparison/spec.yaml`](comparison/spec.yaml) or a spec like it.

## Schemas

Each of these is a rule with its tests in [mapping/schema.md](mapping/schema.md).

### Scalars

| OpenAPI | Rust | Option |
| --- | --- | --- |
| `type: string` | `String` | |
| `format: date`, `date-time` | `chrono::NaiveDate`, `chrono::DateTime<chrono::FixedOffset>`; a path, query or header parameter or a form field is sent as `YYYY-MM-DD` and RFC 3339 (`to_rfc3339()`) | `useChrono` (default `true`); `useChrono=false` gives `String` and no `chrono` dependency (openai-client-base sets it) |
| `format: uuid` | `uuid::Uuid` in a model; `&str` as a header or query parameter | |
| `format: byte` | `Vec<u8>`, base64 through `#[serde_as(as = "serde_with::base64::Base64")]` | |
| `format: binary` *(reqwest)* | `std::path::PathBuf` as a parameter or body (the file is read or streamed); `reqwest::Response` as a response | |
| `format: email`, `uri`, any other | `String` | |
| `type: number` | `f64`; `format: float` gives `f32` | |
| `type: integer` | `i64`; `format: int32` gives `i32` | always (stock: `i32` unless `format: int64`) |
| `type: integer` with `format: float`/`double` | read as `type: number` (logged) | |
| `type: boolean` | `bool` | |
| `minLength`, `maximum`, `pattern`, ... | the type stays as above; the generated `validate()` of the model checks them and the field's docs list them (`Validation: ...`) | `validateMethods` (default `true`): `false` generates no `Validate` trait |
| a component that is only a scalar (`Name: {type: string}`) | no type of its own: a reference to it is the scalar, with the component's description | `stringIdSchemas` renders listed components as `String` whatever they hold |

### Objects

| OpenAPI | Rust | Option |
| --- | --- | --- |
| `type: object` with `properties` | `pub struct`, `#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, bon::Builder)]`, a field per property with `#[serde(rename = "<name>")]`, and `new(<required fields>)` | `bonBuilders` (on by default) |
| required property | `T` | |
| optional property | `Option<T>`, `skip_serializing_if = "Option::is_none"` | |
| `nullable: true` (or `type: [T, null]`), optional | `Option<Option<T>>` through `serde_with::rust::double_option`: `None` leaves the field out, `Some(None)` sends `null` | |
| `nullable: true`, required | `Option<T>` that must be present | |
| nullable property that is a union | `Option<Option<U>>` (optional) or `Option<U>` (required), like any nullable field, so a request can send `null` | |
| a property referencing a model | the model, by value | always: `Box<T>` only on a reference that closes a cycle (`parent: Option<Box<ThreadNode>>`); stock boxes every model reference |
| `additionalProperties: <schema>` | `std::collections::HashMap<String, T>` | |
| `additionalProperties: true` | `HashMap<String, serde_json::Value>` | |
| `type: object` without properties, or `{}` | `serde_json::Value` (`{}` is nullable: `Option<Option<serde_json::Value>>`) | |
| `readOnly`, `writeOnly` | ignored: the field is there in both directions | |
| `default` | not applied; noted in the field's docs (``Defaults to `false`.``) | |
| `deprecated: true` | no marker | `deprecatedAttributes` adds `#[deprecated]` |
| `description` | `///` on the field or type; bare URLs wrapped in `<...>` | |
| `Default` | only on a struct whose fields are all optional | always (stock: every struct) |
| `Display` of a struct | none; the API code serializes a struct parameter as JSON | |

### Arrays

| OpenAPI | Rust | Option |
| --- | --- | --- |
| `type: array` | `Vec<T>`; items are never boxed | |
| `uniqueItems: true` | `Vec<T>` (not a set); `validate()` reports two equal items | |
| inline items that are objects | a model named after the array (`ChatCompletionChoicesInner`) | always: named `<Schema><property>_inner` unless its title is unique |

### Enums

| OpenAPI | Rust | Option |
| --- | --- | --- |
| `type: string, enum: [...]` (component) | `pub enum` with a unit variant per value (`#[serde(rename = "<value>")]`), `Copy`, `Eq`, `Hash`, `Ord` and a `Display` printing the value | |
| an inline string enum of a property | an enum of the same shape in the model's file, named after the property (`Role`, `Object`) | |
| `type: integer, enum: [...]` | `#[repr(i64)]` enum with `serde_repr`, variants `Variant1 = 1`, ... | |
| parsing | `FromStr` and `TryFrom<&str>`, `TryFrom<&String>`, `TryFrom<String>` accept exactly what `Display` prints; `models::ParseEnumError` otherwise | |
| a value the spec does not list, in a response | the variant `Unknown` (`#[serde(rename = "unknown", other)]`; an integer enum `Unknown = i64::MAX`) | always. Enums only requests use stay closed |
| `Default` | none | always (stock: the first variant) |

### Unions: `oneOf`, `anyOf`, discriminators

These are rules of the mapping now: [mapping/schema.md](mapping/schema.md) and
[mapping/discriminator.md](mapping/discriminator.md) give each one with its tests. The table below is the summary.

| OpenAPI | Rust | Option |
| --- | --- | --- |
| `oneOf` / `anyOf` | `#[serde(untagged)]` enum with a variant per branch, ordered so the narrower one decodes first ([variant order](mapping/schema.md#schemaoneofvariant-order)) | always (stock renders `oneOf` only, and a discriminator as `#[serde(tag)]`, which does not decode when the branch structs keep the tag field) |
| `discriminator` | the same enum, serialized untagged, decoded by the tag: only the selected variant is tried, and its error is the error (``Message::User: missing field `content` ``) | |
| no `discriminator`, but every branch requires one property with fixed values | dispatched on that property like a discriminator | |
| an unknown tag in a response | `Unknown(serde_json::Value)` holding what came | |
| a `type: null` branch | no variant; the union is nullable | |
| variant names | discriminator mapping key, referenced schema, title, then type (`Text(String)`, `ArrayOfStrings(Vec<String>)`) | |
| a string enum branch | its own model `<Union><Variant>Enum` | |
| helpers on a union | `Display` (a string or enum variant prints itself, others their JSON); with a string variant `From<String>`, `From<&str>`, `new_text` | |
| `allOf` of one union | that union's variants | |
| an inline union of a property whose branches are inline and at least one is an object or array | moved to a component `<Schema><Property>` | always |
| `anyOf` with no typed branch (`anyOf: [{}, {type: null}]`) | `serde_json::Value` | always |

### Composition and names

| OpenAPI | Rust | Option |
| --- | --- | --- |
| `allOf` of objects | one struct holding the properties of every part; each referenced part also has its own struct | |
| `$ref` | the referenced model; siblings of a `$ref` are dropped | |
| a recursive schema | by value through `Vec`, boxed on a direct reference (`Option<Box<T>>`) | |
| component names | `CamelCase` model, `snake_case` file (`ChatCompletion` in `chat_completion.rs`), re-exported as `models::ChatCompletion`; hyphens removed (`Thing-2` → `Thing2`) | |
| inline schema names | after where they stand: property `p` of `P` is `PP`, items `P_inner`, map values `P_value`, branches `P_<title>`, bodies `<op>_request`, responses `<op>_<code>_response`; a unique title wins. Two different schemas with one Rust name fail generation | `titleNaming=false` keeps component and array titles out of naming (stock names number a taken title, `Object0_17`) |
| `title` | names an inline schema only when unique (above) | `titleNaming=false` stops titles from merging or replacing component types |

## Operations

Each of these is a rule with its tests in [mapping/](mapping/README.md#objects): [operation.md](mapping/operation.md),
[parameter.md](mapping/parameter.md), [request-body.md](mapping/request-body.md), [response.md](mapping/response.md),
[responses.md](mapping/responses.md), [server.md](mapping/server.md) and [security-scheme.md](mapping/security-scheme.md).

### Functions and modules

| OpenAPI | Rust | Option |
| --- | --- | --- |
| `tags` | a module per tag, `src/apis/<tag>_api.rs`; untagged operations in `default_api` | |
| `operationId` | `pub async fn <snake_case>(configuration: &configuration::Configuration, <parameters...>)`, also callable as a builder (`list_models().configuration(&c).limit(10).call()`) | `bonBuilders` |
| parameters | one argument each, in spec order: required as `T`/`&str`, optional as `Option<..>` | |
| `deprecated` operation | `#[deprecated]` | |
| `webhooks` | a module of their own, tagged `webhookModule` | `webhookModule`, `webhookModuleReexport` |
| request body | the last argument, named after the schema (`create_chat_completion_request: models::CreateChatCompletionRequest`) | |

### Parameters

| OpenAPI | Rust | Option |
| --- | --- | --- |
| path | `&str` or the scalar, URL-encoded into the path | |
| query | `Option<..>` added with `.query(..)` | |
| query array | repeated (`?tag=a&tag=b`) or joined with `,`, as the spec's `style`/`explode` say | `resetQueryParameterStyle` sends all of them repeated |
| query object | sent as a string | `deepObjectQueryParameters` sends listed ones as `deepObject` |
| header | `.header(name, value)`; an array joined with `,` | |
| cookie | **not sent** (the argument is accepted and unused, as in stock) | |
| an inline `enum` | `&str` | `typedEnumParameters`: a component enum (`models::ListModelsOrder`), sent as its value |

### Request bodies

| Media type | Rust | Option |
| --- | --- | --- |
| `application/json` | the schema's type, serialized with `.json(..)` | |
| `multipart/form-data` *(reqwest)* | an argument per property; files as `std::path::PathBuf`, text as `&str` or the type | `multipartHelper`: files through `apis::multipart_helper`, arrays as a part per item, objects as JSON parts, `encoding.contentType` honoured; `fileProperties` turns listed properties into files |
| `application/x-www-form-urlencoded` | an argument per property, sent with `.form(..)` | |
| `application/octet-stream` *(reqwest)* | `std::path::PathBuf`, streamed | |
| `text/plain`, other | the operation's own method sends the first media type | `contentTypeMethods`: one method per media type (`_json`, `_multipart`, `_form`, `_text`, `_bytes`, `_<subtype>`) |

### Responses

| OpenAPI | Rust | Option |
| --- | --- | --- |
| success with a JSON schema | `Result<T, Error<<Op>Error>>`, the body decoded as `T` | |
| success without a body | `Result<(), ..>` | |
| `text/plain` success | `String` | |
| `application/octet-stream` success *(reqwest)* | `reqwest::Response`, to read as bytes or a stream | |
| `text/event-stream` success *(reqwest)* | decoded as JSON, which fails | `typedEventStreams`: `apis::sse::EventStream<Event, <Op>Error>`, each `data:` decoded to the event schema, `[DONE]` ends it |
| several success media types | the first | `contentTypeMethods`: `_json`, `_text`, `_bytes`, `_stream` methods |
| error responses (`4XX`, `404`, `default`) | `Error::ResponseError` with `status`, the raw `content` and `entity: Option<<Op>Error>`; the error enum has a variant per response (`Status404(T)`, `DefaultResponse(T)`) and `UnknownValue(serde_json::Value)`. The status picks the variant (the exact code, then its `4XX` range, then `default`) and the body decodes as that response's type; a body that does not decode is `UnknownValue` | `boxResponseError` boxes `ResponseContent` |
| transport and decoding errors *(reqwest)* | `Error::Reqwest`, `Error::ReqwestMiddleware`, `Error::Serde`, `Error::Io` | |

### Configuration and authentication

| OpenAPI | Rust |
| --- | --- |
| `servers[0].url` | `Configuration::default().base_path` |
| `http` `bearer` | `configuration.bearer_access_token`, sent as `Authorization: Bearer` |
| `http` `basic` | `configuration.basic_auth: Option<(String, Option<String>)>` |
| `apiKey` | `configuration.api_key: Option<ApiKey { prefix, key }>`, in the header the scheme names |
| `oauth2` | `configuration.oauth_access_token` |
| which scheme an operation uses | the operation's `security`, or the spec's |
| user agent | `OpenAPI-Generator/<spec version>/rust`; `userAgentFromPackage` gives `<package>/<crate version>` |
| HTTP client *(reqwest)* | `reqwest_middleware::ClientWithMiddleware`, so retries and tracing plug in as middleware |

## Known gaps

- Cookie parameters are not sent.
- Response headers of a JSON or text response cannot be read.
- Callbacks, links, examples, tag descriptions, external documentation, XML and OAuth flows are not rendered
  ([mapping](mapping/README.md#objects)).
- `readOnly`/`writeOnly` properties are in both directions.
- `format: uuid` is `uuid::Uuid` in models but `&str` in parameters.
