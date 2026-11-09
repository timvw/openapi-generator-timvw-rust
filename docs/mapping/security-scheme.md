# Security Scheme Object

Rules for the [Security Scheme Object](https://spec.openapis.org/oas/v3.0.3#security-scheme-object). Which schemes an
operation sends is [`operation.security`](operation.md#operationsecurity). Every credential is a field of
`Configuration`, empty by default, and is sent only when set.

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OperationMappingTest` renders
`src/test/resources/operation_mapping.yaml` without options.

### `securityScheme.type.http-bearer`

| | |
| --- | --- |
| OpenAPI | `type: http, scheme: bearer` |
| Rust | `configuration.bearer_access_token: Option<String>`, sent as `Authorization: Bearer <token>` |
| Library | `reqwest::RequestBuilder::bearer_auth` |
| Tests | [`OperationMappingTest#eachOperationSendsTheCredentialsOfItsSecurity`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L171) |

### `securityScheme.type.http-basic`

| | |
| --- | --- |
| OpenAPI | `type: http, scheme: basic` |
| Rust | `configuration.basic_auth: Option<BasicAuth>`, `BasicAuth = (String, Option<String>)` (user, password) |
| Library | `reqwest::RequestBuilder::basic_auth` |
| Tests | [`OperationMappingTest#eachOperationSendsTheCredentialsOfItsSecurity`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L171) |

### `securityScheme.type.api-key`

| | |
| --- | --- |
| OpenAPI | `type: apiKey, in: header, name: <header>` |
| Rust | `configuration.api_key: Option<ApiKey { prefix: Option<String>, key: String }>`, sent in the scheme's header as `<prefix> <key>` or `<key>`. `Debug` redacts the key |
| Tests | [`OperationMappingTest#eachOperationSendsTheCredentialsOfItsSecurity`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L171) |

### `securityScheme.type.oauth2`

| | |
| --- | --- |
| OpenAPI | `type: oauth2` (any flow) |
| Rust | `configuration.oauth_access_token: Option<String>`, sent as a bearer token. Getting or refreshing the token is up to the caller |
| Tests | [`OperationMappingTest#eachOperationSendsTheCredentialsOfItsSecurity`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L171) |

### `oauthFlows.scopes`

| | |
| --- | --- |
| OpenAPI | the `flows` of an OAuth 2 scheme (`authorizationUrl`, `tokenUrl`, `scopes`), and the scopes an operation's `security` asks for |
| Rust | nothing: the client sends `oauth_access_token` as it is, and does not get, refresh or check the scopes of a token |
| Tests | [`OtherObjectsTest#oauthFlowsAndScopesAreIgnored`](../../src/test/java/be/timvw/openapi/rust/OtherObjectsTest.java#L124) |
