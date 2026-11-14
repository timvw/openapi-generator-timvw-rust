//! String-first unions decode and serialize, on the crate generated from src/test/resources/string_first_unions.yaml.
//! `StringFirstUnionsTest` copies this file into that crate's tests/ and runs cargo.

use string_first_unions::models::{CreateReply, InputParam, Item, Reply, ReplyAllOfInstructions};

#[test]
fn an_all_of_property_holds_either_branch_of_the_union() {
    let text: CreateReply = serde_json::from_value(serde_json::json!({"input": "hi"})).expect("decodes");
    assert_eq!(text.input, Some(InputParam::TextInput("hi".to_string())));
    let list: CreateReply =
        serde_json::from_value(serde_json::json!({"input": [{"text": "a"}]})).expect("decodes");
    assert_eq!(
        list.input,
        Some(InputParam::InputItemList(vec![Item { text: "a".to_string() }]))
    );
    assert_eq!(
        serde_json::to_value(&list).unwrap(),
        serde_json::json!({"input": [{"text": "a"}]})
    );
}

#[test]
fn a_required_nullable_union_decodes_null_and_either_branch() {
    let null: Reply = serde_json::from_value(serde_json::json!({"id": "r", "instructions": null, "note": null}))
        .expect("decodes null");
    assert_eq!(null.instructions, None);
    assert_eq!(
        serde_json::to_value(&null).unwrap(),
        serde_json::json!({"id": "r", "instructions": null, "note": null})
    );
    let text: Reply = serde_json::from_value(serde_json::json!({"id": "r", "instructions": "be brief", "note": "n"}))
        .expect("decodes text");
    assert_eq!(text.instructions, Some(ReplyAllOfInstructions::Text("be brief".to_string())));
    let list: Reply = serde_json::from_value(
        serde_json::json!({"id": "r", "instructions": [{"text": "a"}], "note": null}),
    )
    .expect("decodes a list");
    assert_eq!(
        list.instructions,
        Some(ReplyAllOfInstructions::InputItemList(vec![Item { text: "a".to_string() }]))
    );
    // required: a missing field is an error, not None
    assert!(serde_json::from_value::<Reply>(serde_json::json!({"id": "r", "note": null})).is_err());
}
