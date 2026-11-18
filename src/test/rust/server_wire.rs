//! What the server reads as the client writes it, end to end, on the crate generated from
//! src/test/resources/server_wire.yaml with `targets: [client, server]` and `multipartHelper`. `ServerWireTest` copies
//! this file into that crate's tests/ and runs cargo. The generated router serves an implementation on a local port;
//! the generated client calls it.

use std::collections::HashMap;

use server_wire::apis::configuration::Configuration;
use server_wire::apis::{beta_api, notes_api};
use server_wire::models::{
    Attached, AttachRequestFiles, AttachRequestSource, ListNotesOrderParameter,
    ListNotesOrderParameterTextVariant2Enum, Listed, Note, Range, Reference,
};
use server_wire::server::beta_api::{
    BetaCountNotesRequest, BetaCountNotesResponse, BetaGetNoteRequest, BetaGetNoteResponse,
};
use server_wire::server::notes_api::{
    AttachFiles, AttachRequest, AttachResponse, AttachSource, DeleteNoteRequest, DeleteNoteResponse, GetNoteRequest,
    GetNoteResponse, ListNotesRequest, ListNotesResponse,
};
use server_wire::server::{self, BetaApi, ErrorHandler, FilePart, NotesApi, ServerError};

/// Answers with what it read.
struct Echo;

impl ErrorHandler for Echo {}

fn file(part: &FilePart) -> String {
    format!("file {} ({} bytes)", part.file_name.as_deref().unwrap_or("?"), part.bytes.len())
}

impl NotesApi for Echo {
    async fn get_note(&self, request: GetNoteRequest) -> Result<GetNoteResponse, ServerError> {
        Ok(GetNoteResponse::Status200(Note { id: request.note_id, beta: false, include: None }))
    }

    async fn delete_note(&self, _request: DeleteNoteRequest) -> Result<DeleteNoteResponse, ServerError> {
        Ok(DeleteNoteResponse::Status204)
    }

    async fn list_notes(&self, request: ListNotesRequest) -> Result<ListNotesResponse, ServerError> {
        Ok(ListNotesResponse::Status200(Listed {
            metadata: request.metadata.unwrap_or_default(),
            created: request.created,
            order: request.order.map(|order| order.to_string()),
            limit: request.limit,
        }))
    }

    async fn attach(&self, request: AttachRequest) -> Result<AttachResponse, ServerError> {
        let form = request.body;
        let source = match &form.source {
            AttachSource::Text(part) => file(part),
            AttachSource::Reference(reference) => format!("reference {}", reference.id),
        };
        let files = match &form.files {
            None => Vec::new(),
            Some(AttachFiles::Text(part)) => vec![format!("one {}", file(part))],
            Some(AttachFiles::ArrayOfStrings(parts)) => parts.iter().map(file).collect(),
        };
        Ok(AttachResponse::Status200(Attached { source, files, caption: form.caption }))
    }
}

impl BetaApi for Echo {
    async fn beta_get_note(&self, request: BetaGetNoteRequest) -> Result<BetaGetNoteResponse, ServerError> {
        Ok(BetaGetNoteResponse::Status200(Note { id: request.note_id, beta: true, include: request.include }))
    }

    async fn beta_count_notes(&self, _request: BetaCountNotesRequest) -> Result<BetaCountNotesResponse, ServerError> {
        Ok(BetaCountNotesResponse::Status200(42))
    }
}

async fn serve() -> Configuration {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move { axum::serve(listener, server::router(Echo)).await.unwrap() });
    let mut configuration = Configuration::new();
    configuration.base_path = format!("http://127.0.0.1:{port}");
    configuration.client = timvw_rust_runtime::Client::from_service(timvw_rust_runtime::ReqwestService::default());
    configuration
}

/// `/notes/{id}` and `/notes/{id}?beta=true` share a route: the fixed query picks the operation.
#[tokio::test]
async fn a_fixed_query_picks_the_operation() {
    let configuration = serve().await;
    let note = notes_api::get_note().configuration(&configuration).note_id("n1").call().await.unwrap();
    assert_eq!((note.id.as_str(), note.beta), ("n1", false));
    let note = beta_api::beta_get_note()
        .configuration(&configuration)
        .note_id("n1")
        .include("body")
        .call()
        .await
        .unwrap();
    assert_eq!((note.id.as_str(), note.beta, note.include.as_deref()), ("n1", true, Some("body")));
    // another method of the path is routed as usual
    notes_api::delete_note().configuration(&configuration).note_id("n1").call().await.unwrap();
    assert_eq!(beta_api::beta_count_notes().configuration(&configuration).call().await.unwrap(), 42);
}

