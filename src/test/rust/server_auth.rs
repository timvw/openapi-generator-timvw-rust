//! `serverAuthentication` end to end, on the crate generated from src/test/resources/server_auth.yaml with
//! `targets: [server]` and `serverAuthentication`. `ServerAuthTest` copies this file into that crate's tests/ and runs
//! cargo. The router is called in process: each operation answers with the claims it was given.

use axum::body::Body;
use http::{Request, StatusCode};
use http_body_util::BodyExt;
use tower::ServiceExt;

use auth_fixture::server::auth_api::{
    GetBasicRequest, GetBasicResponse, GetFeedRequest, GetFeedResponse, GetKeyRequest, GetKeyResponse, GetMeRequest,
    GetMeResponse, GetSessionRequest, GetSessionResponse, HealthRequest, HealthResponse, PostAdminRequest,
    PostAdminResponse,
};
use auth_fixture::server::{
    self, AuthApi, AuthError, Authenticator, Credential, ErrorHandler, Requirement, ServerError,
};

/// The caller, as the authenticator knows it.
#[derive(Clone, Debug)]
struct Caller(String);

#[derive(Clone)]
struct Api;

impl ErrorHandler for Api {}

impl Authenticator for Api {
    type Claims = Caller;

    async fn authenticate(
        &self,
        operation: &'static str,
        credentials: Vec<Credential>,
        requirements: &'static [&'static [Requirement]],
    ) -> Result<Caller, AuthError> {
        // one alternative whose every scheme has a valid credential
        for alternative in requirements {
            let mut who = Vec::new();
            for requirement in alternative.iter() {
                let Some(credential) = credentials.iter().find(|c| c.scheme() == requirement.scheme) else {
                    break;
                };
                let name = match credential {
                    Credential::BearerAuth(token) | Credential::HeaderKey(token) | Credential::QueryKey(token)
                    | Credential::CookieKey(token) => token.expose_secret().to_owned(),
                    Credential::Oauth(token) => {
                        if !requirement.scopes.iter().all(|scope| token.expose_secret().contains(scope)) {
                            return Err(AuthError::forbidden(format!("{operation} needs {:?}", requirement.scopes)));
                        }
                        token.expose_secret().to_owned()
                    }
                    Credential::BasicAuth { username, password } => {
                        if password.expose_secret() != "secret" {
                            return Err(AuthError::unauthenticated("wrong password"));
                        }
                        username.clone()
                    }
                    _ => return Err(AuthError::unauthenticated("an unknown scheme")),
                };
                if name == "bad" {
                    return Err(AuthError::unauthenticated("Incorrect API key provided"));
                }
                who.push(name);
            }
            if !alternative.is_empty() && who.len() == alternative.len() {
                return Ok(Caller(who.join("+")));
            }
        }
        Err(AuthError::unauthenticated(format!("{operation}: no requirement is met")))
    }
}

impl AuthApi for Api {
    async fn get_me(&self, claims: Caller, _: GetMeRequest) -> Result<GetMeResponse, ServerError> {
        Ok(GetMeResponse::Status200(claims.0))
    }

    async fn health(&self, _: HealthRequest) -> Result<HealthResponse, ServerError> {
        Ok(HealthResponse::Status200("up".to_owned()))
    }

    async fn get_feed(&self, claims: Option<Caller>, _: GetFeedRequest) -> Result<GetFeedResponse, ServerError> {
        Ok(GetFeedResponse::Status200(claims.map_or("anonymous".to_owned(), |c| c.0)))
    }

    async fn get_key(&self, claims: Caller, request: GetKeyRequest) -> Result<GetKeyResponse, ServerError> {
        Ok(GetKeyResponse::Status200(format!("{} {}", claims.0, request.id)))
    }

    async fn get_session(&self, claims: Caller, _: GetSessionRequest) -> Result<GetSessionResponse, ServerError> {
        Ok(GetSessionResponse::Status200(claims.0))
    }

    async fn post_admin(&self, claims: Caller, request: PostAdminRequest) -> Result<PostAdminResponse, ServerError> {
        Ok(PostAdminResponse::Status200(format!("{}: {}", claims.0, request.body.text)))
    }

    async fn get_basic(&self, claims: Caller, _: GetBasicRequest) -> Result<GetBasicResponse, ServerError> {
        Ok(GetBasicResponse::Status200(claims.0))
    }
}

async fn call(request: Request<Body>) -> (StatusCode, http::HeaderMap, String) {
    let response = server::router(Api).oneshot(request).await.unwrap();
    let status = response.status();
    let headers = response.headers().clone();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (status, headers, String::from_utf8(bytes.to_vec()).unwrap())
}

