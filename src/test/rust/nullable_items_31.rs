//! The OpenAPI 3.1 forms of nullable items decode and serialize, on the crate generated from
//! src/test/resources/nullable_items_31.yaml. `NullableItemsTest` copies this file into that crate's tests/ and runs
//! cargo.

use nullable_items_fixture::models::holder::Modes;
use nullable_items_fixture::models::validation::Validate;
use nullable_items_fixture::models::Holder;

#[test]
fn null_items_of_the_31_forms_decode_and_round_trip() {
    let json = serde_json::json!({
        "map": {"a": null, "b": "x"},
        "list": [null, 1],
        "things": [null, {"name": "n"}],
        "grid": {"a": [null, "y"]},
        "modes": [null, "slow"],
        "free": [null, {"k": 1}]
    });
    let holder: Holder = serde_json::from_value(json.clone()).expect("decodes");
    assert_eq!(holder.map.as_ref().unwrap()["a"], None);
    assert_eq!(holder.list.as_deref(), Some(&[None, Some(1)][..]));
    assert!(holder.things.as_ref().unwrap()[0].is_none());
    assert_eq!(
        holder.grid.as_ref().unwrap()["a"],
        vec![None, Some("y".to_string())]
    );
    assert_eq!(
        holder.modes.as_deref(),
        Some(&[None, Some(Modes::Slow)][..])
    );
    // a free-form item holds null itself
    assert_eq!(holder.free.as_ref().unwrap()[0], serde_json::Value::Null);
    holder.validate().expect("valid");
    assert_eq!(serde_json::to_value(&holder).unwrap(), json);
}