/// A path that has only an operation with a fixed query is not found without it.
#[tokio::test]
async fn without_its_fixed_query_a_path_is_not_found() {
    let configuration = serve().await;
    let url = format!("{}/notes/count", configuration.base_path);
    let request = configuration.client.request(timvw_rust_runtime::Method::GET, &url).build().unwrap();
    let response = configuration.client.execute(request).await.unwrap();
    assert_eq!(response.status(), 404);
}

/// deepObject parameters: a map, and an object with numbers and an array; and a union parameter from its text.
#[tokio::test]
async fn deep_objects_and_a_union_are_read_as_the_client_writes_them() {
    let configuration = serve().await;
    let metadata = HashMap::from([("team".to_owned(), "core".to_owned()), ("tier".to_owned(), "".to_owned())]);
    let created = Range { gt: Some(10), lt: Some(20), tags: Some(vec!["a".to_owned(), "b".to_owned()]) };
    let listed = notes_api::list_notes()
        .configuration(&configuration)
        .metadata(metadata.clone())
        .created(created.clone())
        .order(ListNotesOrderParameter::TextVariant2(ListNotesOrderParameterTextVariant2Enum::Desc))
        .limit(5)
        .call()
        .await
        .unwrap();
    assert_eq!(listed.metadata, metadata);
    assert_eq!(listed.created, Some(created));
    assert_eq!(listed.order.as_deref(), Some("desc"));
    assert_eq!(listed.limit, Some(5));

    let listed = notes_api::list_notes().configuration(&configuration).call().await.unwrap();
    assert_eq!(listed, Listed { metadata: HashMap::new(), created: None, order: None, limit: None });
}

/// A deepObject value that does not fit its type is a 400.
#[tokio::test]
async fn a_deep_object_that_does_not_fit_is_a_bad_request() {
    let configuration = serve().await;
    let url = format!("{}/notes?created%5Bgt%5D=soon", configuration.base_path);
    let request = configuration.client.request(timvw_rust_runtime::Method::GET, &url).build().unwrap();
    let response = configuration.client.execute(request).await.unwrap();
    assert_eq!(response.status(), 400);
    let body = response.text().await.unwrap();
    assert!(body.contains("created"), "{body}");
}

/// A union with a file variant: a file, a reference (JSON), one file and the files of a directory (`files[]`).
#[tokio::test]
async fn a_union_with_a_file_variant_is_a_file_or_a_value() {
    let configuration = serve().await;
    let upload = |name: &'static str, bytes: &'static [u8]| timvw_rust_runtime::Upload::bytes(bytes).file_name(name);

    let attached = notes_api::attach()
        .configuration(&configuration)
        .note_id("n1")
        .source(AttachRequestSource::file(upload("a.txt", b"abc")))
        .files(AttachRequestFiles::files([upload("x.md", b"x"), upload("y.md", b"yy")]))
        .caption("both")
        .call()
        .await
        .unwrap();
    assert_eq!(
        attached,
        Attached {
            source: "file a.txt (3 bytes)".to_owned(),
            files: vec!["file x.md (1 bytes)".to_owned(), "file y.md (2 bytes)".to_owned()],
            caption: Some("both".to_owned()),
        }
    );

    let attached = notes_api::attach()
        .configuration(&configuration)
        .note_id("n1")
        .source(AttachRequestSource::Reference(Reference::new("file_123".to_owned())))
        .files(AttachRequestFiles::file(upload("all.zip", b"zip!")))
        .call()
        .await
        .unwrap();
    assert_eq!(attached.source, "reference file_123");
    assert_eq!(attached.files, vec!["one file all.zip (4 bytes)".to_owned()]);
    assert_eq!(attached.caption, None);

    // one file of a directory is still the directory's: it is sent as `files[]`
    let attached = notes_api::attach()
        .configuration(&configuration)
        .note_id("n1")
        .source(AttachRequestSource::file(upload("a.txt", b"abc")))
        .files(AttachRequestFiles::files([upload("only.md", b"o")]))
        .call()
        .await
        .unwrap();
    assert_eq!(attached.files, vec!["file only.md (1 bytes)".to_owned()]);
}

/// A part of a file union that is neither a file nor a value of the union is a 400, and so is a missing required one.
#[tokio::test]
async fn a_part_that_is_no_variant_is_a_bad_request() {
    let configuration = serve().await;
    let url = format!("{}/notes/n1/attachments", configuration.base_path);
    for form in [
        timvw_rust_runtime::multipart::Form::new().text("source", "not json"),
        timvw_rust_runtime::multipart::Form::new().text("caption", "no source"),
    ] {
        let request = configuration
            .client
            .request(timvw_rust_runtime::Method::POST, &url)
            .multipart(form)
            .build()
            .unwrap();
        let response = configuration.client.execute(request).await.unwrap();
        assert_eq!(response.status(), 400);
    }
}
