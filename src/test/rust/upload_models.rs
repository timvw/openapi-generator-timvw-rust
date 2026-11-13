//! Models with file fields, on the crate generated from src/test/resources/upload_models.yaml (`multipartHelper`).
//! `UploadModelsTest` copies this file into that crate's tests/ and runs cargo. The fields hold
//! `timvw_rust_runtime::Upload`s; an upload has no JSON form, so serde leaves them out.

use std::path::PathBuf;

use timvw_rust_runtime::Upload;
use upload_models::models::{ContainerFileBody, DocumentBody};

#[test]
fn new_takes_paths_and_bytes() {
    let document = DocumentBody::new(
        PathBuf::from("report.pdf"),
        [Upload::bytes(b"a".to_vec()).file_name("a.txt"), PathBuf::from("b.txt").into()],
        None,
    );
    assert_eq!(document.file.name(), Some("report.pdf"));
    assert_eq!(document.attachments.len(), 2);
    assert_eq!(document.attachments[0].name(), Some("a.txt"));
    // Clone and PartialEq stay derived.
    assert_eq!(document.clone(), document);
}

#[test]
fn the_builder_converts() {
    let document = DocumentBody::builder()
        .file(b"bytes".to_vec())
        .attachments([PathBuf::from("a.txt")])
        .extra(vec![Upload::bytes(b"x".to_vec())])
        .nullable_file(PathBuf::from("n.txt"))
        .build();
    assert_eq!(document.extra.as_ref().map(Vec::len), Some(1));
    assert_eq!(document.nullable_file.as_ref().and_then(Upload::name), Some("n.txt"));
    let body = ContainerFileBody::builder().file(PathBuf::from("data.csv")).build();
    assert_eq!(body.file.as_ref().and_then(Upload::name), Some("data.csv"));
}

#[test]
fn the_json_form_leaves_the_files_out() {
    let body = ContainerFileBody {
        file_id: Some("file-1".into()),
        file: Some(Upload::bytes(b"x".to_vec())),
    };
    assert_eq!(serde_json::to_string(&body).unwrap(), r#"{"file_id":"file-1"}"#);
    let document = DocumentBody::new(PathBuf::from("a.txt"), Vec::<Upload>::new(), None);
    assert_eq!(serde_json::to_string(&document).unwrap(), "{}");
}

#[test]
fn json_never_sets_a_file() {
    let body: ContainerFileBody = serde_json::from_str(r#"{"file_id":"file-1","file":"/etc/passwd"}"#).unwrap();
    assert_eq!(body.file_id.as_deref(), Some("file-1"));
    assert!(body.file.is_none());
    // A required upload has no value to decode to.
    let error = serde_json::from_str::<DocumentBody>(r#"{"file":"a.txt","attachments":[],"nullable_file":null}"#)
        .unwrap_err();
    assert!(error.to_string().contains("an upload has no JSON form"), "{error}");
}
