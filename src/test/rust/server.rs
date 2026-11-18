//! The server target end to end, on the crate generated from src/test/resources/server.yaml with
//! `targets: [client, server]`, `responseFunctions`, `typedEventStreams` and `contentTypeMethods`. `ServerTest` copies this file into that crate's tests/ and
//! runs cargo. The generated router serves an implementation on a local port; the generated client calls it.

use std::collections::HashMap;
use std::sync::Mutex;

use futures_util::StreamExt;

use server_fixture::apis::configuration::Configuration;
use server_fixture::apis::{owners_api, pets_api, Error};
use server_fixture::headers::{ListPetsErrorHeaders, ListPetsResponseHeaders};
use server_fixture::headers::GetPhotoResponseHeaders;
use server_fixture::models::{self, Kind, NewPet, Pet, Photo};
use server_fixture::server::owners_api::{
    GetOwnerPetRequest, GetOwnerPetResponse, OwnerEventsRequest, OwnerEventsResponse,
};
use server_fixture::server::pets_api::{
    CreatePetRequest, CreatePetResponse, DeletePetRequest, DeletePetResponse, GetPetNameRequest, GetPetNameResponse,
    GetPetRequest, GetPetResponse, GetPhotoRequest, GetPhotoResponse, ListPetsRequest, ListPetsResponse,
    PetFeedRequest, PetFeedResponse, PutPhotoRequest, PutPhotoResponse, RenamePetRequest, RenamePetResponse,
    UpdatePetRequest, UpdatePetResponse, UploadPhotoRequest, UploadPhotoResponse,
};
use server_fixture::server::{
    self, Binary, ErrorHandler, EventStream, OwnersApi, PetsApi, RequestError, ServerError,
};

/// A pet store in memory, which records the last `list_pets` request.
#[derive(Default)]
struct Store {
    pets: Mutex<Vec<Pet>>,
    photos: Mutex<HashMap<i64, axum::body::Bytes>>,
    last_list: Mutex<Option<ListPetsRequest>>,
}

impl Store {
    fn pets(&self) -> Vec<Pet> {
        self.pets.lock().unwrap().clone()
    }
}

impl ErrorHandler for Store {}

impl PetsApi for Store {
    async fn list_pets(&self, request: ListPetsRequest) -> Result<ListPetsResponse, ServerError> {
        *self.last_list.lock().unwrap() = Some(request.clone());
        if request.limit == Some(0) {
            return Ok(ListPetsResponse::Status4XX {
                status: http::StatusCode::UNPROCESSABLE_ENTITY,
                body: models::Error::new("limit must be positive".to_owned()),
                headers: ListPetsErrorHeaders { x_reason: Some("zero".to_owned()) },
            });
        }
        if request.limit == Some(13) {
            return Err(ServerError::new("unlucky"));
        }
        let pets: Vec<Pet> = self
            .pets
            .lock()
            .unwrap()
            .iter()
            .filter(|p| request.kind.is_none() || p.kind == request.kind)
            .take(request.limit.map_or(usize::MAX, |l| l as usize))
            .cloned()
            .collect();
        let headers = ListPetsResponseHeaders {
            x_total: Some(pets.len() as i64),
            x_kinds: Some(pets.iter().filter_map(|p| p.kind).collect()),
            x_next: Some(chrono::DateTime::parse_from_rfc3339("2026-11-15T10:00:00+01:00").unwrap()),
        };
        Ok(ListPetsResponse::Status200 { body: pets, headers })
    }

    async fn create_pet(&self, request: CreatePetRequest) -> Result<CreatePetResponse, ServerError> {
        let mut pets = self.pets.lock().unwrap();
        if pets.iter().any(|p| p.name == request.body.name) {
            return Ok(CreatePetResponse::Status409(models::Error::new("exists".to_owned())));
        }
        let pet = Pet { id: pets.len() as i64 + 1, name: request.body.name, kind: request.body.kind };
        pets.push(pet.clone());
        Ok(CreatePetResponse::Status201(pet))
    }

    async fn get_pet(&self, request: GetPetRequest) -> Result<GetPetResponse, ServerError> {
        assert!(request.verbose);
        match self.pets.lock().unwrap().iter().find(|p| p.id == request.pet_id) {
            Some(pet) => Ok(GetPetResponse::Status200(pet.clone())),
            None => Ok(GetPetResponse::Default {
                status: http::StatusCode::NOT_FOUND,
                body: models::Error::new(format!("no pet {}", request.pet_id)),
            }),
        }
    }

