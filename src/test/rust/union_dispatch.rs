//! What tag dispatch does at run time, on the crate generated from src/test/resources/union_dispatch.yaml.
//! `UnionDispatchRustTest` copies this file into that crate's tests/ and runs cargo.

use dispatch_fixture::models::*;
use serde::de::{Deserializer, MapAccess, SeqAccess, Visitor};
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use serde_json::{json, Value};

fn decode<T: DeserializeOwned>(json: &str) -> T {
    serde_json::from_str(json).unwrap_or_else(|e| panic!("{json}: {e}"))
}

fn error<T: DeserializeOwned + std::fmt::Debug>(json: &str) -> String {
    serde_json::from_str::<T>(json)
        .map(|value| panic!("{json} decoded to {value:?}"))
        .unwrap_err()
        .to_string()
}

fn same_json<T: Serialize>(value: &T, json: &str) {
    assert_eq!(serde_json::to_value(value).unwrap(), serde_json::from_str::<Value>(json).unwrap());
}

/// Counts the keys that appear twice in one object, anywhere in a document.
struct Repeated(usize);

impl<'de> Deserialize<'de> for Repeated {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct Count;
        impl<'de> Visitor<'de> for Count {
            type Value = Repeated;
            fn expecting(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                f.write_str("any JSON")
            }
            fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Repeated, A::Error> {
                let (mut keys, mut repeated) = (std::collections::HashSet::new(), 0);
                while let Some(key) = map.next_key::<String>()? {
                    repeated += usize::from(!keys.insert(key)) + map.next_value::<Repeated>()?.0;
                }
                Ok(Repeated(repeated))
            }
            fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<Repeated, A::Error> {
                let mut repeated = 0;
                while let Some(item) = seq.next_element::<Repeated>()? {
                    repeated += item.0;
                }
                Ok(Repeated(repeated))
            }
            fn visit_bool<E>(self, _: bool) -> Result<Repeated, E> {
                Ok(Repeated(0))
            }
            fn visit_u64<E>(self, _: u64) -> Result<Repeated, E> {
                Ok(Repeated(0))
            }
            fn visit_i64<E>(self, _: i64) -> Result<Repeated, E> {
                Ok(Repeated(0))
            }
            fn visit_f64<E>(self, _: f64) -> Result<Repeated, E> {
                Ok(Repeated(0))
            }
            fn visit_str<E>(self, _: &str) -> Result<Repeated, E> {
                Ok(Repeated(0))
            }
            fn visit_unit<E>(self) -> Result<Repeated, E> {
                Ok(Repeated(0))
            }
        }
        deserializer.deserialize_any(Count)
    }
}

