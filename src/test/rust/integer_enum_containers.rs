//! Integer inline enums in lists and maps at any depth are numbers on the wire, on the crate generated from
//! src/test/resources/integer_enum_containers.yaml. `SmallFixesTest` copies this file into that crate's tests/ and
//! runs cargo.

use small_fixes_fixture::models::holder::{
    Level, List, ListOfMapsOfLists, Map, MapOfLists, Ratio, RequiredMap, Set, Wide,
};
use small_fixes_fixture::models::request::Codes;
use small_fixes_fixture::models::validation::Validate;
use small_fixes_fixture::models::{Big, Holder, Request};

#[test]
fn integer_enums_decode_from_numbers_at_any_depth() {
    let json = serde_json::json!({
        "level": 2,
        "wide": 20,
        "list": [1],
        "set": [2],
        "map": {"a": 2},
        "required_map": {"a": 7},
        "map_of_lists": {"a": [4, 5]},
        "list_of_maps_of_lists": [{"a": [9, 6]}],
        "ratio": ["1.5"],
        "named": 2000
    });
    let holder: Holder = serde_json::from_value(json.clone()).expect("decodes");
    assert_eq!(holder.level, Some(Level::Variant2));
    assert_eq!(holder.wide, Some(Wide::Variant20));
    assert_eq!(holder.list.as_deref(), Some(&[List::Variant1][..]));
    assert!(holder.set.as_ref().unwrap().contains(&Set::Variant2));
    assert_eq!(holder.map.as_ref().unwrap()["a"], Map::Variant2);
    assert_eq!(holder.required_map["a"], RequiredMap::Variant7);
    assert_eq!(
        holder.map_of_lists.as_ref().unwrap()["a"],
        vec![MapOfLists::Variant4, MapOfLists::Variant5]
    );
    assert_eq!(
        holder.list_of_maps_of_lists.as_ref().unwrap()[0]["a"],
        vec![ListOfMapsOfLists::Variant9, ListOfMapsOfLists::Variant6]
    );
    // a number enum stays a string enum (see the schema.enum.integer gap)
    assert_eq!(holder.ratio.as_deref(), Some(&[Ratio::Variant15][..]));
    assert_eq!(holder.named, Some(Big::Variant2000));
    holder.validate().expect("valid");
    // round-trips to the same JSON
    assert_eq!(serde_json::to_value(&holder).unwrap(), json);
}

#[test]
fn the_shapes_of_the_issue_round_trip() {
    let list: Vec<List> = serde_json::from_str("[1]").expect("[1] decodes");
    assert_eq!(serde_json::to_string(&list).unwrap(), "[1]");
    let map: std::collections::HashMap<String, Map> =
        serde_json::from_str(r#"{"a": 2}"#).expect("decodes");
    assert_eq!(serde_json::to_string(&map).unwrap(), r#"{"a":2}"#);
    // strings are not what the API sends
    assert!(serde_json::from_str::<Vec<List>>(r#"["1"]"#).is_err());
}

#[test]
fn a_value_the_spec_does_not_list_is_unknown_at_any_depth() {
    let holder: Holder = serde_json::from_value(serde_json::json!({
        "list": [42],
        "required_map": {"a": 42},
        "list_of_maps_of_lists": [{"a": [42]}]
    }))
    .expect("decodes");
    assert_eq!(holder.list.unwrap(), vec![List::Unknown]);
    assert_eq!(holder.required_map["a"], RequiredMap::Unknown);
    assert_eq!(
        holder.list_of_maps_of_lists.unwrap()[0]["a"],
        vec![ListOfMapsOfLists::Unknown]
    );
    // a request-only enum stays closed
    assert!(serde_json::from_value::<Request>(serde_json::json!({"codes": [42]})).is_err());
    let request: Request =
        serde_json::from_value(serde_json::json!({"codes": [200]})).expect("decodes");
    assert_eq!(request.codes.unwrap(), vec![Codes::Variant200]);
}

#[test]
fn validate_checks_the_containers() {
    let holder: Holder = serde_json::from_value(serde_json::json!({
        "list": [],
        "required_map": {},
        "map_of_lists": {"a": [4, 5, 4]}
    }))
    .expect("decodes");
    let errors = holder.validate().expect_err("invalid");
    let paths: Vec<String> = errors.iter().map(|e| e.path.clone()).collect();
    assert_eq!(paths, vec!["list", "map_of_lists[\"a\"]"], "{errors:?}");
}