    async fn update_pet(&self, request: UpdatePetRequest) -> Result<UpdatePetResponse, ServerError> {
        let mut pets = self.pets.lock().unwrap();
        let pet = pets.iter_mut().find(|p| p.id == request.pet_id).ok_or_else(|| ServerError::new("no pet"))?;
        if let Some(body) = request.body {
            pet.name = body.name;
            pet.kind = body.kind;
        }
        Ok(UpdatePetResponse::Status200(pet.clone()))
    }

    async fn delete_pet(&self, request: DeletePetRequest) -> Result<DeletePetResponse, ServerError> {
        let mut pets = self.pets.lock().unwrap();
        let before = pets.len();
        pets.retain(|p| p.id != request.pet_id);
        Ok(if pets.len() < before { DeletePetResponse::Status204 } else { DeletePetResponse::Status404 })
    }

    async fn upload_photo(&self, request: UploadPhotoRequest) -> Result<UploadPhotoResponse, ServerError> {
        let form = request.body;
        self.photos.lock().unwrap().insert(request.pet_id, form.file.bytes.clone());
        Ok(UploadPhotoResponse::Status200(Photo {
            file_name: form.file.file_name.unwrap_or_default(),
            content_type: form.file.content_type,
            size: form.file.bytes.len() as i64,
            caption: form.caption,
            width: form.width,
            tags: form.tags,
            extras: form.extras.map(|extras| extras.len() as i64),
            owner: form.owner.map(|owner| owner.name),
        }))
    }

    async fn put_photo(&self, request: PutPhotoRequest) -> Result<PutPhotoResponse, ServerError> {
        self.photos.lock().unwrap().insert(request.pet_id, request.body);
        Ok(PutPhotoResponse::Status204)
    }

    async fn get_photo(&self, request: GetPhotoRequest) -> Result<GetPhotoResponse, ServerError> {
        let Some(photo) = self.photos.lock().unwrap().get(&request.pet_id).cloned() else {
            return Ok(GetPhotoResponse::Status404(format!("no photo of pet {}", request.pet_id)));
        };
        let headers = GetPhotoResponseHeaders { x_photo_size: Some(photo.len() as i64) };
        // streamed in chunks of three bytes; a JPEG when the request asks for one
        let chunks: Vec<Result<axum::body::Bytes, std::io::Error>> =
            photo.chunks(3).map(|c| Ok(axum::body::Bytes::copy_from_slice(c))).collect();
        let mut body = Binary::stream(futures_util::stream::iter(chunks));
        if request.headers.get("accept").is_some_and(|v| v == "image/jpeg") {
            body = body.content_type("image/jpeg");
        }
        Ok(GetPhotoResponse::Status200 { body, headers })
    }

    async fn get_pet_name(&self, request: GetPetNameRequest) -> Result<GetPetNameResponse, ServerError> {
        let pet = self.pets().into_iter().find(|p| p.id == request.pet_id).ok_or_else(|| ServerError::new("no pet"))?;
        Ok(GetPetNameResponse::Status200(pet.name))
    }

    async fn rename_pet(&self, request: RenamePetRequest) -> Result<RenamePetResponse, ServerError> {
        let mut pets = self.pets.lock().unwrap();
        let pet = pets.iter_mut().find(|p| p.id == request.pet_id).ok_or_else(|| ServerError::new("no pet"))?;
        pet.name = request.body;
        Ok(RenamePetResponse::Status204)
    }

    async fn pet_feed(&self, request: PetFeedRequest) -> Result<PetFeedResponse, ServerError> {
        let stream = request.headers.get("accept").is_some_and(|v| v == "text/event-stream");
        Ok(if stream {
            PetFeedResponse::Status200Stream(EventStream::from_events(self.pets()).done())
        } else {
            PetFeedResponse::Status200(self.pets())
        })
    }
}

impl OwnersApi for Store {
    async fn get_owner_pet(&self, request: GetOwnerPetRequest) -> Result<GetOwnerPetResponse, ServerError> {
        Ok(GetOwnerPetResponse::Status200(Pet {
            id: 7,
            name: format!("{}'s {}", request.owner_name, request.pet_name),
            kind: None,
        }))
    }

