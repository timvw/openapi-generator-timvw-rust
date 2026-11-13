//! Every Rust example of README.md and docs/guides/*.md, on the crate generated from
//! src/test/resources/docs_examples.yaml (`my_client`, with `responseFunctions`). `DocsExamplesTest` checks that each
//! example of those files appears here, line for line, copies this file into the crate's tests/ and runs cargo test
//! and clippy on it. Examples that send requests are compiled, not run; the others run as tests.
#![allow(unused, clippy::let_unit_value, clippy::needless_return, clippy::unnecessary_literal_unwrap)]

use my_client::apis::configuration::Configuration;
use my_client::apis::{items_api, ApiResponse, Error, ResponseContent};
use my_client::models;

type BoxError = Box<dyn std::error::Error>;

fn configuration() -> Configuration {
    Configuration::new()
}

fn new_item() -> models::NewItem {
    models::NewItem::new("Lamp".to_owned())
}

fn not_found() -> ResponseContent<items_api::GetItemError> {
    ResponseContent {
        status: timvw_rust_runtime::StatusCode::NOT_FOUND,
        content: r#"{"detail":"no such item"}"#.to_owned(),
        entity: Some(items_api::GetItemError::Status404(models::Problem::new("no such item".to_owned()))),
        headers: timvw_rust_runtime::header::HeaderMap::new(),
    }
}

fn rejected() -> ResponseContent<items_api::CreateItemError> {
    ResponseContent {
        status: timvw_rust_runtime::StatusCode::BAD_REQUEST,
        content: "bad input".to_owned(),
        entity: Some(items_api::CreateItemError::Status400("bad input".to_owned())),
        headers: timvw_rust_runtime::header::HeaderMap::new(),
    }
}

/// README.md
mod readme {
    use super::*;

    async fn calling() -> Result<(), BoxError> {
        use my_client::apis::configuration::Configuration;
        use my_client::apis::items_api;

        let mut configuration = Configuration::new();
        configuration.base_path = "https://api.example.com/v1".to_owned();
        configuration.bearer_access_token = Some("secret".into());

        let page = items_api::list_items()
            .configuration(&configuration)
            .limit(10)
            .call()
            .await?;
        for item in &page.data {
            println!("{} {}", item.id, item.name);
        }

        use my_client::models;

        let new_item = models::NewItem::builder()
            .name("Lamp".to_owned())
            .tags(vec!["light".to_owned()])
            .build();
        let item = items_api::create_item()
            .configuration(&configuration)
            .new_item(&new_item)
            .call()
            .await?;
        Ok(())
    }

    #[test]
    fn layers() {
        use std::time::Duration;
        use timvw_rust_runtime::retry::RetryPolicy;
        use timvw_rust_runtime::tower::retry::RetryLayer;
        use timvw_rust_runtime::{Client, ReqwestService};

        let mut configuration = Configuration::new(); // retries with RetryPolicy::default()
        let policy = RetryPolicy::new(5).max_delay(Duration::from_secs(30));
        configuration.client = Client::from_service(ReqwestService::default()).layer(RetryLayer::new(policy));
    }

    #[test]
    fn unions() -> Result<(), BoxError> {
        let event: models::Event = serde_json::from_str(r#"{"type":"deleted","id":"item-1"}"#)?;
        match event {
            models::Event::Created(created) => println!("created {}", created.item.name),
            models::Event::Deleted(deleted) => println!("deleted {}", deleted.id),
            models::Event::Unknown(value) => println!("a newer event: {value}"),
        }
        let text = models::NewItem::builder()
            .name("Lamp".to_owned())
            .content("a short text".into())
            .build();
        assert_eq!(text.content, Some(models::Content::Text("a short text".to_owned())));
        Ok(())
    }

    async fn errors() -> Result<(), BoxError> {
        let configuration = configuration();
        use my_client::apis::Error;

        match items_api::get_item().configuration(&configuration).id("item-1").call().await {
            Ok(item) => println!("{}", item.name),
            Err(Error::ResponseError(response)) => {
                let request_id = response.headers.get("x-request-id");
                match &response.entity {
                    Some(items_api::GetItemError::Status404(problem)) => println!("not found: {}", problem.detail),
                    Some(items_api::GetItemError::Status5XX(problem)) => println!("server error: {}", problem.detail),
                    _ => println!("{}: {} ({request_id:?})", response.status, response.content),
                }
            }
            Err(other) => return Err(other.into()),
        }
        Ok(())
    }

    #[test]
    fn validation() {
        use my_client::models::validation::Validate;

        let draft = models::NewItem::builder().name(String::new()).price(-1.0).build();
        let errors = draft.validate().unwrap_err();
        assert_eq!(errors.len(), 2);
        for error in &errors {
            println!("{}: {}", error.path, error.rule);
        }
        assert_eq!(errors.to_string(), "name: length 0 breaks minLength 1\nprice: -1 breaks minimum 0");
    }
}

/// docs/guides/errors.md
mod errors {
    use super::*;

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

