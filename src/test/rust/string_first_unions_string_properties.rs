//! `stringProperties` keeps a required nullable property nullable, on the crate generated from
//! src/test/resources/string_first_unions.yaml with `stringProperties: CreateReply.input|Reply.instructions`.
//! `StringFirstUnionsTest` copies this file into that crate's tests/ and runs cargo.

use string_first_unions::models::Reply;

#[test]
fn a_required_nullable_string_property_decodes_null() {
    let null: Reply = serde_json::from_value(serde_json::json!({"id": "r", "instructions": null, "note": null}))
        .expect("decodes null");
    assert_eq!(null.instructions, None);
    let text: Reply = serde_json::from_value(serde_json::json!({"id": "r", "instructions": "be brief", "note": null}))
        .expect("decodes text");
    assert_eq!(text.instructions.as_deref(), Some("be brief"));
}
