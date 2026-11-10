//! Nullable list items and map values decode, serialize and validate, on the crate generated from
//! src/test/resources/nullable_items.yaml. `NullableItemsTest` copies this file into that crate's tests/ and runs
//! cargo.

use nullable_items_fixture::models::holder::{ModeGrid, Modes};
use nullable_items_fixture::models::validation::Validate;
use nullable_items_fixture::models::{Holder, Thing};

#[test]
fn null_items_decode_at_depth_one_and_two() {
    let json = serde_json::json!({
        "map": {"a": null, "b": "x"},
        "list": [null, 3],
        "things": [null, {"name": "ok"}],
        "grid": {"a": null, "b": [null, "xy"]},
        "rows": [[null, 1.5]],
        "modes": [null, "fast"],
        "mode_grid": {"a": [null, "down"]},
        "plain": ["p"]
    });
    let holder: Holder = serde_json::from_value(json.clone()).expect("decodes");
    let map = holder.map.as_ref().unwrap();
    assert_eq!(map["a"], None);
    assert_eq!(map["b"].as_deref(), Some("x"));
    assert_eq!(holder.list.as_deref(), Some(&[None, Some(3)][..]));
    assert!(holder.things.as_ref().unwrap()[0].is_none());
    let grid = holder.grid.as_ref().unwrap();
    assert_eq!(grid["a"], None);
    assert_eq!(grid["b"], Some(vec![None, Some("xy".to_string())]));
    assert_eq!(holder.rows.as_ref().unwrap()[0], vec![None, Some(1.5)]);
    assert_eq!(
        holder.modes.as_deref(),
        Some(&[None, Some(Modes::Fast)][..])
    );
    assert_eq!(
        holder.mode_grid.as_ref().unwrap()["a"],
        vec![None, Some(ModeGrid::Down)]
    );
    holder.validate().expect("valid");
    // a None item is written as null, not skipped
    assert_eq!(serde_json::to_value(&holder).unwrap(), json);
}

#[test]
fn a_none_item_serializes_as_null() {
    let holder = Holder {
        list: Some(vec![None]),
        things: Some(vec![None, Some(Thing::new())]),
        map: Some([("k".to_string(), None)].into_iter().collect()),
        ..Holder::new()
    };
    assert_eq!(
        serde_json::to_value(&holder).unwrap(),
        serde_json::json!({"list": [null], "things": [null, {}], "map": {"k": null}})
    );
}

#[test]
fn validate_skips_null_items_and_checks_the_others() {
    let holder: Holder = serde_json::from_value(serde_json::json!({
        "list": [null, 1],
        "things": [null, {"name": "x"}],
        "grid": {"a": [null, "x"], "b": null}
    }))
    .expect("decodes");
    let errors = holder.validate().expect_err("invalid");
    let paths: Vec<String> = errors.iter().map(|e| e.path.clone()).collect();
    assert_eq!(
        paths,
        vec!["list[1]", "things[1].name", "grid[\"a\"][1]"],
        "{errors:?}"
    );
}