    async fn text_errors() -> Result<(), BoxError> {
        let configuration = configuration();
        let new_item = new_item();
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
        Ok(())
    }

    fn retry_after(response: &ResponseContent<items_api::CreateItemError>) -> Option<std::time::Duration> {
        let seconds = response.headers.get(timvw_rust_runtime::header::RETRY_AFTER)?.to_str().ok()?;
        seconds.parse().ok().map(std::time::Duration::from_secs)
    }

    #[test]
    fn headers() {
        let mut response = rejected();
        response.headers.insert(timvw_rust_runtime::header::RETRY_AFTER, "30".parse().unwrap());
        assert_eq!(retry_after(&response), Some(std::time::Duration::from_secs(30)));
    }

    #[test]
    fn boxing() {
        let result: Result<models::Item, Error<items_api::GetItemError>> = Err(Error::ResponseError(Box::new(not_found())));
        if let Err(Error::ResponseError(response)) = result {
            let content: ResponseContent<items_api::GetItemError> = *response;
            println!("{}", content.status);
        }

        let error: Error<items_api::GetItemError> = Error::ResponseError(Box::new(ResponseContent {
            status: timvw_rust_runtime::StatusCode::NOT_FOUND,
            content: r#"{"detail":"no such item"}"#.to_owned(),
            entity: Some(items_api::GetItemError::Status404(models::Problem::new("no such item".to_owned()))),
            headers: timvw_rust_runtime::header::HeaderMap::new(),
        }));
        assert_eq!(error.to_string(), "error in response: status code 404 Not Found");
    }

    #[test]
    fn logging() {
        let result: Result<models::Item, Error<items_api::GetItemError>> = Err(Error::ResponseError(Box::new(not_found())));
        if let Err(error) = &result {
            match error {
                Error::ResponseError(response) => {
                    let request_id = response.headers.get("x-request-id").and_then(|value| value.to_str().ok());
                    eprintln!("{error} (request {})", request_id.unwrap_or("-"));
                }
                other => eprintln!("{other}"),
            }
        }
    }
}

/// docs/guides/validation.md
mod validation {
    use super::*;
    use my_client::models::validation::Validate;

    #[test]
    fn using_it() {
        use my_client::models::{self, validation::Validate};

        let new_item = models::NewItem::builder()
            .name("Lamp".to_owned())
            .sku("LMP-0001".to_owned())
            .price(19.5)
            .tags(vec!["light".to_owned(), "desk".to_owned()])
            .build();
        assert!(new_item.validate().is_ok());

        let bad = models::NewItem::builder()
            .name("Lamp".to_owned())
            .tags(vec!["light".to_owned(), "light".to_owned()])
            .build();
        let errors = bad.validate().unwrap_err();
        assert_eq!(errors.len(), 1);
        let error = errors.iter().next().unwrap();
        assert_eq!(error.path, "tags");
        assert_eq!(error.rule, models::validation::Rule::UniqueItems);
        assert_eq!(errors.to_string(), "tags: items 0 and 1 are equal breaks uniqueItems");

        let worse = models::NewItem::builder()
            .name(String::new())
            .sku("lmp-1".to_owned())
            .tags(vec!["light".to_owned(), "light".to_owned()])
            .build();
        let errors: Vec<models::validation::ValidationError> = worse.validate().unwrap_err().into_vec();
        let paths: Vec<&str> = errors.iter().map(|error| error.path.as_str()).collect();
        assert_eq!(paths, ["name", "sku", "tags"]);
    }

