# Design: authentication for the server

Status: implemented, as `serverAuthentication`: the second part of slice 4 of
[targets and a server](targets-and-server.md). The [server guide](../guides/server.md#authentication-serverauthentication)
shows how to use it.

Before this, the server left authentication to axum middleware, which got the `HeaderMap` and nothing else. The spec
already says which schemes an operation takes (`security`, `components.securitySchemes`), and the server can read
those credentials. Deciding who the caller is, and what the caller may do, stays the implementation's job.

## What others do

- **[LiteLLM](https://github.com/BerriAI/litellm)'s proxy** (Python, FastAPI) runs one dependency,
  [`user_api_key_auth`](https://github.com/BerriAI/litellm/blob/907f91a4b65b204778097995ee810444fe02b1a1/litellm/proxy/auth/user_api_key_auth.py),
  on every route.
  - It reads a key from many places: `Authorization: Bearer`, the Azure, Anthropic and Google headers, a configurable
    header, and Google's `key` query parameter.
  - It accepts a master key, virtual keys (hashed, in its database), JWTs, OAuth2 introspection and a custom hook.
  - The result is a `UserAPIKeyAuth` (user, team, role, budget, allowed models), which the route handlers receive.
  - A key that is missing, unknown or blocked is a `401`. Something the key may not do is a `403`.
  - Authentication runs before the body is parsed, so a request that does not parse is attributed to its caller.
  - It does not read the spec's schemes: it is a gateway's own logic.
- **[Bifrost](https://docs.getbifrost.ai/features/governance/virtual-keys)** (Go) uses virtual keys (`sk-bf-...`).
  - They come in `x-bf-vk` or in the provider's usual headers (`Authorization`, `x-api-key`, `api-key`, ...).
  - A missing key is a `401`, an exhausted budget a `402`, a rate limit a `429`, a model it may not use a `403`.
- **openapi-generator's `rust-axum`** generates from the spec, as we do. Its
  [templates](https://github.com/OpenAPITools/openapi-generator/blob/master/modules/openapi-generator/src/main/resources/rust-axum/apis-mod.mustache)
  give each kind of scheme a trait with a `type Claims`: `extract_claims_from_header`, `extract_claims_from_cookie`,
  and `extract_claims_from_auth_header` (`Bearer` or `Basic`).
  - The handler tries the cookie, the header and the `Authorization` header, and the first `Some` wins.
  - With claims, an operation that has security gets a `claims: Self::Claims` parameter. An operation without
    security gets no parameter.
  - With no claims, the answer is a bare `401`: no body, no `WWW-Authenticate`, no reason (the extractors return an
    `Option`).
  - An optional requirement (`{}`) is not handled. The schemes of a requirement are not combined (AND), nor are the
    requirements (OR). Scopes are not passed on.

## The design

`serverAuthentication` (boolean, server only):

- **Credentials from the spec.** For each operation with security, the generated handler reads the credentials of
  the schemes that the operation's requirements name, as `Credential`s (a variant per scheme, with `Secret`
  values whose `Debug` is redacted):

  | Scheme | Read from |
  | --- | --- |
  | `http` `bearer`, `oauth2`, `openIdConnect` | `Authorization: Bearer <token>` |
  | `http` `basic` | `Authorization: Basic <base64>`, as a user name and a password |
  | `apiKey` | the header, query parameter or cookie it names |

  Other schemes (`mutualTLS`, `http` `digest`) are named in the requirements, but nothing is read for them: a warning
  when generating says so.
- **One trait decides.** `Authenticator` has `type Claims` and
  `authenticate(operation, credentials, requirements) -> Result<Claims, AuthError>`. The requirements are the
  spec's: alternatives (OR), each with its schemes (AND) and their scopes. The implementation looks up the key or
  checks the token, and returns who the caller is or why not: `AuthError::unauthenticated` (`401`) or
  `AuthError::forbidden` (`403`, for a scope the caller lacks).
- **Claims as a parameter (variant B).** Like `rust-axum`, an operation gets the claims only where the spec gives it
  security:

  | `security` of the operation | The trait method |
  | --- | --- |
  | none, or `[]` | `fn op(&self, request)`, as without the option |
  | required | `fn op(&self, claims: Self::Claims, request)` |
  | optional (`{}` among the requirements) | `fn op(&self, claims: Option<Self::Claims>, request)` |

  The other variant was to put the claims into an `http::Extensions` field of the request. It would not change any
  signature, but every operation would find its claims at run time (`extensions.get::<Claims>()`) and the compiler
  would not check them. The signatures change only with the option, so a server without it keeps them.
- **Before anything else.** A request without credentials, for an operation that requires them, is a `401` without
  calling `authenticate`. A request with credentials is authenticated before its parameters and body are decoded and
  validated, as LiteLLM does. For an optional operation, a request with no credentials gets `None`, and a request with
  bad credentials is still a `401`.
- **An answer of its own.** `Authenticator::unauthorized` answers an `AuthError`: by default its status, the JSON body
  `{"error": {"message": "..."}}` of the other errors, and on a `401` a `WWW-Authenticate` challenge for the spec's
  scheme (`Bearer`, else `Basic`). A mock implements it to answer as the real API does.

## What is left out

- The claims are not compared with the scopes by the generated code. The requirements are passed on, and the
  implementation decides, as LiteLLM's virtual keys and Bifrost's governance do.
- Webhooks sent by the server are the last part of slice 4.
