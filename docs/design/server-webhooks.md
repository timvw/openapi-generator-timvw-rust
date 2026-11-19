# Design: webhooks sent by the server

Status: proposed. This is the last part of slice 4 of [targets and a server](targets-and-server.md), after validation
(`serverValidation`) and [authentication](server-authentication.md) (`serverAuthentication`).

An OpenAPI 3.1 spec lists the webhooks an API sends under `webhooks`: a name, a method, and the payload as a request
body. OpenAI's spec has 31 of them. One is `response.completed` (`WebhookResponseCompleted`): OpenAI sends it when a
background response finishes. A mock of OpenAI that cannot send them cannot test a client that waits for one. This
page proposes how a generated server sends them.

## What the spec says, and what it does not

The spec gives each webhook's name and payload type. In OpenAI's spec, each has one `post` with a JSON body, and its
`200` response says "Return a 200 status code to acknowledge receipt of the event. Non-200 status codes will be
retried". The spec does not give:

- **The URL.** The receiver registers it outside the API: in OpenAI's dashboard, with the event types it wants.
- **The signature.** OpenAI's [webhooks guide](https://developers.openai.com/api/docs/guides/webhooks) says it follows
  [Standard Webhooks](https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md),
  which covers the signature (below).
- **Redelivery.** OpenAI retries a non-`2xx` answer, or no answer within a few seconds, for up to 72 hours with
  exponential backoff. It does not follow a `3xx`.

So a sender needs configuration that the spec cannot give: endpoints, with a URL and a secret.

## What others do

- **[Standard Webhooks](https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md)**
  is what OpenAI follows.
  - Every delivery is a `POST` with three headers:
    - `webhook-id`: the message's id, the same on a retry, and the receiver's idempotency key;
    - `webhook-timestamp`: Unix seconds;
    - `webhook-signature`: `v1,<base64>` per signature, separated by spaces, so that a secret can be rotated.
  - `v1` is HMAC-SHA256 over `<id>.<timestamp>.<body>`. The key is the base64 after the secret's `whsec_` prefix.
  - Only a `2xx` counts as delivered. A `410` means the endpoint is gone, and a `429`, `502` or `504` means slow down.
  - Its example schedule retries over about three days. It recommends a timeout of 15 to 30 seconds.
- **OpenAI's SDKs** receive webhooks with `client.webhooks.unwrap(body, headers, secret)`. It verifies the signature
  on the raw body and returns the typed event. A receiver can also use a Standard Webhooks library, such as Rust's
  `Webhook::new(secret).verify(...)`.
- **The generators** have nothing to copy for sending:
  - stock `-g rust` (and we, after it) render a webhook as a client function that `POST`s to
    `{base_path}/{webhook name}`;
  - `rust-axum` renders none.

## The proposal

Signing goes into the runtime, as a tower layer: the extension point for any scheme. Both the client's webhook
functions and the server send through the runtime with that layer. The generator adds typed events for the server, and
fixes where the client's functions send.

### 1. The runtime: a signing layer, and `verify` (feature `webhooks`)

- **`StandardWebhooksLayer::new(secret)`** signs every request it sends:
  - it writes `webhook-id` (kept if the request has one, else a new `msg_<uuid>`), `webhook-timestamp` and
    `webhook-signature` (`v1,<base64 HMAC-SHA256 of id.timestamp.body>`);
  - the key is the base64 after the secret's `whsec_` prefix;
  - it signs the bytes that are sent: a JSON body is whole (`Body::as_bytes`), and a body that streams is refused
    with an error rather than sent unsigned.

  The layer is the extension point. A scheme other than Standard Webhooks is another layer: the runtime's, when one
  is needed, or one of the user's own. Without the layer, nothing is signed. So there is no generator option for the
  scheme, and Standard Webhooks is the one the runtime ships.
- **`webhooks::verify(headers, body, secrets, tolerance) -> Result<(), WebhookError>`** is the receiving side:
  - it accepts the request when one of its signatures matches one of the secrets (rotation), compared in constant
    time;
  - it checks the timestamp within the tolerance;
  - the caller then decodes the body as the event type it expects.

  It belongs with whoever receives webhooks, not with the server that sends them. That includes the tests of a mock
  and an application that uses openai-client-base: OpenAI's SDKs have it on the client (`client.webhooks.unwrap`).
- **Dependencies:** `hmac` and `sha2`, behind the feature; `base64` is a dependency already. The sending side uses the
  runtime's `Client`, so retries (`RetryLayer`), timeouts and tracing work as for any call.

### 2. The client's webhook functions send to the endpoint (breaking)

A webhook function `POST`s to `configuration.base_path` itself, the endpoint's whole URL, no longer to
`{base_path}/{webhook name}`. A receiver registers one URL, which `base_path` now is. With a `Client` that has the
signing layer, the function's request is signed:

```rust
let mut configuration = Configuration::new();
configuration.base_path = "http://127.0.0.1:8090/hooks".to_owned(); // the endpoint
configuration.client = Client::default().layer(StandardWebhooksLayer::new(Secret::new("whsec_...")));
webhooks_api::response_completed(&configuration, Some(&payload), None).await?;
```

This is the breaking part: a client that relied on `{base_path}/{name}` adds the name to `base_path` itself. The
migration guide says so.

### 3. The server: `serverWebhooks` (boolean, server only)

The server target gets `server::webhooks`:

- **Typed events.** `Webhook` has a variant per webhook of the spec, holding its payload model, so that a value cannot
  be sent as the wrong event. The variant is named after the webhook (`response.completed` as
  `ResponseCompleted`), and `Webhook::name()` gives the spec's name:

  ```rust
  pub enum Webhook {
      ResponseCompleted(models::WebhookResponseCompleted),
      BatchCompleted(models::WebhookBatchCompleted),
      // ...
  }
  ```
- **Endpoints are configuration.** `WebhookEndpoint { url, client, events }` is what a receiver registers: its URL,
  the runtime `Client` to send with (with the signing layer and its secret, or without), and the event names it wants
  (all of them when empty). A mock takes its endpoints from the test, and a real server from wherever it keeps
  registrations.
- **One delivery.** `send(&endpoint, &Webhook)` writes the JSON once and `POST`s it through the endpoint's client,
  whose layers sign and retry it (`429`, `5xx`, failed connections, honouring `Retry-After`). It returns the status,
  or an error when no attempt got a `2xx`. Delivery over days needs a queue and storage, so it stays the
  application's.

  ```rust
  let client = Client::default().layer(StandardWebhooksLayer::new(Secret::new("whsec_...")));
  let endpoint = WebhookEndpoint::new("http://127.0.0.1:8090/hooks", client);
  server::webhooks::send(&endpoint, &Webhook::ResponseCompleted(payload)).await?;
  ```
- **Dependencies.** The server depends on `timvw-rust-runtime` (feature `webhooks`), optionally behind a `webhooks`
  Cargo feature of the crate, so a server that does not send stays as it is.

A test of the mock sends with `server::webhooks::send` to an axum receiver of its own, which checks the request with
`timvw_rust_runtime::webhooks::verify`.

## Order

1. The runtime: the layer and `verify`, released on their own.
2. The generator: the client's webhook functions send to `base_path` (breaking), and `serverWebhooks`.

## What is left out

- Delivery over hours or days (a queue, storage, disabling a failing endpoint): the application's.
- Asymmetric signatures (Standard Webhooks' `v2a`), and schemes other than Standard Webhooks, until one is needed.
- An endpoint registry API: OpenAI's spec has none, and a mock is configured by its tests.
