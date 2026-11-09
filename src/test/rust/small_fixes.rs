//! Maps and lists of inline enums decode, on the crate generated from src/test/resources/map_enum_values.yaml.
//! `SmallFixesTest` copies this file into that crate's tests/ and runs cargo.

use small_fixes_fixture::models::holder::{ArrayLabels, LabelMaps, Labels, RequiredLabels};
use small_fixes_fixture::models::Holder;

#[test]
fn maps_and_lists_of_inline_enums_decode() {
    let holder: Holder = serde_json::from_value(serde_json::json!({
        "labels": {"a": "red"},
        "required_labels": {"b": "high"},
        "array_labels": {"c": ["up", "down"]},
        "label_maps": [{"d": "left"}, {"e": "right"}]
    }))
    .expect("decodes");
    assert_eq!(holder.labels.unwrap()["a"], Labels::Red);
    assert_eq!(holder.required_labels["b"], RequiredLabels::High);
    assert_eq!(holder.array_labels.unwrap()["c"], vec![ArrayLabels::Up, ArrayLabels::Down]);
    let maps = holder.label_maps.unwrap();
    assert_eq!(maps[0]["d"], LabelMaps::Left);
    assert_eq!(maps[1]["e"], LabelMaps::Right);
}
