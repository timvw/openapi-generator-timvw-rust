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

`serverWebhooks` (boolean, server only) generates `server::webhooks`, which sends the spec's webhooks.

- **Typed events.** `Webhook` has a variant per webhook of the spec, holding its payload model, so that a value cannot
  be sent as the wrong event:

  ```rust
  pub enum Webhook {
      ResponseCompleted(models::WebhookResponseCompleted),
      BatchCompleted(models::WebhookBatchCompleted),
      // ...
  }
  ```

  The variant is named after the webhook: `response.completed` becomes `ResponseCompleted`.
  `Webhook::name()` gives the spec's name.
- **Endpoints are configuration.** `WebhookEndpoint { url, secret, events }` is what a receiver registers. A `secret`
  of `None` sends without a signature. `events` lists the names the endpoint wants, all of them when empty. A mock
  takes its endpoints from the test. A real server takes them from wherever it keeps registrations.
- **Signed as Standard Webhooks.** `send` writes `webhook-id` (given, or a new `msg_<uuid>`), `webhook-timestamp`
  and `webhook-signature` (`v1` with the endpoint's secret). It writes the JSON body once, and that same text is what
  it signs.
- **One delivery, through the runtime.** `WebhookSender` sends with a `timvw_rust_runtime::Client`, the client's HTTP
  stack: reqwest by default, or any tower service. Its `RetryLayer` gives a delivery its short retries: `429`, `5xx`,
  failed connections, honouring `Retry-After`. Sending over three days needs a queue and storage, so it stays the
  application's: `send` returns a `Delivery` (status, attempts) or a `WebhookError`, and the caller decides what
  comes next.

  ```rust
  let sender = server::webhooks::WebhookSender::new(timvw_rust_runtime::Client::default());
  let endpoint = WebhookEndpoint::new("http://127.0.0.1:8090/hooks").secret(Secret::new("whsec_..."));
  let delivery = sender
      .send(&endpoint, &Webhook::ResponseCompleted(payload), None) // None: a new webhook-id
      .await?;
  ```
- **Verification, for the tests and for receivers.** The same module gets
  `verify(headers, body, secrets, tolerance) -> Result<Webhook, WebhookError>`. It checks a signature in constant
  time and the timestamp within the tolerance, then decodes the event by its type. This is what a test's receiver
  needs to check what the server sent. It is also `unwrap` for an application that receives the API's webhooks.
  Where it belongs is open question 2.
- **Dependencies.** A crate with webhooks needs `timvw-rust-runtime` (sending), `hmac`, `sha2` and `base64`
  (signing), and `uuid` (ids). They are optional, behind a `webhooks` Cargo feature, so a server that does not send
  stays as it is.

## Open questions

1. **The signature scheme.** Standard Webhooks only, or a `serverWebhookSignature` option (`standard-webhooks`,
   `none`) that leaves room for another one later (Stripe's `Stripe-Signature: t=...,v1=...`)? Proposal: Standard
   Webhooks only, with `secret: None` for unsigned.
2. **Where `verify` lives.** In `server::webhooks`, as above, or with the models (every target), so that a
   client-only crate such as openai-client-base gets `unwrap` without the server? Proposal: with the models, behind
   the `webhooks` feature, so that both sides use the same code.
3. **The client's webhook functions.** They `POST` to `{base_path}/{name}`. That is not where a webhook goes, and they
   do not sign. Leave them as they are for compatibility, or replace them with `webhooks::send`? Proposal: leave them,
   and document `webhooks::send` as the way to send.

## What is left out

- Delivery over hours or days (a queue, storage, disabling a failing endpoint): the application's.
- Asymmetric signatures (Standard Webhooks' `v2a`).
- An endpoint registry API: OpenAI's spec has none, and a mock is configured by its tests.