    async fn owner_events(&self, request: OwnerEventsRequest) -> Result<OwnerEventsResponse, ServerError> {
        let pets = self.pets();
        // with `x-fail`, the stream fails after its first event
        let fail = request.headers.contains_key("x-fail");
        let events = futures_util::stream::iter(pets.into_iter().enumerate()).then(move |(i, pet)| async move {
            if fail && i > 0 {
                // after the first event has gone out
                tokio::time::sleep(std::time::Duration::from_millis(50)).await;
                Err(ServerError::new("the feed broke"))
            } else {
                Ok(pet)
            }
        });
        Ok(OwnerEventsResponse::Status200(EventStream::try_new(events)))
    }
}

/// Implements the server's traits for a wrapper by calling those of what it holds.
macro_rules! delegate {
    ($wrapper:ty) => {
        impl PetsApi for $wrapper {
            async fn list_pets(&self, request: ListPetsRequest) -> Result<ListPetsResponse, ServerError> {
                self.0.list_pets(request).await
            }
            async fn create_pet(&self, request: CreatePetRequest) -> Result<CreatePetResponse, ServerError> {
                self.0.create_pet(request).await
            }
            async fn get_pet(&self, request: GetPetRequest) -> Result<GetPetResponse, ServerError> {
                self.0.get_pet(request).await
            }
            async fn update_pet(&self, request: UpdatePetRequest) -> Result<UpdatePetResponse, ServerError> {
                self.0.update_pet(request).await
            }
            async fn delete_pet(&self, request: DeletePetRequest) -> Result<DeletePetResponse, ServerError> {
                self.0.delete_pet(request).await
            }
            async fn upload_photo(&self, request: UploadPhotoRequest) -> Result<UploadPhotoResponse, ServerError> {
                self.0.upload_photo(request).await
            }
            async fn put_photo(&self, request: PutPhotoRequest) -> Result<PutPhotoResponse, ServerError> {
                self.0.put_photo(request).await
            }
            async fn get_photo(&self, request: GetPhotoRequest) -> Result<GetPhotoResponse, ServerError> {
                self.0.get_photo(request).await
            }
            async fn get_pet_name(&self, request: GetPetNameRequest) -> Result<GetPetNameResponse, ServerError> {
                self.0.get_pet_name(request).await
            }
            async fn rename_pet(&self, request: RenamePetRequest) -> Result<RenamePetResponse, ServerError> {
                self.0.rename_pet(request).await
            }
            async fn pet_feed(&self, request: PetFeedRequest) -> Result<PetFeedResponse, ServerError> {
                self.0.pet_feed(request).await
            }
        }

        impl OwnersApi for $wrapper {
            async fn get_owner_pet(&self, request: GetOwnerPetRequest) -> Result<GetOwnerPetResponse, ServerError> {
                self.0.get_owner_pet(request).await
            }
            async fn owner_events(&self, request: OwnerEventsRequest) -> Result<OwnerEventsResponse, ServerError> {
                self.0.owner_events(request).await
            }
        }
    };
}

/// Serves `api` on a local port; the client's configuration for it.
async fn serve<I>(api: I) -> Configuration
where
    I: PetsApi + OwnersApi + ErrorHandler + 'static,
{
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move { axum::serve(listener, server::router(api)).await.unwrap() });
    let mut configuration = Configuration::new();
    configuration.base_path = format!("http://127.0.0.1:{port}");
    // No retries: a 5xx is the answer, not a reason to ask again.
    configuration.client = timvw_rust_runtime::Client::from_service(timvw_rust_runtime::ReqwestService::default());
    configuration
}

async fn seeded() -> (Configuration, std::sync::Arc<Store>) {
    let store = std::sync::Arc::new(Store::default());
    store.pets.lock().unwrap().extend([
        Pet { id: 1, name: "Tom".to_owned(), kind: Some(Kind::Cat) },
        Pet { id: 2, name: "Rex".to_owned(), kind: Some(Kind::Dog) },
    ]);
    (serve(Shared(store.clone())).await, store)
}

/// The store behind an `Arc`, so the test can look at it while the server holds it.
struct Shared(std::sync::Arc<Store>);

impl ErrorHandler for Shared {}

delegate!(Shared);

