//! `serverValidation` end to end, on the crate generated from src/test/resources/server_validation.yaml with
//! `targets: [server]`, `serverValidation` and `serverDefaultMethods`. `ServerValidationTest` copies this file into
//! that crate's tests/ and runs cargo. The router is called in process: a request that breaks a validation keyword is
//! answered with a `400` listing every error, one that does not reaches the implementation (which implements nothing:
//! a `501`).

use axum::body::Body;
use http::{Request, StatusCode};
use http_body_util::BodyExt;
use tower::ServiceExt;

use validation_fixture::models::validation::{Rule, Validate};
use validation_fixture::server::items_api::{ListItemsRequest, SearchItemsForm};
use validation_fixture::server::{self, ErrorHandler, ItemsApi, RequestError};

#[derive(Clone)]
struct Nothing;

impl ErrorHandler for Nothing {}
impl ItemsApi for Nothing {}

/// An error handler of its own: what it is given for a request that breaks a keyword.
#[derive(Clone)]
struct Teapot;

impl ErrorHandler for Teapot {
    fn invalid_request(&self, error: RequestError) -> axum::response::Response {
        let errors = error.validation_errors().map_or(0, |errors| errors.len());
        let text = format!("{} {} {:?} {errors}: {error}", error.status(), error.location(), error.name());
        axum::response::IntoResponse::into_response((StatusCode::IM_A_TEAPOT, text))
    }
}
impl ItemsApi for Teapot {}

async fn call(request: Request<Body>) -> (StatusCode, serde_json::Value) {
    let response = server::router(Nothing).oneshot(request).await.unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (status, serde_json::from_slice(&bytes).unwrap_or(serde_json::Value::Null))
}

fn get(uri: &str) -> Request<Body> {
    Request::get(uri).body(Body::empty()).unwrap()
}

fn post(uri: &str, content_type: &str, body: &str) -> Request<Body> {
    Request::post(uri).header("content-type", content_type).body(Body::from(body.to_owned())).unwrap()
}

#[tokio::test]
async fn query_parameters_are_checked() {
    let (status, _) = call(get("/items?limit=10&tag=ab&tag=cd")).await;
    assert_eq!(status, StatusCode::NOT_IMPLEMENTED);

    let (status, body) = call(get("/items?limit=101&tag=a&tag=bc&tag=de")).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    let error = &body["error"];
    assert_eq!(error["location"], "query");
    assert_eq!(error["name"], "limit");
    assert_eq!(
        error["message"],
        "query.limit: 101 breaks maximum 100; query.tag: 3 items breaks maxItems 2; query.tag[0]: length 1 breaks minLength 2"
    );
    let errors = error["errors"].as_array().unwrap();
    assert_eq!(errors.len(), 3);
    assert_eq!(errors[0]["path"], "query.limit");
    assert_eq!(errors[0]["rule"], "maximum 100");
    assert_eq!(errors[2]["path"], "query.tag[0]");
    assert_eq!(errors[2]["message"], "query.tag[0]: length 1 breaks minLength 2");

    // a value that does not parse is still a decoding error, without "errors"
    let (status, body) = call(get("/items?limit=x")).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert!(body["error"].get("errors").is_none(), "{body}");
}

#[tokio::test]
async fn a_path_parameter_is_checked_against_its_pattern() {
    let (status, _) = call(get("/items/ABC")).await;
    assert_eq!(status, StatusCode::NOT_IMPLEMENTED);
    let (status, body) = call(get("/items/abcd")).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["error"]["location"], "path");
    assert_eq!(body["error"]["name"], "code");
    assert_eq!(body["error"]["errors"][0]["rule"], "pattern \"^[A-Z]{3}$\"");
}

#[tokio::test]
async fn a_json_body_is_checked_with_its_models() {
    let (status, _) = call(post("/items", "application/json", r#"{"name": "lamp", "tags": [{"label": "desk"}]}"#)).await;
    assert_eq!(status, StatusCode::NOT_IMPLEMENTED);

    let (status, body) = call(post(
        "/items",
        "application/json",
        r#"{"name": "", "tags": [{"label": "ok"}, {"label": "too long"}]}"#,
    ))
    .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    let error = &body["error"];
    assert_eq!(error["location"], "body");
    assert!(error.get("name").is_none(), "{body}");
    let paths: Vec<&str> = error["errors"].as_array().unwrap().iter().map(|e| e["path"].as_str().unwrap()).collect();
    assert_eq!(paths, ["body.name", "body.tags[1].label"]);
}

#[tokio::test]
async fn form_fields_are_checked() {
    let (status, _) = call(post("/search", "application/x-www-form-urlencoded", "query=lamp&page=2")).await;
    assert_eq!(status, StatusCode::NOT_IMPLEMENTED);
    let (status, body) = call(post("/search", "application/x-www-form-urlencoded", "query=lamps!&page=0")).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    let paths: Vec<&str> =
        body["error"]["errors"].as_array().unwrap().iter().map(|e| e["path"].as_str().unwrap()).collect();
    assert_eq!(paths, ["body.query", "body.page"]);
}

#[tokio::test]
async fn each_media_type_of_a_body_is_checked() {
    let (status, body) = call(post("/import", "application/json", r#"{"name": "a name that is too long"}"#)).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["error"]["errors"][0]["path"], "body.name");
    let (status, body) =
        call(post("/import", "application/x-www-form-urlencoded", "name=a+name+that+is+too+long")).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["error"]["errors"][0]["path"], "body.name");
    let (status, _) = call(post("/import", "application/x-www-form-urlencoded", "name=lamp")).await;
    assert_eq!(status, StatusCode::NOT_IMPLEMENTED);
}

#[tokio::test]
async fn an_operation_without_keywords_is_not_checked() {
    let (status, _) = call(get("/health")).await;
    assert_eq!(status, StatusCode::NOT_IMPLEMENTED);
}

#[tokio::test]
async fn an_error_handler_is_given_the_errors() {
    let response = server::router(Teapot).oneshot(get("/items?limit=0")).await.unwrap();
    assert_eq!(response.status(), StatusCode::IM_A_TEAPOT);
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    assert_eq!(
        std::str::from_utf8(&bytes).unwrap(),
        "400 Bad Request query Some(\"limit\") 1: query.limit: 0 breaks minimum 1"
    );
}

#[test]
fn the_request_structs_implement_validate() {
    let request = ListItemsRequest { limit: Some(0), tag: None, headers: http::HeaderMap::new() };
    let errors = request.validate().unwrap_err();
    assert_eq!(errors.iter().next().unwrap().rule, Rule::Minimum(1.0));
    let form = SearchItemsForm { query: "lamp".to_owned(), page: Some(1) };
    assert!(form.validate().is_ok());
}