#[test]
fn the_tag_selects_the_variant() {
    assert!(matches!(decode::<Shape>(r#"{"kind":"circle","radius":1}"#), Shape::Circle(_)));
    assert!(matches!(decode::<Shape>(r#"{"kind":"square","side":1.5}"#), Shape::Square(_)));
    assert!(matches!(decode::<Event>(r#"{"type":"stopped"}"#), Event::StoppedEvent(_)));
    assert!(matches!(decode::<EventAlias>(r#"{"type":"started","at":1}"#), EventAlias::StartedEvent(_)));
    assert!(matches!(decode::<Part>(r#"{"type":"image","url":"x"}"#), Part::ImagePart(_)));
    assert!(matches!(decode::<TwoCandidates>(r#"{"type":"same","object":"b"}"#), TwoCandidates::CandidateB(_)));
}

#[test]
fn a_struct_receives_its_tag() {
    let Shape::Circle(circle) = decode::<Shape>(r#"{"kind":"circle","radius":1}"#) else {
        panic!("not a circle");
    };
    assert_eq!(serde_json::to_value(circle.kind).unwrap(), json!("circle"));
}

#[test]
fn the_tag_goes_out_once_and_the_bytes_are_the_structs() {
    for (json, tag) in [
        (r#"{"kind":"circle","radius":1}"#, "kind"),
        (r#"{"type":"web_search"}"#, "type"),
        (r#"{"type":"message","role":"user","content":["x"],"status":"done"}"#, "type"),
        (r#"{"type":"tool_call","name":"x"}"#, "type"),
    ] {
        let text = match tag {
            "kind" => {
                let shape = decode::<Shape>(json);
                let Shape::Circle(circle) = &shape else { panic!() };
                let text = serde_json::to_string(&shape).unwrap();
                assert_eq!(text, serde_json::to_string(circle).unwrap());
                text
            }
            _ if json.contains("web_search") => serde_json::to_string(&decode::<Tool>(json)).unwrap(),
            _ => serde_json::to_string(&decode::<InputItem>(json)).unwrap(),
        };
        assert_eq!(decode::<Repeated>(&text).0, 0, "{text}");
        assert_eq!(text.matches(&format!("\"{tag}\":")).count(), 1, "{text}");
    }
}

#[test]
fn every_value_of_a_multi_valued_tag_selects_the_variant_and_stays() {
    for value in ["web_search", "web_search_2025_08_26"] {
        let json = format!(r#"{{"type":"{value}"}}"#);
        let tool = decode::<Tool>(&json);
        assert!(matches!(tool, Tool::WebSearchTool(_)));
        same_json(&tool, &json);
        // And through the branch that is a union.
        let content = decode::<Content>(&json);
        assert!(matches!(content, Content::Tool(Tool::WebSearchTool(_))));
        same_json(&content, &json);
    }
}

#[test]
fn a_value_on_two_variants_tries_them_in_order_for_that_value_only() {
    let user = r#"{"type":"message","role":"user","content":["x"]}"#;
    assert!(matches!(decode::<Item>(user), Item::InputMessage(_)));
    let assistant = r#"{"type":"message","role":"assistant","content":["x"]}"#;
    assert!(matches!(decode::<Item>(assistant), Item::OutputMessage(_)));

    let failed = error::<Item>(r#"{"type":"message","role":"user","content":"x"}"#);
    assert!(failed.contains("Item: no variant decodes `type` \"message\""), "{failed}");
    assert!(failed.contains("InputMessage: invalid type: string"), "{failed}");
    assert!(failed.contains("OutputMessage: invalid type: string"), "{failed}");
    // A response holds OutputMessage, so its role is open: a role no variant lists still decodes.
    let unknown = r#"{"type":"message","role":"nobody","content":["x"]}"#;
    assert!(matches!(decode::<Item>(unknown), Item::OutputMessage(_)));

    // Another value does not try them.
    let other = error::<Item>(r#"{"type":"tool_call","role":"user","content":["x"]}"#);
    assert_eq!(other, "Item::ToolCall: missing field `name`");
}

#[test]
fn a_missing_tag_tries_the_variants_that_allow_it() {
    assert!(matches!(decode::<Item>(r#"{"role":"user","content":["x"]}"#), Item::InputMessage(_)));
    // OutputMessage requires the tag, so it is not tried.
    let failed = error::<Item>(r#"{"role":"assistant","content":["x"]}"#);
    assert_eq!(failed, "Item::InputMessage: unknown variant `assistant`, expected `user` or `system`");
    // Null is no tag either.
    assert!(matches!(decode::<Item>(r#"{"type":null,"role":"user","content":["x"]}"#), Item::InputMessage(_)));
    assert!(matches!(decode::<InputItem>(r#"{"type":null,"id":"x"}"#), InputItem::ItemReference(_)));
    assert!(matches!(decode::<InputItem>(r#"{"id":"x"}"#), InputItem::ItemReference(_)));
    assert!(matches!(decode::<InputItem>(r#"{"role":"user","content":"x"}"#), InputItem::EasyMessage(_)));

    // Every variant requires it.
    assert_eq!(error::<Part>(r#"{"text":"x"}"#), "Part: tag `type` is missing or null");
    assert_eq!(error::<Part>(r#"{"type":null,"text":"x"}"#), "Part: tag `type` is missing or null");
}

#[test]
fn a_variant_that_is_a_union_is_dispatched_through_and_keeps_its_fields() {
    let json = r#"{"type":"message","role":"user","content":["x"],"status":"done"}"#;
    let item = decode::<InputItem>(json);
    match &item {
        InputItem::Item(Item::InputMessage(message)) => assert_eq!(message.status.as_deref(), Some("done")),
        other => panic!("{other:?}"),
    }
    same_json(&item, json);

    assert!(matches!(
        decode::<InputItem>(r#"{"type":"tool_call","name":"x"}"#),
        InputItem::Item(Item::ToolCall(_))
    ));
    assert!(matches!(
        decode::<InputItem>(r#"{"type":"message","role":"assistant","content":["x"]}"#),
        InputItem::Item(Item::OutputMessage(_))
    ));
    // Without a tag too.
    assert!(matches!(
        decode::<InputItem>(r#"{"role":"user","content":["x"],"status":"done"}"#),
        InputItem::Item(Item::InputMessage(_))
    ));
    // An error says where it came from.
    assert_eq!(
        error::<InputItem>(r#"{"type":"tool_call"}"#),
        "InputItem::Item: Item::ToolCall: missing field `name`"
    );
}

#[test]
fn an_unknown_tag_names_the_union_the_tag_and_the_value() {
    assert_eq!(error::<Shape>(r#"{"kind":"triangle"}"#), "Shape: unknown `kind` value \"triangle\"");
    assert_eq!(error::<Part>(r#"{"type":"audio"}"#), "Part: unknown `type` value \"audio\"");
    // A response holds InputItem, so it keeps a tag it does not know instead.
    assert!(matches!(decode::<InputItem>(r#"{"type":"nope"}"#), InputItem::Unknown(_)));
    // Nested in a struct, serde_json adds where.
    #[derive(Debug, Deserialize)]
    #[allow(dead_code)]
    struct Holder {
        shape: Shape,
    }
    let nested = error::<Holder>(r#"{"shape":{"kind":"triangle"}}"#);
    assert!(nested.starts_with("Shape: unknown `kind` value \"triangle\" at line 1"), "{nested}");
}

#[test]
fn an_input_that_is_no_object_is_a_clear_error() {
    assert_eq!(error::<Shape>("1"), "Shape: expected an object with tag `kind`, got a number");
    assert_eq!(error::<Shape>(r#""x""#), "Shape: expected an object with tag `kind`, got a string");
    assert_eq!(error::<Shape>("[1]"), "Shape: expected an object with tag `kind`, got an array");
    assert_eq!(error::<Shape>("null"), "Shape: expected an object with tag `kind`, got null");
    assert!(serde_json::from_str::<Option<Shape>>("null").unwrap().is_none());
}

#[test]
fn the_other_branches_of_a_mixed_union_still_work() {
    assert!(matches!(decode::<ToolChoice>(r#""auto""#), ToolChoice::TextVariant(_)));
    assert!(matches!(decode::<ToolChoice>(r#"["a","b"]"#), ToolChoice::ArrayOfStrings(_)));
    assert!(matches!(decode::<ToolChoice>(r#"{"type":"named","name":"x"}"#), ToolChoice::NamedChoice(_)));
    same_json(&decode::<ToolChoice>(r#""none""#), r#""none""#);
    let failed = error::<ToolChoice>("1");
    assert!(failed.starts_with("ToolChoice: no variant decodes a number: TextVariant: "), "{failed}");
    assert!(failed.contains("ArrayOfStrings: "), "{failed}");
    assert_eq!(error::<ToolChoice>(r#"{"type":"other"}"#), "ToolChoice: unknown `type` value \"other\"");
    assert_eq!(error::<ToolChoice>(r#"{"name":"x"}"#), "ToolChoice: tag `type` is missing or null");
}

#[test]
fn a_tag_of_the_wrong_kind_is_a_clear_error() {
    assert_eq!(error::<Shape>(r#"{"kind":5}"#), "Shape: tag `kind` must be a string, got a number");
    assert_eq!(error::<Shape>(r#"{"kind":true}"#), "Shape: tag `kind` must be a string, got a boolean");
    assert_eq!(error::<Shape>(r#"{"kind":["circle"]}"#), "Shape: tag `kind` must be a string, got an array");
    assert_eq!(error::<Shape>(r#"{"kind":{}}"#), "Shape: tag `kind` must be a string, got an object");
}

#[test]
fn the_error_is_the_chosen_variants_not_data_did_not_match() {
    assert_eq!(error::<Shape>(r#"{"kind":"circle"}"#), "Shape::Circle: missing field `radius`");
    assert_eq!(
        error::<Shape>(r#"{"kind":"circle","radius":"big"}"#),
        "Shape::Circle: invalid type: string \"big\", expected i64"
    );
    assert_eq!(error::<Scalar>("true"), "data did not match any variant of untagged enum Scalar");
}

#[test]
fn an_inline_branch_of_a_branch_decodes_by_the_tag() {
    assert!(matches!(
        decode::<Stream>(r#"{"event":"run.done","data":"x"}"#),
        Stream::RunEvent(RunEvent::Object1(_))
    ));
    assert!(matches!(decode::<Stream>(r#"{"event":"done"}"#), Stream::DoneEvent(_)));
}

#[test]
fn a_union_without_a_tag_stays_untagged() {
    assert!(matches!(decode::<Scalar>("1"), Scalar::Integer(_)));
    assert!(matches!(decode::<NoCommonField>(r#"{"left":"x"}"#), NoCommonField::Left(_)));
    assert!(matches!(decode::<SameRoles>(r#"{"role":"user"}"#), SameRoles::RoleA(_)));
    assert!(matches!(decode::<SomeOptional>(r#"{"kind":"b"}"#), SomeOptional::RequiredKind(_)));
    assert!(matches!(decode::<Mixed>(r#""x""#), Mixed::Text(_)));
    assert!(matches!(decode::<Mixed>(r#"{"type":"text","text":"x"}"#), Mixed::TextPart(_)));
}

#[test]
fn numbers_arrive_as_they_would_at_the_struct() {
    for value in ["9007199254740993", "9223372036854775807", "-9223372036854775808"] {
        let json = format!(r#"{{"type":"integer","value":{value}}}"#);
        let direct = decode::<IntegerMeasure>(&json);
        let Measure::IntegerMeasure(through) = decode::<Measure>(&json) else { panic!() };
        assert_eq!(through.value, direct.value);
        assert_eq!(through.value.to_string(), value);
        assert_eq!(serde_json::to_string(&decode::<Measure>(&json)).unwrap(), json);
    }
    assert!(serde_json::from_str::<Measure>(r#"{"type":"integer","value":9223372036854775808}"#).is_err());
    assert!(serde_json::from_str::<IntegerMeasure>(r#"{"type":"integer","value":9223372036854775808}"#).is_err());

    for value in [
        "0.1", "1.7976931348623157e308", "5e-324", "-0.0", "1.0", "1", "100", "9007199254740993", "1e2",
        "18446744073709551615", "18446744073709551616", "0.30000000000000004", "123456789.12345678",
    ] {
        let json = format!(r#"{{"type":"number","value":{value}}}"#);
        let direct = decode::<NumberMeasure>(&json);
        let Measure::NumberMeasure(through) = decode::<Measure>(&json) else { panic!() };
        assert_eq!(through.value.to_bits(), direct.value.to_bits(), "{value}");

        // A value of any type keeps what serde_json would hold on its own.
        let json = format!(r#"{{"type":"raw","value":{value}}}"#);
        let direct = decode::<RawMeasure>(&json);
        let Measure::RawMeasure(through) = decode::<Measure>(&json) else { panic!() };
        assert_eq!(through.value, direct.value, "{value}");
        assert_eq!(
            serde_json::to_string(&through).unwrap(),
            serde_json::to_string(&direct).unwrap(),
            "{value}"
        );
    }
    let raw = r#"{"type":"raw","value":{"b":[1,2.5,"x",null,true],"a":18446744073709551615}}"#;
    assert_eq!(
        serde_json::to_string(&decode::<Measure>(raw)).unwrap(),
        serde_json::to_string(&decode::<RawMeasure>(raw)).unwrap()
    );
}

#[test]
fn a_repeated_key_is_the_last_one() {
    // serde_json::Value keeps the last of a repeated key, so a struct no longer sees the repeat. Documented, and the
    // same value the tag was read from.
    assert!(matches!(
        decode::<Shape>(r#"{"kind":"square","kind":"circle","radius":1}"#),
        Shape::Circle(_)
    ));
    assert!(serde_json::from_str::<Circle>(r#"{"kind":"circle","kind":"circle","radius":1}"#).is_err());
}
