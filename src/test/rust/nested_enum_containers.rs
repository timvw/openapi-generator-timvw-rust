//! Inline enums two or more lists or maps deep decode and validate, on the crate generated from
//! src/test/resources/nested_enum_containers.yaml. `SmallFixesTest` copies this file into that crate's tests/ and runs
//! cargo.

use small_fixes_fixture::models::get_named_200_response::Grid;
use small_fixes_fixture::models::holder::{
    ListOfMapsOfMaps, MapOfLists, MapOfListsOfLists, MapOfMaps, RequiredGrid, UniqueLists, UniqueSets,
};
use small_fixes_fixture::models::validation::Validate;
use small_fixes_fixture::models::{GetNamed200Response, Holder};

#[test]
fn nested_containers_of_inline_enums_decode() {
    let holder: Holder = serde_json::from_value(serde_json::json!({
        "map_of_maps": {"a": {"b": "foo_bar", "c": "baz"}},
        "map_of_lists": {"a": ["up", "down"]},
        "map_of_lists_of_lists": {"a": [["left"], ["right", "left"]]},
        "list_of_maps_of_maps": [{"a": {"b": "a"}}],
        "required_grid": {"a": {"b": "high"}},
        "unique_sets": {"a": ["one", "two"]},
        "unique_lists": [["x"], ["y"]],
        "something": "else"
    }))
    .expect("decodes");
    assert_eq!(holder.map_of_maps.as_ref().unwrap()["a"]["b"], MapOfMaps::FooBar);
    assert_eq!(holder.map_of_lists.as_ref().unwrap()["a"], vec![MapOfLists::Up, MapOfLists::Down]);
    assert_eq!(
        holder.map_of_lists_of_lists.as_ref().unwrap()["a"][1],
        vec![MapOfListsOfLists::Right, MapOfListsOfLists::Left]
    );
    assert_eq!(holder.list_of_maps_of_maps.as_ref().unwrap()[0]["a"]["b"], ListOfMapsOfMaps::A);
    assert_eq!(holder.required_grid["a"]["b"], RequiredGrid::High);
    assert!(holder.unique_sets.as_ref().unwrap()["a"].contains(&UniqueSets::Two));
    assert_eq!(holder.unique_lists.as_ref().unwrap()[1], vec![UniqueLists::Y]);
    holder.validate().expect("valid");
    // a value the spec does not list decodes as Unknown at any depth
    let holder: Holder =
        serde_json::from_value(serde_json::json!({"required_grid": {"a": {"b": "new"}}})).expect("decodes");
    assert_eq!(holder.required_grid["a"]["b"], RequiredGrid::Unknown);
    // and round-trips
    let json = serde_json::to_value(Holder::new(holder.required_grid.clone())).unwrap();
    assert_eq!(json, serde_json::json!({"required_grid": {"a": {"b": "unknown"}}}));

    let named: GetNamed200Response =
        serde_json::from_value(serde_json::json!({"grid": {"a": {"b": "dark"}}})).expect("decodes");
    assert_eq!(named.grid.unwrap()["a"]["b"], Grid::Dark);
}

#[test]
fn every_level_is_validated() {
    let holder: Holder = serde_json::from_value(serde_json::json!({
        "map_of_lists_of_lists": {"a": [], "b": [["left", "right", "left"]]},
        "list_of_maps_of_maps": [{"a": {"x": "a", "y": "b", "z": "a"}}],
        "required_grid": {},
        "unique_lists": [["x"], ["x"]]
    }))
    .expect("decodes");
    let errors = holder.validate().expect_err("invalid");
    let paths: Vec<String> = errors.iter().map(|e| e.path.clone()).collect();
    assert_eq!(
        paths,
        vec![
            "map_of_lists_of_lists[\"a\"]",
            "map_of_lists_of_lists[\"b\"][0]",
            "list_of_maps_of_maps[0][\"a\"]",
            "unique_lists",
        ],
        "{errors:?}"
    );
}