/// A JSON body, a 201 with the created model, and a declared error status with its model.
#[tokio::test]
async fn create_and_conflict() {
    let (configuration, store) = seeded().await;
    let pet = pets_api::create_pet()
        .configuration(&configuration)
        .new_pet(&NewPet { name: "Felix".to_owned(), kind: Some(Kind::Cat) })
        .call()
        .await
        .unwrap();
    assert_eq!(pet, Pet { id: 3, name: "Felix".to_owned(), kind: Some(Kind::Cat) });
    assert_eq!(store.pets.lock().unwrap().len(), 3);

    let error = pets_api::create_pet()
        .configuration(&configuration)
        .new_pet(&NewPet { name: "Felix".to_owned(), kind: None })
        .call()
        .await
        .unwrap_err();
    let Error::ResponseError(content) = error else { panic!("not a response error: {error:?}") };
    assert_eq!(content.status, 409);
    assert!(
        matches!(&content.entity, Some(pets_api::CreatePetError::Status409(e)) if e.message == "exists"),
        "{content:?}"
    );
}

/// Typed path and query parameters, a required one among them, and the `default` response with its status.
#[tokio::test]
async fn path_and_query_parameters() {
    let (configuration, _) = seeded().await;
    let pet = pets_api::get_pet().configuration(&configuration).pet_id(2).verbose(true).call().await.unwrap();
    assert_eq!(pet.name, "Rex");

    let error = pets_api::get_pet().configuration(&configuration).pet_id(9).verbose(true).call().await.unwrap_err();
    let Error::ResponseError(content) = error else { panic!("not a response error: {error:?}") };
    assert_eq!(content.status, 404);
    assert!(matches!(&content.entity, Some(pets_api::GetPetError::DefaultResponse(e)) if e.message == "no pet 9"));

    // Strings with spaces and a slash in path segments are decoded.
    let owned = owners_api::get_owner_pet()
        .configuration(&configuration)
        .owner_name("Ann Lee")
        .pet_name("a/b")
        .call()
        .await
        .unwrap();
    assert_eq!(owned.name, "Ann Lee's a/b");
}

/// Query arrays both ways (repeated, comma-separated), an enum, an integer, a header parameter in the header map,
/// and the success headers written by the server and read by the client.
#[tokio::test]
async fn lists_enums_and_typed_headers() {
    let (configuration, store) = seeded().await;
    let response = pets_api::list_pets_response()
        .configuration(&configuration)
        .limit(5)
        .kind(Kind::Dog)
        .tags(vec!["a b".to_owned(), "c,d".to_owned()])
        .ids(vec![1, 2, 3])
        .x_trace("t-1")
        .call()
        .await
        .unwrap();
    assert_eq!(response.status, 200);
    let pets = response.json().unwrap();
    assert_eq!(pets.iter().map(|p| p.name.as_str()).collect::<Vec<_>>(), ["Rex"]);
    let headers = response.typed_headers::<pets_api::ListPetsResponseHeaders>();
    assert_eq!(headers.x_total, Some(1));
    assert_eq!(headers.x_kinds, Some(vec![Kind::Dog]));
    assert_eq!(headers.x_next.unwrap().to_rfc3339(), "2026-11-15T10:00:00+01:00");

    let request = store.last_list.lock().unwrap().clone().unwrap();
    assert_eq!(request.limit, Some(5));
    assert_eq!(request.kind, Some(Kind::Dog));
    assert_eq!(request.tags, Some(vec!["a b".to_owned(), "c,d".to_owned()]));
    assert_eq!(request.ids, Some(vec![1, 2, 3]));
    assert_eq!(request.headers.get("x-trace").unwrap(), "t-1");

    // Without the optional parameters
    pets_api::list_pets().configuration(&configuration).call().await.unwrap();
    let request = store.last_list.lock().unwrap().clone().unwrap();
    assert_eq!((request.limit, request.kind, request.tags, request.ids), (None, None, None, None));
}

/// A status range (`4XX`) with the status the implementation chose and the error headers it set.
#[tokio::test]
async fn a_range_status_with_error_headers() {
    let (configuration, _) = seeded().await;
    let error = pets_api::list_pets().configuration(&configuration).limit(0).call().await.unwrap_err();
    let Error::ResponseError(content) = error else { panic!("not a response error: {error:?}") };
    assert_eq!(content.status, 422);
    assert_eq!(content.typed_headers::<pets_api::ListPetsErrorHeaders>().x_reason.as_deref(), Some("zero"));
    assert!(matches!(&content.entity, Some(pets_api::ListPetsError::Status4XX(e)) if e.message == "limit must be positive"));
}