    #[test]
    fn the_other_rules() {
        let sku = models::NewItem::builder().name("Lamp".to_owned()).sku("lmp-1".to_owned()).build();
        assert_eq!(sku.validate().unwrap_err().to_string(), r#"sku: "lmp-1" breaks pattern "^[A-Z]{3}-[0-9]{4}$""#);
        let price = models::NewItem::builder().name("Lamp".to_owned()).price(-1.0).build();
        assert_eq!(price.validate().unwrap_err().to_string(), "price: -1 breaks minimum 0");
        let tags = models::NewItem::builder().name("Lamp".to_owned()).tags((0..11).map(|i| i.to_string()).collect()).build();
        assert_eq!(tags.validate().unwrap_err().to_string(), "tags: 11 items breaks maxItems 10");
    }

    #[test]
    fn by_hand() {
        use my_client::models::validation::{Path, Rule, Validate, ValidationError};

        struct Order {
            note: String,
            items: Vec<models::NewItem>,
        }

        impl Validate for Order {
            fn validate_into(&self, path: &Path<'_>, errors: &mut Vec<ValidationError>) {
                let length = self.note.chars().count() as u64;
                if length > 100 {
                    let mut error = ValidationError::new(Rule::MaxLength(100), format!("length {length}"));
                    error.path = path.field("note").to_string();
                    errors.push(error);
                }
                self.items.validate_into(&path.field("items"), errors);
            }
        }

        let order = Order { note: "x".repeat(101), items: vec![models::NewItem::new(String::new())] };
        let errors = order.validate().unwrap_err();
        assert_eq!(errors.to_string(), "note: length 101 breaks maxLength 100\nitems[0].name: length 0 breaks minLength 1");
    }

    async fn create(configuration: &Configuration, new_item: &models::NewItem) -> Result<models::Item, Box<dyn std::error::Error>> {
        new_item.validate()?;
        Ok(items_api::create_item().configuration(configuration).new_item(new_item).call().await?)
    }
}

/// docs/guides/response-functions.md
mod response_functions {
    use super::*;

    async fn intro(configuration: Configuration) -> Result<(), Error<items_api::GetItemError>> {
        let item: models::Item = items_api::get_item()
            .configuration(&configuration)
            .id("42")
            .call()
            .await?;
        Ok(())
    }

    async fn decoding(configuration: Configuration) -> Result<(), Error<items_api::GetItemError>> {
        let response = items_api::get_item_response()
            .configuration(&configuration)
            .id("42")
            .call()
            .await?;
        let item: models::Item = response.json()?;

        use serde::Deserialize;

        #[derive(Deserialize)]
        struct ItemSummary {
            id: String,
            name: String,
        }

        let summary: ItemSummary = response.json_as()?;
        Ok(())
    }

    async fn borrowed(configuration: Configuration) -> Result<(), Error<items_api::GetItemError>> {
        let response = items_api::get_item_response().configuration(&configuration).id("42").call().await?;
        use std::borrow::Cow;
        use serde::Deserialize;

        #[derive(Deserialize)]
        struct ItemNames<'a> {
            id: &'a str,                 // fails if the JSON string has an escape (\", \n, \u...)
            #[serde(borrow)]
            name: Cow<'a, str>,          // borrows when it can, copies when it has to
        }

        let names: ItemNames = response.json_borrowed()?;   // lives as long as `response`
        println!("{} {}", names.id, names.name);

        let body: &str = response.text();
        Ok(())
    }

    async fn headers(configuration: Configuration) -> Result<(), BoxError> {
        let response = items_api::get_item_response()
            .configuration(&configuration)
            .id("42")
            .call()
            .await?;

        if response.status == timvw_rust_runtime::StatusCode::NON_AUTHORITATIVE_INFORMATION {
            // ...
        }
        let request_id = response
            .headers
            .get("x-request-id")
            .and_then(|value| value.to_str().ok());
        let item: models::Item = response.json()?;

        let deleted = items_api::delete_item_response()
            .configuration(&configuration)
            .id("42")
            .call()
            .await?;
        assert_eq!(deleted.status, timvw_rust_runtime::StatusCode::NO_CONTENT);
        let location = deleted.headers.get(timvw_rust_runtime::header::LOCATION);
        Ok(())
    }

