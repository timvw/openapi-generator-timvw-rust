# Security Scheme Object

Rules for the [Security Scheme Object](https://spec.openapis.org/oas/v3.0.3#security-scheme-object). Which schemes an
operation sends is [`operation.security`](operation.md#operationsecurity). Every credential is a field of
`Configuration`, empty by default, and is sent only when set. The secrets
(the tokens, the basic-auth password and the api key) are `SecretString`s ([secrecy](https://docs.rs/secrecy),
re-exported as `configuration::SecretString`): `Some("...".into())` sets one, `Debug` redacts them, and the generated
code exposes them only to build the header, query parameter or cookie, sending headers marked sensitive.
`CredentialsTest` renders `src/test/resources/credentials.yaml` and sends each scheme to a local server.

Tests are in `src/test/java/be/timvw/openapi/rust/`; a test is named `Class#method`. `OperationMappingTest` renders
`src/test/resources/operation_mapping.yaml` without options.

### `securityScheme.type.http-bearer`

| | |
| --- | --- |
| OpenAPI | `type: http, scheme: bearer` |
| Rust | `configuration.bearer_access_token: Option<SecretString>`, sent as `Authorization: Bearer <token>` |
| Library | `timvw_rust_runtime::RequestBuilder::bearer_auth` |
| Tests | [`OperationMappingTest#eachOperationSendsTheCredentialsOfItsSecurity`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L178), [`CredentialsTest#theSecretsAreSecretStrings`](../../src/test/java/be/timvw/openapi/rust/CredentialsTest.java#L63), [`#theyAreExposedOnlyIntoSensitiveHeaders`](../../src/test/java/be/timvw/openapi/rust/CredentialsTest.java#L73), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/CredentialsTest.java#L86) |

### `securityScheme.type.http-basic`

| | |
| --- | --- |
| OpenAPI | `type: http, scheme: basic` |
| Rust | `configuration.basic_auth: Option<BasicAuth>`, `BasicAuth = (String, Option<SecretString>)` (user, password) |
| Library | `timvw_rust_runtime::RequestBuilder::basic_auth` |
| Tests | [`OperationMappingTest#eachOperationSendsTheCredentialsOfItsSecurity`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L178), [`CredentialsTest#theSecretsAreSecretStrings`](../../src/test/java/be/timvw/openapi/rust/CredentialsTest.java#L63), [`#theyAreExposedOnlyIntoSensitiveHeaders`](../../src/test/java/be/timvw/openapi/rust/CredentialsTest.java#L73), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/CredentialsTest.java#L86) |

### `securityScheme.type.api-key`

| | |
| --- | --- |
| OpenAPI | `type: apiKey, in: header`, `in: query` or `in: cookie`, `name: <name>` |
| Rust | `configuration.api_key: Option<ApiKey { prefix: Option<String>, key: SecretString }>`, sent as `<prefix> <key>` or `<key>` in the scheme's header, query parameter, or cookie. A cookie goes into the operation's one `Cookie` header, after its cookie parameters, percent-encoded as they are ([`parameter.in.cookie`](parameter.md#parameterincookie)). The single configured `api_key` is sent under every api-key scheme an operation's security alternatives list (header, query and cookie alike). `Debug` redacts the key. A header key goes out with `RequestBuilder::sensitive_header`, and so does the `Cookie` header |
| Tests | [`OperationMappingTest#eachOperationSendsTheCredentialsOfItsSecurity`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L178), [`CookieParamsTest#eachCookieIsAPairOfOneHeader`](../../src/test/java/be/timvw/openapi/rust/CookieParamsTest.java#L70), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/CookieParamsTest.java#L114), [`CredentialsTest#theSecretsAreSecretStrings`](../../src/test/java/be/timvw/openapi/rust/CredentialsTest.java#L63), [`#theyAreExposedOnlyIntoSensitiveHeaders`](../../src/test/java/be/timvw/openapi/rust/CredentialsTest.java#L73), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/CredentialsTest.java#L86) |

### `securityScheme.type.oauth2`

| | |
| --- | --- |
| OpenAPI | `type: oauth2` (any flow) |
| Rust | `configuration.oauth_access_token: Option<SecretString>`, sent as a bearer token. Getting or refreshing the token is up to the caller |
| Tests | [`OperationMappingTest#eachOperationSendsTheCredentialsOfItsSecurity`](../../src/test/java/be/timvw/openapi/rust/OperationMappingTest.java#L178), [`CredentialsTest#theSecretsAreSecretStrings`](../../src/test/java/be/timvw/openapi/rust/CredentialsTest.java#L63), [`#theyAreExposedOnlyIntoSensitiveHeaders`](../../src/test/java/be/timvw/openapi/rust/CredentialsTest.java#L73), [`#whatItDoesAtRunTime`](../../src/test/java/be/timvw/openapi/rust/CredentialsTest.java#L86) |

### `oauthFlows.scopes`

| | |
| --- | --- |
| OpenAPI | the `flows` of an OAuth 2 scheme (`authorizationUrl`, `tokenUrl`, `scopes`), and the scopes an operation's `security` asks for |
| Rust | nothing: the client sends `oauth_access_token` as it is, and does not get, refresh or check the scopes of a token |
| Tests | [`OtherObjectsTest#oauthFlowsAndScopesAreIgnored`](../../src/test/java/be/timvw/openapi/rust/OtherObjectsTest.java#L124) |