/// An optional body, sent or not, and a response without a body (`204`), or a declared one without content.
#[tokio::test]
async fn optional_body_and_empty_responses() {
    let (configuration, _) = seeded().await;
    let pet = pets_api::update_pet()
        .configuration(&configuration)
        .pet_id(1)
        .new_pet(&NewPet { name: "Tom II".to_owned(), kind: None })
        .call()
        .await
        .unwrap();
    assert_eq!(pet, Pet { id: 1, name: "Tom II".to_owned(), kind: None });
    let pet = pets_api::update_pet().configuration(&configuration).pet_id(1).call().await.unwrap();
    assert_eq!(pet.name, "Tom II");

    pets_api::delete_pet().configuration(&configuration).pet_id(1).call().await.unwrap();
    let error = pets_api::delete_pet().configuration(&configuration).pet_id(1).call().await.unwrap_err();
    let Error::ResponseError(content) = error else { panic!("not a response error: {error:?}") };
    assert_eq!(content.status, 404);
}

/// What the server answers itself: a `400` for a request that does not decode, a `500` for an implementation's
/// error, both with a JSON error body.
#[tokio::test]
async fn requests_that_do_not_decode_and_errors() {
    let (configuration, _) = seeded().await;
    let send = |method: timvw_rust_runtime::Method, path: &str, body: Option<&'static str>| {
        let configuration = configuration.clone();
        let url = format!("{}{}", configuration.base_path, path);
        async move {
            let mut builder = configuration.client.request(method, &url);
            if let Some(body) = body {
                builder = builder.header("content-type", "application/json").body(body);
            }
            let response = configuration.client.execute(builder.build().unwrap()).await.unwrap();
            let status = response.status().as_u16();
            let text = response.text().await.unwrap();
            (status, serde_json::from_str::<serde_json::Value>(&text).unwrap())
        }
    };
    use timvw_rust_runtime::Method;

    let (status, body) = send(Method::GET, "/pets/abc?verbose=true", None).await;
    assert_eq!(status, 400);
    assert_eq!(body["error"]["location"], "path");
    assert_eq!(body["error"]["name"], "petId");

    let (status, body) = send(Method::GET, "/pets/1", None).await;
    assert_eq!(status, 400);
    assert_eq!(body["error"]["location"], "query");
    assert_eq!(body["error"]["name"], "verbose");
    assert_eq!(body["error"]["message"], "query parameter `verbose`: missing");

    let (status, body) = send(Method::GET, "/pets?ids=1,x", None).await;
    assert_eq!(status, 400);
    assert_eq!(body["error"]["name"], "ids");

    let (status, body) = send(Method::GET, "/pets?limit=many", None).await;
    assert_eq!(status, 400);
    assert_eq!(body["error"]["name"], "limit");
    assert_eq!(body["error"]["message"], "query parameter `limit`: invalid value 'many'");

    let (status, body) = send(Method::POST, "/pets", Some(r#"{"kind":"cat"}"#)).await;
    assert_eq!(status, 400);
    assert_eq!(body["error"]["location"], "body");
    assert!(body["error"]["message"].as_str().unwrap().contains("name"), "{body}");

    let (status, body) = send(Method::POST, "/pets", None).await;
    assert_eq!(status, 400);
    assert_eq!(body["error"]["message"], "body: missing");

    let (status, body) = send(Method::GET, "/pets?limit=13", None).await;
    assert_eq!(status, 500);
    assert_eq!(body["error"]["message"], "internal server error");
}

/// An `ErrorHandler` of its own shapes the `400` and the `500`.
#[tokio::test]
async fn a_custom_error_handler() {
    struct Custom(Store);
    impl ErrorHandler for Custom {
        fn invalid_request(&self, error: RequestError) -> axum::response::Response {
            let body = serde_json::json!({ "detail": error.message(), "where": error.location() });
            (http::StatusCode::UNPROCESSABLE_ENTITY, axum::Json(body)).into_response()
        }
        fn server_error(&self, error: ServerError) -> axum::response::Response {
            (http::StatusCode::SERVICE_UNAVAILABLE, error.to_string()).into_response()
        }
    }
    delegate!(Custom);
    use axum::response::IntoResponse;

    let configuration = serve(Custom(Store::default())).await;
    let error = pets_api::list_pets().configuration(&configuration).limit(13).call().await.unwrap_err();
    let Error::ResponseError(content) = error else { panic!("not a response error: {error:?}") };
    assert_eq!(content.status, 503);
    assert_eq!(content.content, "unlucky");

    let url = format!("{}/pets?limit=x", configuration.base_path);
    let request = configuration.client.request(timvw_rust_runtime::Method::GET, &url).build().unwrap();
    let response = configuration.client.execute(request).await.unwrap();
    assert_eq!(response.status(), 422);
    let body: serde_json::Value = serde_json::from_str(&response.text().await.unwrap()).unwrap();
    assert_eq!(body, serde_json::json!({ "detail": "invalid value 'x'", "where": "query" }));
}

/// The headers structs are the same on both sides: the client's re-exports are `crate::headers`'.
#[test]
fn the_headers_are_shared() {
    let written = ListPetsResponseHeaders {
        x_total: Some(3),
        x_kinds: Some(vec![Kind::Cat, Kind::Dog]),
        x_next: None,
    };
    let map = written.to_header_map();
    assert_eq!(map.get("x-total").unwrap(), "3");
    assert_eq!(map.get("x-kinds").unwrap(), "cat,dog");
    assert!(map.get("x-next").is_none());
    let read: pets_api::ListPetsResponseHeaders = ListPetsResponseHeaders::from_headers(&map);
    assert_eq!(read, written);
}

/// A `multipart/form-data` body: a file with its name and media type, text, an integer, a repeated text field, more
/// files under one name, and a JSON part.
#[tokio::test]
async fn a_multipart_form() {
    let (configuration, store) = seeded().await;
    let photo = pets_api::upload_photo()
        .configuration(&configuration)
        .pet_id(1)
        .file(timvw_rust_runtime::Upload::bytes(vec![1u8, 2, 3, 4]).file_name("tom.png").mime("image/png"))
        .caption("on the sofa")
        .width(640)
        .tags(vec!["a".to_owned(), "b c".to_owned()])
        .extras(vec![vec![5u8], vec![6u8, 7]])
        .owner(NewPet { name: "Ann".to_owned(), kind: None })
        .call()
        .await
        .unwrap();
    assert_eq!(
        photo,
        Photo {
            file_name: "tom.png".to_owned(),
            content_type: Some("image/png".to_owned()),
            size: 4,
            caption: Some("on the sofa".to_owned()),
            width: Some(640),
            tags: Some(vec!["a".to_owned(), "b c".to_owned()]),
            extras: Some(2),
            owner: Some("Ann".to_owned()),
        }
    );
    assert_eq!(store.photos.lock().unwrap()[&1].as_ref(), [1, 2, 3, 4]);

    // only the required file
    let photo = pets_api::upload_photo()
        .configuration(&configuration)
        .pet_id(2)
        .file(vec![9u8])
        .call()
        .await
        .unwrap();
    assert_eq!((photo.size, photo.caption, photo.tags, photo.extras, photo.owner), (1, None, None, None, None));

    // a field that does not decode, and a missing file: a 400 that names the field
    let send = |form: timvw_rust_runtime::multipart::Form| {
        let configuration = configuration.clone();
        async move {
            let url = format!("{}/pets/1/photo", configuration.base_path);
            let content_type = format!("multipart/form-data; boundary={}", form.boundary());
            let request = configuration
                .client
                .request(timvw_rust_runtime::Method::POST, &url)
                .header("content-type", content_type)
                .body(form.into_body())
                .build()
                .unwrap();
            let response = configuration.client.execute(request).await.unwrap();
            let status = response.status().as_u16();
            (status, serde_json::from_str::<serde_json::Value>(&response.text().await.unwrap()).unwrap())
        }
    };
    let file = || timvw_rust_runtime::multipart::Part::bytes(vec![1u8]).file_name("x.png");
    let (status, body) = send(timvw_rust_runtime::multipart::Form::new().part("file", file()).text("width", "wide")).await;
    assert_eq!(status, 400);
    assert_eq!(body["error"]["location"], "body");
    assert_eq!(body["error"]["name"], "width");
    let (status, body) = send(timvw_rust_runtime::multipart::Form::new().text("caption", "no file")).await;
    assert_eq!(status, 400);
    assert_eq!(body["error"]["message"], "body parameter `file`: missing");
    let (status, _) = send(timvw_rust_runtime::multipart::Form::new().part("file", file()).text("owner", "{")).await;
    assert_eq!(status, 400);

    // a list as one part per item (a client with `multipartHelper`)
    let form = timvw_rust_runtime::multipart::Form::new().part("file", file()).text("tags", "x").text("tags", "[y");
    let (status, body) = send(form).await;
    assert_eq!(status, 200);
    assert_eq!(body["tags"], serde_json::json!(["x", "[y"]));
}

/// A binary request body, and a binary response streamed in chunks with the declared media type or another, its
/// headers, and a text error.
#[tokio::test]
async fn binary_bodies() {
    let (configuration, _) = seeded().await;
    let png: Vec<u8> = (0u8..10).collect();
    pets_api::put_photo().configuration(&configuration).pet_id(2).body(png.clone()).call().await.unwrap();

    let response = pets_api::get_photo().configuration(&configuration).pet_id(2).call().await.unwrap();
    assert_eq!(response.headers().get("content-type").unwrap(), "image/png");
    assert_eq!(GetPhotoResponseHeaders::from_headers(response.headers()).x_photo_size, Some(10));
    assert_eq!(response.bytes().await.unwrap().as_ref(), png.as_slice());

    let options = timvw_rust_runtime::RequestOptions::new().header("accept", "image/jpeg").unwrap();
    let response =
        pets_api::get_photo().configuration(&configuration).pet_id(2).options(&options).call().await.unwrap();
    assert_eq!(response.headers().get("content-type").unwrap(), "image/jpeg");

    let error = pets_api::get_photo().configuration(&configuration).pet_id(1).call().await.unwrap_err();
    let Error::ResponseError(content) = error else { panic!("not a response error: {error:?}") };
    assert_eq!(content.status, 404);
    assert_eq!(content.content, "no photo of pet 1");
    assert_eq!(content.headers.get("content-type").unwrap(), "text/plain");
}

/// A text request body and a text response.
#[tokio::test]
async fn text_bodies() {
    let (configuration, _) = seeded().await;
    pets_api::rename_pet().configuration(&configuration).pet_id(1).body("Tom \"the cat\"").call().await.unwrap();
    let name = pets_api::get_pet_name().configuration(&configuration).pet_id(1).call().await.unwrap();
    assert_eq!(name, "Tom \"the cat\"");
}

/// Server-sent events read by the client's typed event streams: a stream on its own, one that fails half way, and
/// a status with both JSON and a stream, ended with `[DONE]`.
#[tokio::test]
async fn event_streams() {
    let (configuration, _) = seeded().await;
    let events = owners_api::owner_events().configuration(&configuration).call().await.unwrap();
    let pets: Vec<Pet> = events.map(Result::unwrap).collect().await;
    assert_eq!(pets.iter().map(|p| p.name.as_str()).collect::<Vec<_>>(), ["Tom", "Rex"]);

    let options = timvw_rust_runtime::RequestOptions::new().header("x-fail", "1").unwrap();
    let mut events = owners_api::owner_events().configuration(&configuration).options(&options).call().await.unwrap();
    assert_eq!(events.next().await.unwrap().unwrap().name, "Tom");
    assert!(events.next().await.unwrap().is_err(), "the stream ends with an error, not cleanly");

    let pets = pets_api::pet_feed().configuration(&configuration).call().await.unwrap();
    assert_eq!(pets.len(), 2);
    let events = pets_api::pet_feed_stream().configuration(&configuration).call().await.unwrap();
    let pets: Vec<Pet> = events.map(Result::unwrap).collect().await;
    assert_eq!(pets.len(), 2);

    // on the wire: a `data:` line per event, and `[DONE]`
    let url = format!("{}/pets/feed", configuration.base_path);
    let request = configuration
        .client
        .request(timvw_rust_runtime::Method::GET, &url)
        .header("accept", "text/event-stream")
        .build()
        .unwrap();
    let response = configuration.client.execute(request).await.unwrap();
    assert_eq!(response.headers().get("content-type").unwrap(), "text/event-stream");
    let text = response.text().await.unwrap();
    assert!(text.starts_with("data: {\"id\":1,"), "{text}");
    assert!(text.ends_with("\n\ndata: [DONE]\n\n"), "{text}");
}