    async fn typed_headers(configuration: Configuration) -> Result<(), BoxError> {
        let response = items_api::get_item_response()
            .configuration(&configuration)
            .id("42")
            .call()
            .await?;
        let headers = response.typed_headers::<items_api::GetItemResponseHeaders>();
        if let Some(request_id) = &headers.x_request_id {
            println!("request {request_id}");
        }
        let annotated: items_api::GetItemResponseHeaders = response.typed_headers();
        let map = timvw_rust_runtime::header::HeaderMap::new();
        assert_eq!(items_api::GetItemResponseHeaders::from_headers(&map), Default::default());
        Ok(())
    }

    async fn error_headers(configuration: Configuration) -> Result<(), Error<items_api::CreateItemError>> {
        let new_item = new_item();
        match items_api::create_item()
            .configuration(&configuration)
            .new_item(&new_item)
            .call()
            .await
        {
            Err(Error::ResponseError(content)) => {
                let headers = content.typed_headers::<items_api::CreateItemErrorHeaders>();
                if let Some(seconds) = headers.retry_after {
                    println!("retry in {seconds} s");
                }
            }
            other => {
                let item = other?;
            }
        }
        Ok(())
    }

    #[test]
    fn error_headers_of_a_response_content() {
        let mut content = rejected();
        content.headers.insert(timvw_rust_runtime::header::RETRY_AFTER, "30".parse().unwrap());
        assert_eq!(content.typed_headers::<items_api::CreateItemErrorHeaders>().retry_after, Some(30));
    }

    async fn errors(configuration: Configuration) -> Result<(), Error<items_api::GetItemError>> {
        use my_client::apis::Error;

        match items_api::get_item_response()
            .configuration(&configuration)
            .id("42")
            .call()
            .await
        {
            Ok(response) => {
                let item: models::Item = response.json()?;
            }
            Err(Error::ResponseError(content)) => match content.entity {
                Some(items_api::GetItemError::Status404(problem)) => println!("not found: {}", problem.detail),
                _ => println!("status {}: {}", content.status, content.content),
            },
            Err(other) => return Err(other),
        }
        Ok(())
    }

    #[test]
    fn in_tests() -> Result<(), serde_json::Error> {
        let response: ApiResponse<models::Item> = ApiResponse::new(
            timvw_rust_runtime::StatusCode::OK,
            timvw_rust_runtime::header::HeaderMap::new(),
            r#"{"id":"42","name":"first"}"#.to_owned(),
        );
        assert_eq!(response.json()?.name, "first");
        Ok(())
    }

    #[test]
    fn path_to_error() -> Result<(), BoxError> {
        let response: ApiResponse<models::Item> =
            ApiResponse::new(timvw_rust_runtime::StatusCode::OK, Default::default(), r#"{"id":"42","name":7}"#.to_owned());
        let decoded: Result<models::Item, _> = (|| -> Result<models::Item, BoxError> {
            let item: models::Item =
                serde_path_to_error::deserialize(&mut serde_json::Deserializer::from_str(response.text()))?;
            Ok(item)
        })();
        assert!(decoded.unwrap_err().to_string().starts_with("name: "));
        Ok(())
    }

    fn assert_send_sync<T: Send + Sync + Clone>() {}

    /// `ApiResponse<T>` is `Send + Sync + Clone` whatever `T` is.
    #[test]
    fn send_sync() {
        assert_send_sync::<ApiResponse<models::Item>>();
        assert_send_sync::<ApiResponse<std::rc::Rc<u8>>>();
    }

    /// A `_response` future is `Send`: it can be spawned.
    fn spawnable(c: Configuration) {
        let _handle = tokio::spawn(async move {
            let r = items_api::get_item_response().configuration(&c).id("1").call().await;
            r.map(|r| r.status)
        });
    }

    /// `json()` of `ApiResponse<String>` wants a JSON string literal and of `ApiResponse<()>` a `null`: use `text()`.
    #[test]
    fn text_and_unit() {
        let r: ApiResponse<String> = ApiResponse::new(timvw_rust_runtime::StatusCode::OK, Default::default(), "1.2.3".into());
        assert_eq!(r.text(), "1.2.3");
        assert!(r.json().is_err());
        let u: ApiResponse<()> = ApiResponse::new(timvw_rust_runtime::StatusCode::NO_CONTENT, Default::default(), String::new());
        assert!(u.json().is_err());
    }
}

/// docs/guides/http-stack.md
mod http_stack {
    use super::*;

