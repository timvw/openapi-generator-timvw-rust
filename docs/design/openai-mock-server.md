# Design: a mock OpenAI server, compared with LiteLLM and Bifrost

Status: implemented, as [openai-mock-server](https://github.com/genai-rs/openai-mock-server). This was the plan for
slice 3 of [targets and a server](targets-and-server.md): a mock OpenAI server generated from OpenAI's spec, for
openai-ergonomic's tests. One thing was decided differently from the plan below, see
[what was built](#what-was-built): the mock generates its own models, and shares only the spec with the client.

Before building it, this page looks at two OpenAI-compatible gateways that serve the same API by hand:
[LiteLLM](https://github.com/BerriAI/litellm)'s proxy (Python, with a Rust gateway in progress) and
[Bifrost](https://github.com/maximhq/bifrost) (Go). Links are pinned to the commits read:
LiteLLM [`907f91a`](https://github.com/BerriAI/litellm/tree/907f91a4b65b204778097995ee810444fe02b1a1),
Bifrost [`958a5b3`](https://github.com/maximhq/bifrost/tree/958a5b3f7fb64511fa564a59267376b6f2e3976a).

## What they serve, and how they route it

Both serve a large part of the OpenAI API, by hand, one route at a time:

- **LiteLLM** registers each operation under several paths: `/v1/chat/completions`, `/chat/completions`, and Azure's
  `/openai/deployments/{model}/chat/completions`
  ([proxy_server.py](https://github.com/BerriAI/litellm/blob/907f91a4b65b204778097995ee810444fe02b1a1/litellm/proxy/proxy_server.py#L13578)).
  One table maps internal call types to paths, from responses and videos to containers and skills
  ([route_llm_request.py](https://github.com/BerriAI/litellm/blob/907f91a4b65b204778097995ee810444fe02b1a1/litellm/proxy/route_llm_request.py#L156)).
  Its Rust gateway serves a smaller set so far: chat completions, responses, audio transcriptions, messages and OCR.
  It answers `unsupported` for embeddings and completions
  ([gateway-inference/src/lib.rs](https://github.com/BerriAI/litellm/blob/907f91a4b65b204778097995ee810444fe02b1a1/litellm/rust/crates/gateway-inference/src/lib.rs#L102)).
- **Bifrost** has one route list for the OpenAI shape
  ([handlers/inference.go](https://github.com/maximhq/bifrost/blob/958a5b3f7fb64511fa564a59267376b6f2e3976a/transports/bifrost-http/handlers/inference.go#L881)):
  - completions, chat completions, responses (with get, delete, cancel, input items, input tokens and compact);
  - embeddings, rerank, OCR;
  - audio speech and transcriptions;
  - images (generations, edits, variations), videos;
  - batches, files, containers.

  It also serves OpenAI's paths under an `/openai` prefix for the SDK integrations.

For a mock, the operations that matter are the ones openai-ergonomic wraps and tests. In order:

1. chat completions and responses, both streamed and not;
2. embeddings and models;
3. files (multipart upload, binary content download) and batches;
4. images (generations, edits) and audio (speech is binary, transcription is multipart);
5. the realtime call (an SDP offer in, an SDP answer out), which the genai-agents demo uses.

## Server-sent events

- **Framing:**
  - Chat completions streams are `data: <json>` frames ending in `data: [DONE]`
    ([proxy_server.py](https://github.com/BerriAI/litellm/blob/907f91a4b65b204778097995ee810444fe02b1a1/litellm/proxy/proxy_server.py#L10376)).
  - Responses streams carry `event: <type>` before `data:`. Bifrost writes the event name when it has one
    ([lib/streamreader.go](https://github.com/maximhq/bifrost/blob/958a5b3f7fb64511fa564a59267376b6f2e3976a/transports/bifrost-http/lib/streamreader.go#L123)).
  - LiteLLM's Rust decoder test accepts event names, keep-alive comments, unknown future events and `[DONE]`
    in one stream ([streaming.rs](https://github.com/BerriAI/litellm/blob/907f91a4b65b204778097995ee810444fe02b1a1/litellm/rust/crates/llms/src/base_llm/responses/streaming.rs#L71)).
- **Keep-alive:** both send SSE comment lines while the upstream is slow:
  - LiteLLM sends `: ping`
    ([sse_keepalive.py](https://github.com/BerriAI/litellm/blob/907f91a4b65b204778097995ee810444fe02b1a1/litellm/proxy/common_utils/sse_keepalive.py#L12)).
  - Bifrost sends `: heartbeat`, framed per route. It writes none where a client rejects comments: one SDK breaks
    on them, and older openai-go dispatches an empty event for a comment followed by a blank line
    ([lib/streamreader.go](https://github.com/maximhq/bifrost/blob/958a5b3f7fb64511fa564a59267376b6f2e3976a/transports/bifrost-http/lib/streamreader.go#L152)).
  - So the encoder has to get comments right, and a client's decoder has to skip them.
- **Errors after the status is sent:** the HTTP status can no longer change, so the error becomes the stream's last
  frame.
  - For chat completions, LiteLLM sends `data: {"error": {...}}` and then `data: [DONE]`
    ([common_request_processing.py](https://github.com/BerriAI/litellm/blob/907f91a4b65b204778097995ee810444fe02b1a1/litellm/proxy/common_request_processing.py#L1539)).
  - For responses, it sends `event: error`, with the next `sequence_number`
    ([responses_stream_errors.py](https://github.com/BerriAI/litellm/blob/907f91a4b65b204778097995ee810444fe02b1a1/litellm/proxy/common_utils/responses_stream_errors.py#L383)).
  - Bifrost's `SendError` writes `event: error`.
- **Passing streams through:** both decode the provider's stream, change it (LiteLLM rewrites the model name inside
  each event), and encode it again.
  - Bifrost tests that the encoded chat stream is valid SSE, with the real provider, route converter and writer
    together
    ([openai_stream_framing_test.go](https://github.com/maximhq/bifrost/blob/958a5b3f7fb64511fa564a59267376b6f2e3976a/transports/bifrost-http/integrations/openai_stream_framing_test.go#L55)).
  - The comment above that test says it exists because unframed JSON slipped through before.

## Bodies with more than one media type, and realtime

- **Realtime calls** take either a raw SDP body or a multipart form (`sdp` and `session`):
  - Bifrost serves both on `/v1/realtime/calls`, and the legacy form on `/v1/realtime`. It picks by `Content-Type`
    ([handlers/webrtcrealtime.go](https://github.com/maximhq/bifrost/blob/958a5b3f7fb64511fa564a59267376b6f2e3976a/transports/bifrost-http/handlers/webrtcrealtime.go#L39)).
  - LiteLLM answers the SDP offer with the SDP answer as `text/plain`, status 201
    ([realtime_endpoints/endpoints.py](https://github.com/BerriAI/litellm/blob/907f91a4b65b204778097995ee810444fe02b1a1/litellm/proxy/realtime_endpoints/endpoints.py#L492)).
  - Both also proxy the WebSocket form of realtime.
- **Multipart** (audio transcription, image edits, file upload) is parsed per handler in both.
- This is the gap slice 2 left: our server skips an operation whose body has more than one media type. Slice 3 needs
  a body enum chosen by `Content-Type` for image edits and the realtime call. Another change is adding that.
  WebSocket realtime is out of scope for a generated server; the spec does not describe it.

## Errors, authentication, headers

- **Errors:**
  - LiteLLM answers OpenAI's envelope, `{"error": {"message", "type", "param", "code"}}`, with the status from the
    exception ([`ProxyException`](https://github.com/BerriAI/litellm/blob/907f91a4b65b204778097995ee810444fe02b1a1/litellm/proxy/_types.py#L4048)).
  - Bifrost's own error adds `is_bifrost_error`, `status_code` and extra fields around an `error` object
    ([schemas/bifrost.go](https://github.com/maximhq/bifrost/blob/958a5b3f7fb64511fa564a59267376b6f2e3976a/core/schemas/bifrost.go#L2307)).
  - A mock should send OpenAI's envelope exactly, and the statuses the client maps: 400, 401, 404, 429 and 5xx.
- **Authentication:** both check a bearer key in middleware, before the route. The realtime call is the exception:
  its bearer token is an ephemeral client secret. The mock only needs to check that `Authorization` is there, and
  optionally that it matches.
- **Headers:**
  - Both pass the provider's headers through and add their own: LiteLLM `x-litellm-call-id` and rate-limit headers
    from its limiter, Bifrost `x-bf-*`.
  - The headers openai-ergonomic reads (`x-request-id`, `x-ratelimit-*`, `openai-processing-ms`) are in the spec as
    response headers. The generated server already writes them, as typed structs.

## How they test OpenAI compatibility

- **LiteLLM:**
  - **A canned fake endpoint** answers chat, text, embeddings and moderations with realistic `usage`. A `model` of
    `429` returns 429, and a slow model exercises timeouts
    ([_fake_openai_endpoint_server.py](https://github.com/BerriAI/litellm/blob/907f91a4b65b204778097995ee810444fe02b1a1/tests/_fake_openai_endpoint_server.py)).
  - **A record and replay proxy:** recordings in Redis expire after a day, so provider drift surfaces within a day
    ([_openai_record_replay_proxy.py](https://github.com/BerriAI/litellm/blob/907f91a4b65b204778097995ee810444fe02b1a1/tests/_openai_record_replay_proxy.py),
    [VCR_TESTING.md](https://github.com/BerriAI/litellm/blob/907f91a4b65b204778097995ee810444fe02b1a1/tests/VCR_TESTING.md)).
  - **OpenAPI compliance tests** against a spec snapshot, with a scheduled canary against the live spec. These exist
    for Google's Interactions API only
    ([test_openapi_compliance.py](https://github.com/BerriAI/litellm/blob/907f91a4b65b204778097995ee810444fe02b1a1/tests/unit/interactions/test_openapi_compliance.py)).
- **Bifrost:**
  - **The official Python and TypeScript SDKs** run against a running Bifrost, with real provider keys
    ([tests/integrations/python](https://github.com/maximhq/bifrost/blob/958a5b3f7fb64511fa564a59267376b6f2e3976a/tests/integrations/python/README.md)).
  - **Scenario tests** across providers in Go
    ([core/internal/llmtests](https://github.com/maximhq/bifrost/tree/958a5b3f7fb64511fa564a59267376b6f2e3976a/core/internal/llmtests)).
  - **Wire-level framing tests** per route.
- **What neither has:** a server whose types come from OpenAI's spec. Both keep hand-written request and response
  types in step with OpenAI, and find drift with live or recorded traffic.

## What a generated server buys, and what we would miss

**What it buys:**
- **Coverage:** every operation in the spec gets a route, a typed request and a typed response, the same day the
  spec changes. Both gateways add routes by hand, and the table above shows how long that list is.
- **Types from the spec:** the mock and the client are generated from the same spec, so they agree on field names,
  enum values and event types wherever their spec revisions do. A test then fails on behaviour, not on fixtures
  that went stale. (The plan was to share the models crate, `modelsCrate`; see [what was built](#what-was-built).)
- **Headers and statuses come from the spec,** as typed values, not strings.

**What we would miss, and should copy:**
- **SSE details from real traffic:**
  - `event:` names on Responses streams;
  - keep-alive comments;
  - errors after the status, in both forms (`data: {"error"}` with `[DONE]`, and `event: error`);
  - streams that end without `[DONE]`.

  The mock should be able to produce each of these on purpose, so the client's decoder is tested against them.
- **Behaviour the spec does not describe:** rate limiting with `retry-after`, slow responses, connections that drop.
  These are scripted responses, not generated code.
- **Drift detection:** LiteLLM's day-long recordings catch a provider that changes, and a mock built from a spec
  snapshot does not. openai-client-base already regenerates from the upstream spec, which covers the documented
  part. A small set of live tests (openai-ergonomic has them, behind a key) covers the rest.

**A Rust gateway on the generated server** is possible: the server is axum and the client is a tower stack. But
running a gateway is mostly routing, keys, budgets, caching and observability, which is most of both codebases, not
the HTTP layer. LiteLLM is writing its Rust gateway crate by crate (about 90 crates), and it serves five inference
routes so far. Not worth starting now. The mock first; if it works well, a pass-through "record, then replay" mode
on the same server is the useful next step, and closer to what tests need than a gateway.

## Slice 3 plan

1. **Generate the server crate.**
   - What: a crate in openai-client-base (or beside it) with `targets: ["server"]` and
     `modelsCrate: openai-client-base`.
   - Before that: the body enum for operations with more than one media type, and `event:` names on streams whose
     events have a type. Both are generator work.
2. **Endpoints in scope:**
   - chat completions and responses (JSON and SSE);
   - embeddings, models;
   - files (upload, list, content);
   - batches;
   - images (generations, edits);
   - audio (speech, transcriptions);
   - moderations;
   - the realtime call (SDP in, SDP out).

   The rest answers `501` with OpenAI's error envelope, from a default implementation of every trait.
3. **A scripted mock (`openai-mock`, a test crate):**
   - It implements the generated traits with a queue of scripted replies per operation: a model, a stream of events,
     an error with a status, or a delay.
   - It records the decoded requests, so tests assert on typed requests, not on JSON strings.
   - It starts on `127.0.0.1:0` and returns its base URL.
   - Builders cover the common cases: a text answer, a tool call, a streamed answer, and a 429 with `retry-after`.
4. **Wire options for streams:** keep-alive comments, `event:` names on or off, an error mid-stream in either form,
   and no `[DONE]`. Each is a flag on the scripted stream.
5. **openai-ergonomic consumes it:**
   - It replaces the mockito harness (`test-utils`, `tests/harness`) one test file at a time, starting with
     streaming, responses, files and errors.
   - It keeps `MockOpenAIServer` as a thin wrapper, so tests change little.
   - genai-agents can use the same mock for its provider tests.
6. **Later (slice 4):**
   - authentication schemes;
   - recording: forward to OpenAI once, store the typed exchange, replay it, re-record on demand;
   - spec-based checks of the mock's answers.

## What was built

The plan above put the server in a crate next to openai-client-base, with the client's models (`modelsCrate`). That
was built first, and then undone:

- **The spec is the contract.** A client and a server share the OpenAPI spec, not a crate. A server that depends on
  the client crate for its models takes the client's manifest, features and version with it, and a release of one
  forces a release of the other. Each repository generates its own types from the spec and records the revision it
  used.
- **No server crate.** A published `openai-server-base` only serves someone who implements an OpenAI-compatible
  server, and nobody does yet. The generated server is a module of the mock (`src/generated`: `server`, `headers`,
  `models`), regenerated there. If a proxy or a gateway is built one day, it generates its own server from the spec
  the same way.
- **One repository, one crate:** [genai-rs/openai-mock-server](https://github.com/genai-rs/openai-mock-server).
  `scripts/generate.sh` runs the generator with `targets: ["server"]` and `serverDefaultMethods` into a scratch
  directory and keeps the three modules; the generated `lib.rs`, manifest and docs are not used. A script checks the
  hand-written `Cargo.toml` against the generated one (API features, dependencies).
- **Cargo features** are the generated API groups. The default set is the groups the mock scripts, which keeps a
  test build short; everything else answers `501` from a fallback.
- **The cost:** a crate that tests with both compiles two sets of OpenAI models, the client's and the mock's (the
  mock's only for its default groups). And the two can be on different spec revisions, as a real client and server
  are.

`modelsCrate` stays an option of the generator for an API where a shared models crate is wanted; genai-rs does not
use it.