fn get(uri: &str, headers: &[(&str, &str)]) -> Request<Body> {
    let mut request = Request::get(uri);
    for (name, value) in headers {
        request = request.header(*name, *value);
    }
    request.body(Body::empty()).unwrap()
}

#[tokio::test]
async fn a_required_bearer_token_is_given_as_claims() {
    let (status, _, body) = call(get("/me", &[("authorization", "Bearer tim")])).await;
    assert_eq!((status, body.as_str()), (StatusCode::OK, "tim"));
    // the scheme in any case
    let (status, _, body) = call(get("/me", &[("authorization", "bearer tim")])).await;
    assert_eq!((status, body.as_str()), (StatusCode::OK, "tim"));
}

#[tokio::test]
async fn no_credentials_is_a_401_with_a_challenge() {
    let (status, headers, body) = call(get("/me", &[])).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert_eq!(headers["www-authenticate"], "Bearer");
    let body: serde_json::Value = serde_json::from_str(&body).unwrap();
    assert_eq!(body["error"]["message"], "this operation requires credentials, and the request has none");
    // another scheme's credential is not one of this operation's
    let (status, _, _) = call(get("/me", &[("x-api-key", "tim")])).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn credentials_the_authenticator_turns_down_are_a_401() {
    let (status, _, body) = call(get("/me", &[("authorization", "Bearer bad")])).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert!(body.contains("Incorrect API key provided"), "{body}");
}

#[tokio::test]
async fn an_operation_without_security_is_not_authenticated() {
    let (status, _, body) = call(get("/health", &[("authorization", "Bearer bad")])).await;
    assert_eq!((status, body.as_str()), (StatusCode::OK, "up"));
}

#[tokio::test]
async fn optional_security_gives_none_without_credentials() {
    let (status, _, body) = call(get("/feed", &[])).await;
    assert_eq!((status, body.as_str()), (StatusCode::OK, "anonymous"));
    let (status, _, body) = call(get("/feed", &[("authorization", "Bearer tim")])).await;
    assert_eq!((status, body.as_str()), (StatusCode::OK, "tim"));
    // credentials that are bad are bad, also where none are needed
    let (status, _, _) = call(get("/feed", &[("authorization", "Bearer bad")])).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn an_api_key_in_a_header_or_the_query() {
    let (status, _, body) = call(get("/keys/7", &[("x-api-key", "header")])).await;
    assert_eq!((status, body.as_str()), (StatusCode::OK, "header 7"));
    let (status, _, body) = call(get("/keys/7?api_key=query", &[])).await;
    assert_eq!((status, body.as_str()), (StatusCode::OK, "query 7"));
    // authenticated before the path is decoded
    let (status, _, _) = call(get("/keys/x", &[])).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    let (status, _, _) = call(get("/keys/x?api_key=query", &[])).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn an_api_key_in_a_header_and_a_cookie() {
    let (status, _, body) =
        call(get("/session", &[("x-api-key", "key"), ("cookie", "theme=dark; session=abc")])).await;
    assert_eq!((status, body.as_str()), (StatusCode::OK, "key+abc"));
    let (status, _, body) = call(get("/session", &[("x-api-key", "key")])).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert!(body.contains("no requirement is met"), "{body}");
}

#[tokio::test]
async fn a_scope_the_caller_lacks_is_a_403() {
    let post = |token: &str| {
        Request::post("/admin")
            .header("authorization", format!("Bearer {token}"))
            .header("content-type", "application/json")
            .body(Body::from(r#"{"text": "hello"}"#))
            .unwrap()
    };
    let (status, _, body) = call(post("tim:admin:write")).await;
    assert_eq!((status, body.as_str()), (StatusCode::OK, "tim:admin:write: hello"));
    let (status, headers, body) = call(post("tim:read")).await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    assert!(!headers.contains_key("www-authenticate"));
    assert!(body.contains(r#"post_admin needs [\"admin:write\"]"#), "{body}");
}

#[tokio::test]
async fn http_basic_gives_the_user_name_and_password() {
    // tim:secret
    let (status, _, body) = call(get("/basic", &[("authorization", "Basic dGltOnNlY3JldA==")])).await;
    assert_eq!((status, body.as_str()), (StatusCode::OK, "tim"));
    // tim:wrong
    let (status, _, _) = call(get("/basic", &[("authorization", "Basic dGltOndyb25n")])).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    let (status, _, _) = call(get("/basic", &[("authorization", "Basic not base64!")])).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[test]
fn a_secret_does_not_show_in_debug() {
    let credential = Credential::BearerAuth(server::Secret::new("sk-123"));
    assert_eq!(format!("{credential:?}"), "BearerAuth(Secret([redacted]))");
    assert_eq!(credential.scheme(), "bearerAuth");
}