    #[tokio::test]
    async fn a_service_of_your_own() -> Result<(), BoxError> {
        use timvw_rust_runtime::http;
        use timvw_rust_runtime::{Body, BoxError, Client};

        let service = timvw_rust_runtime::tower::service_fn(|_request: http::Request<Body>| async {
            let page = r#"{"data":[{"id":"1","name":"Lamp"}],"has_more":false}"#;
            Ok::<_, BoxError>(http::Response::builder().header("content-type", "application/json").body(Body::from(page))?)
        });
        let mut configuration = Configuration::new();
        configuration.client = Client::from_service(service);
        let page = items_api::list_items().configuration(&configuration).call().await?;
        assert_eq!(page.data[0].name, "Lamp");
        Ok(())
    }

    fn credentials() -> Result<(), BoxError> {
        use my_client::apis::configuration::{ApiKey, ExposeSecret, SecretString};

        let mut configuration = Configuration::new();
        configuration.bearer_access_token = Some(SecretString::from(std::env::var("API_TOKEN")?));
        configuration.basic_auth = Some(("user".to_owned(), Some("password".into())));
        configuration.api_key = Some(ApiKey { prefix: None, key: "sk-123".into() });
        println!("{configuration:?}"); // the secrets print as [REDACTED]
        let key: &str = configuration.api_key.as_ref().unwrap().key.expose_secret();
        Ok(())
    }
}

/// docs/guides/http-stack.md
mod http_stack_options {
    use super::*;

    #[tokio::test]
    async fn headers_and_query_parameters_for_one_call() -> Result<(), BoxError> {
        use timvw_rust_runtime::{http, Body, Client};

        let mut configuration = Configuration::new();
        configuration.client = Client::from_service(timvw_rust_runtime::tower::service_fn(
            |request: http::Request<Body>| async move {
                assert_eq!(request.headers()["x-request-source"], "batch");
                assert_eq!(request.uri().query(), Some("limit=10&trace=1"));
                let page = r#"{"data":[],"has_more":false}"#;
                Ok::<_, timvw_rust_runtime::BoxError>(
                    http::Response::builder()
                        .header("content-type", "application/json")
                        .body(Body::from(page))?,
                )
            },
        ));

        use my_client::apis::RequestOptions;

        let options = RequestOptions::new()
            .header("x-request-source", "batch")? // replaces a generated header of that name
            .query(&[("trace", "1")])?; // after the generated query
        let page = items_api::list_items()
            .configuration(&configuration)
            .limit(10)
            .options(&options)
            .call()
            .await?;
        assert!(page.data.is_empty());
        Ok(())
    }
}

/// docs/guides/http-stack.md
mod http_stack_uploads {
    use super::*;

    async fn uploads_and_downloads() -> Result<(), BoxError> {
        let configuration = configuration();

        use std::path::PathBuf;
        use timvw_rust_runtime::Upload;

        // A path: streamed from disk, opened again if the request is retried.
        items_api::upload_item_image()
            .configuration(&configuration)
            .id("1")
            .image(PathBuf::from("lamp.png"))
            .call()
            .await?;

        // Bytes held in memory, with a file name and a media type of your own.
        let png: Vec<u8> = std::fs::read("lamp.png")?;
        items_api::upload_item_image()
            .configuration(&configuration)
            .id("1")
            .image(Upload::bytes(png).file_name("lamp.png").mime("image/png"))
            .call()
            .await?;

        // A binary response, written to a file as it arrives.
        let response = items_api::get_item_image().configuration(&configuration).id("1").call().await?;
        response.save_to("lamp-copy.png").await?;
        Ok(())
    }
}

/// docs/guides/migration.md
mod migration {
    use super::*;

    async fn file_parameters() -> Result<(), BoxError> {
        use std::path::PathBuf;
        let configuration = configuration();

        items_api::upload_item_image()
            .configuration(&configuration)
            .id("1")
            .image(PathBuf::from("lamp.png"))
            .call()
            .await?;
        Ok(())
    }

    fn secret_credentials() {
        use my_client::apis::configuration::{ApiKey, SecretString};

        let mut configuration = Configuration::new();
        let token = "sk-123".to_owned();
        configuration.bearer_access_token = Some(token.into());
        configuration.oauth_access_token = Some(SecretString::from("oauth-token"));
        configuration.basic_auth = Some(("user".to_owned(), Some("password".into())));
        configuration.api_key = Some(ApiKey {
            prefix: Some("Token".to_owned()),
            key: "key".into(),
        });
    }

    async fn bodies() -> Result<(), BoxError> {
        let configuration = configuration();
        let new_item = new_item();
        let item = items_api::create_item()
            .configuration(&configuration)
            .new_item(&new_item)
            .call()
            .await?;
        Ok(())
    }

    async fn uuids_dates_and_cookies() -> Result<(), BoxError> {
        let configuration = configuration();
        let owner_id = uuid::Uuid::parse_str("6f1c2b8e-0d1e-4a5b-9c3d-2e4f5a6b7c8d")?;
        let items = items_api::list_owner_items()
            .configuration(&configuration)
            .owner_id(owner_id)
            .call()
            .await?;

        let since = chrono::DateTime::parse_from_rfc3339("2026-01-02T03:04:05+01:00")?;
        let recent = items_api::list_owner_items()
            .configuration(&configuration)
            .owner_id(owner_id)
            .since(since)
            .call()
            .await?;

        items_api::list_owner_items()
            .configuration(&configuration)
            .owner_id(owner_id)
            .session("abc")
            .call()
            .await?;
        Ok(())
    }

    async fn deep_object() -> Result<(), BoxError> {
        let configuration = configuration();
        let metadata = std::collections::HashMap::from([("team".to_owned(), "lamps".to_owned())]);
        let page = items_api::list_items()
            .configuration(&configuration)
            .metadata(metadata)
            .call()
            .await?;
        Ok(())
    }

    #[test]
    fn status_variants() {
        let response = not_found();
        match &response.entity {
            Some(items_api::GetItemError::Status404(problem)) => println!("missing: {}", problem.detail),
            Some(items_api::GetItemError::Status5XX(problem)) => println!("server: {}", problem.detail),
            Some(items_api::GetItemError::UnknownValue(value)) => println!("{}: {value}", response.status),
            None => println!("{}: {}", response.status, response.content),
        }
    }

    #[test]
    fn headers_and_boxes() {
        let body = String::from("{}");
        let content = ResponseContent {
            status: timvw_rust_runtime::StatusCode::TOO_MANY_REQUESTS,
            content: body.clone(),
            entity: None::<items_api::CreateItemError>,
            headers: timvw_rust_runtime::header::HeaderMap::new(),
        };

        let result: Result<models::Item, Error<items_api::GetItemError>> = Err(Error::ResponseError(Box::new(not_found())));
        let error: Error<items_api::CreateItemError> = Error::ResponseError(Box::new(content));
        if let Err(Error::ResponseError(response)) = result {
            let content: ResponseContent<items_api::GetItemError> = *response;
            println!("{}", content.status);
        }
    }

    #[test]
    fn text_errors() {
        let response = rejected();
        if let Some(items_api::CreateItemError::Status400(message)) = &response.entity {
            println!("rejected: {message}");
        }
    }

    async fn null_branch_and_path() -> Result<(), BoxError> {
        let configuration = configuration();
        let published = items_api::list_items()
            .configuration(&configuration)
            .status(models::Status::Published)
            .call()
            .await?;
        items_api::get_item().configuration(&configuration).id("42").call().await?;
        Ok(())
    }

    #[test]
    fn nullable_items() {
        let new_item = models::NewItem::builder()
            .name("Lamp".to_owned())
            .notes(vec![Some("fragile".to_owned()), None])
            .build();
        for note in new_item.notes.iter().flatten().flatten() {
            println!("{note}");
        }
        assert_eq!(
            serde_json::to_value(&new_item).unwrap()["notes"],
            serde_json::json!(["fragile", null])
        );
    }

    #[test]
    fn int64_enums() -> Result<(), BoxError> {
        assert_eq!(serde_json::to_string(&models::Priority::Variant1000)?, "1000");
        let priority: models::Priority = serde_json::from_str("2000")?;
        assert_eq!(priority, models::Priority::Variant2000);
        Ok(())
    }
}
