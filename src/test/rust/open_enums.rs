//! What openEnums does at run time, on the crate generated from src/test/resources/open_enums.yaml.
//! `OpenEnumsTest` copies this file into that crate's tests/ and runs cargo.

use open_enums_fixture::models::*;
use serde::de::DeserializeOwned;
use serde_json::{from_str, from_value, json, to_string, to_value, Value};
use std::collections::HashSet;
use std::str::FromStr;

fn decode<T: DeserializeOwned>(value: Value) -> T {
    from_value(value.clone()).unwrap_or_else(|e| panic!("{value}: {e}"))
}

fn error<T: DeserializeOwned + std::fmt::Debug>(value: Value) -> String {
    from_value::<T>(value.clone()).map(|v| panic!("{value} decoded to {v:?}")).unwrap_err().to_string()
}

/// One unknown value deep inside a whole response: it decodes, and the rest of the response stays what it was.
#[test]
fn a_response_with_an_unknown_value_deep_inside_decodes() {
    let body = r#"{
        "status": "active",
        "phase": "published_new_value",
        "deep": { "deeper": { "colors": ["red", "ultraviolet"] } },
        "items": [
            { "kind": "circle", "radius": 3, "hue": "green" },
            { "kind": "hexagon", "sides": 6, "nested": { "a": [1, 2.5, null] } }
        ]
    }"#;
    let envelope: Envelope = from_str(body).unwrap();
    assert_eq!(envelope.status, Status::Active);
    assert_eq!(envelope.phase, envelope::Phase::Unknown);
    assert_eq!(envelope.deep.deeper.colors, vec![Color::Red, Color::Unknown]);
    match &envelope.items[0] {
        Shape::Circle(circle) => assert_eq!(circle.radius, 3),
        other => panic!("{other:?}"),
    }
    assert!(matches!(&envelope.items[1], Shape::Unknown(value) if value["sides"] == 6));
}

#[test]
fn a_new_value_fails_nothing_where_it_used_to_fail_the_response() {
    assert_eq!(decode::<Status>(json!("archived")), Status::Unknown);
    assert_eq!(decode::<ApiError>(json!({"code": "teapot"})).code, ErrorCode::Unknown);
    assert_eq!(decode::<StreamEvent>(json!({"phase": "paused"})).phase, StreamPhase::Unknown);
    assert_eq!(decode::<WebhookEvent>(json!({"state": "stuck"})).state, WebhookState::Unknown);
    assert_eq!(decode::<PageEntry>(json!({"kind": "middle"})).kind, PageKind::Unknown);
    assert_eq!(decode::<Extended>(json!({"level": "mid"})).level, Some(Level::Unknown));
    assert_eq!(decode::<OpaqueBranch>(json!({"kind": "three"})).kind, OpaqueKind::Unknown);
    assert_eq!(decode::<Envelope>(envelope_with("byName", json!({"x": "archived"}))).by_name.unwrap()["x"], Status::Unknown);
    assert_eq!(decode::<Envelope>(envelope_with("phases", json!(["early", "mid"]))).phases.unwrap(), vec![envelope::Phases::Early, envelope::Phases::Unknown]);
}

fn envelope_with(field: &str, value: Value) -> Value {
    let mut base = json!({"status": "active", "phase": "draft", "deep": {"deeper": {"colors": []}}, "items": []});
    base[field] = value;
    base
}

#[test]
fn only_an_unknown_string_goes_to_unknown() {
    for wrong in [json!(1), json!(true), json!(null), json!([]), json!({})] {
        from_value::<Status>(wrong.clone()).expect_err(&wrong.to_string());
    }
}

#[test]
fn an_enum_keeps_what_it_derives() {
    fn copy_eq_hash_ord<T: Copy + Eq + std::hash::Hash + Ord>() {}
    copy_eq_hash_ord::<Status>();
    copy_eq_hash_ord::<envelope::Phase>();
    copy_eq_hash_ord::<Rate>();
    assert!(Status::Done < Status::Unknown);
    assert_eq!([Status::Unknown, Status::Unknown].iter().collect::<HashSet<_>>().len(), 1);
}

/// `Unknown` lost the string: it serializes as a value of its own, stably, and that is not one the API lists.
#[test]
fn unknown_serializes_as_a_string_the_api_does_not_list() {
    let unknown: Status = decode(json!("archived"));
    assert_eq!(to_string(&unknown).unwrap(), r#""unknown""#);
    assert_eq!(decode::<Status>(to_value(unknown).unwrap()), Status::Unknown);
    let envelope: Envelope = decode(envelope_with("status", json!("archived")));
    assert_eq!(to_value(&envelope).unwrap()["status"], "unknown");
    assert_eq!(decode::<Envelope>(to_value(&envelope).unwrap()), envelope);
    assert_eq!(to_string(&envelope::Phase::Unknown).unwrap(), r#""unknown""#);
}

#[test]
fn a_value_called_unknown_keeps_its_variant_and_the_catch_all_gets_another_name_and_string() {
    assert_eq!(decode::<Role>(json!("unknown")), Role::Unknown);
    assert_eq!(decode::<Role>(json!("robot")), Role::UnknownValue);
    assert_eq!(to_value(Role::Unknown).unwrap(), "unknown");
    assert_eq!(to_value(Role::UnknownValue).unwrap(), "unknown_value");
    assert_eq!(Role::UnknownValue.to_string(), "unknown_value");
    assert_eq!(Role::from_str("unknown"), Ok(Role::Unknown));
    assert!(Role::from_str("unknown_value").is_err());
}

#[test]
fn a_value_called_other_is_no_catch_all() {
    assert_eq!(decode::<Flavor>(json!("other")), Flavor::Other);
    assert_eq!(decode::<Flavor>(json!("new")), Flavor::Unknown);
    assert_eq!(Flavor::from_str("other"), Ok(Flavor::Other));
    assert!(Flavor::from_str("unknown").is_err());
}

/// `FromStr` is a caller's strict check, and `Display` is defined.
#[test]
fn from_str_stays_strict_and_display_is_defined() {
    assert_eq!(Status::Unknown.to_string(), "unknown");
    assert_eq!(Status::from_str("active"), Ok(Status::Active));
    for rejected in ["unknown", "Unknown", "archived", ""] {
        let error = Status::from_str(rejected).unwrap_err();
        assert_eq!(error.value(), rejected);
        assert_eq!(error.type_name(), "Status");
    }
    assert!(Status::try_from("unknown").is_err());
    assert!(Status::try_from("unknown".to_string()).is_err());
    // An inline enum gets its Display from the generator; it behaves the same.
    assert_eq!(envelope::Phase::Unknown.to_string(), "unknown");
    assert!(envelope::Phase::from_str("unknown").is_err());
    assert_eq!(envelope::Phase::from_str("final"), Ok(envelope::Phase::Final));
    // Parsing what Display printed for Unknown fails, so it cannot pass for a value.
    assert!(Status::Unknown.to_string().parse::<Status>().is_err());
}

#[test]
fn an_integer_enum_opens_too() {
    assert_eq!(decode::<Rate>(json!(16000)), Rate::Variant16000);
    assert_eq!(decode::<Rate>(json!(48000)), Rate::Unknown);
    assert_eq!(to_value(Rate::Variant24000).unwrap(), 24000);
    assert_eq!(to_value(Rate::Unknown).unwrap(), i64::MAX);
    assert_eq!(decode::<Rate>(to_value(Rate::Unknown).unwrap()), Rate::Unknown);
    assert_eq!(Rate::Unknown.to_string(), "unknown");
    assert_eq!(Rate::from_str("16000"), Ok(Rate::Variant16000));
    assert!(Rate::from_str("48000").is_err());
    assert!(Rate::from_str("unknown").is_err());
    from_value::<Rate>(json!("16000")).expect_err("a string is no integer enum");
}

/// The catch-all of a tagged union holds what came, so this round-trips.
#[test]
fn a_tagged_union_keeps_what_it_does_not_know() {
    let sample = json!({"kind": "hexagon", "sides": 6, "nested": {"a": [1, 2.5, null], "b": {"c": "d"}}});
    let shape: Shape = decode(sample.clone());
    assert_eq!(shape, Shape::Unknown(sample.clone()));
    assert_eq!(to_value(&shape).unwrap(), sample);
    assert_eq!(shape.to_string(), sample.to_string());
    assert_eq!(to_string(&shape).unwrap(), to_string(&sample).unwrap());
}

#[test]
fn a_known_tag_still_decodes_its_variant_and_its_errors_stay() {
    assert!(matches!(decode::<Shape>(json!({"kind": "circle", "radius": 1})), Shape::Circle(_)));
    let message = error::<Shape>(json!({"kind": "circle"}));
    assert!(message.contains("Shape::Circle") && message.contains("radius"), "{message}");
}

#[test]
fn a_missing_or_wrongly_typed_tag_still_errors() {
    for (wrong, wanted) in [
        (json!({"radius": 1}), "missing or null"),
        (json!({"kind": null}), "missing or null"),
        (json!({"kind": 5}), "must be a string"),
        (json!({"kind": ["circle"]}), "must be a string"),
        (json!(5), "expected an object"),
        (json!("circle"), "expected an object"),
    ] {
        let message = error::<Shape>(wrong.clone());
        assert!(message.contains("Shape") && message.contains(wanted), "{wrong}: {message}");
    }
}

#[test]
fn a_branch_called_unknown_keeps_its_variant() {
    assert!(matches!(decode::<Odd>(json!({"type": "unknown"})), Odd::Unknown(_)));
    let sample = json!({"type": "new"});
    assert_eq!(decode::<Odd>(sample.clone()), Odd::UnknownValue(sample));
}

/// A synthesized enum is reachable through its union, and nothing behind it takes a string.
#[test]
fn a_synthesized_enum_opens_with_its_union() {
    assert_eq!(decode::<Hoisted>(json!("auto")), Hoisted::Mode(HoistedModeEnum::Auto));
    assert_eq!(decode::<Hoisted>(json!("sometimes")), Hoisted::Mode(HoistedModeEnum::Unknown));
    assert!(matches!(decode::<Hoisted>(json!({"name": "x"})), Hoisted::Named(_)));
}

/// What a later variant is for stays with that variant: nothing that decoded before decodes to something else.
#[test]
fn a_closed_enum_in_front_of_a_free_string_stays_closed() {
    assert_eq!(decode::<ModelName>(json!("small")), ModelName::Known(ModelNameKnownEnum::Small));
    assert_eq!(decode::<ModelName>(json!("my-model")), ModelName::Custom("my-model".into()));
}

#[test]
fn only_the_last_of_two_enums_opens() {
    assert_eq!(decode::<EitherEnum>(json!("green2")), EitherEnum::Primary(EitherEnumPrimaryEnum::Green2));
    assert_eq!(decode::<EitherEnum>(json!("pink")), EitherEnum::Secondary(EitherEnumSecondaryEnum::Pink));
    assert_eq!(decode::<EitherEnum>(json!("new")), EitherEnum::Secondary(EitherEnumSecondaryEnum::Unknown));
}

#[test]
fn an_enum_that_tells_untagged_structs_apart_stays_closed() {
    assert!(matches!(decode::<Turn>(json!({"role": "assistant", "text": "x"})), Turn::BotTurn(_)));
    assert!(matches!(decode::<Turn>(json!({"role": "user", "text": "x"})), Turn::UserTurn(_)));
    assert!(matches!(decode::<Turn>(json!({"text": "x"})), Turn::UserTurn(_)));
    // A field the later branch does not have tells nothing apart, so it opens.
    match decode::<Turn>(json!({"text": "x", "mood": "bored"})) {
        Turn::UserTurn(turn) => assert_eq!(turn.mood, Some(user_turn::Mood::Unknown)),
        other => panic!("{other:?}"),
    }
    // Both roles are closed in front of the last, whose own role takes what nothing lists.
    match decode::<Turn>(json!({"role": "robot", "text": "x"})) {
        Turn::BotTurn(turn) => assert_eq!(turn.role, bot_turn::Role::Unknown),
        other => panic!("{other:?}"),
    }
}

#[test]
fn an_enum_that_tells_apart_variants_sharing_a_tag_stays_closed() {
    assert!(matches!(decode::<SharedTag>(json!({"type": "message", "author": "user"})), SharedTag::InMessage(_)));
    assert!(matches!(decode::<SharedTag>(json!({"type": "message", "author": "assistant"})), SharedTag::OutMessage(_)));
    assert!(matches!(decode::<SharedTag>(json!({"type": "tool_call", "name": "x"})), SharedTag::ToolCall(_)));
    assert!(matches!(decode::<SharedTag>(json!({"type": "message", "author": "assistant", "tone": "loud"})), SharedTag::OutMessage(_)));
    let sample = json!({"type": "new", "x": 1});
    assert_eq!(decode::<SharedTag>(sample.clone()), SharedTag::Unknown(sample));
    // One enum that rejects the later variant's values is enough: the other fields can open.
    match decode::<SharedTag>(json!({"type": "message", "author": "user", "tone": "new"})) {
        SharedTag::InMessage(message) => assert_eq!(message.tone, Some(Tone::Unknown)),
        other => panic!("{other:?}"),
    }
    // The last variant tried can take an unknown author, and a state of its own.
    match decode::<SharedTag>(json!({"type": "message", "author": "robot", "state": "new"})) {
        SharedTag::OutMessage(message) => {
            assert_eq!(message.author, out_message::Author::Unknown);
            assert_eq!(message.state, Some(out_message::State::Unknown));
        }
        other => panic!("{other:?}"),
    }
}

#[test]
fn a_tagged_union_in_front_of_a_free_tag_stays_closed() {
    assert!(matches!(decode::<Wrapped>(json!({"type": "cat"})), Wrapped::Animal(_)));
    assert!(matches!(decode::<Wrapped>(json!({"type": "whatever", "blob": "x"})), Wrapped::FreeForm(_)));
}

#[test]
fn a_struct_in_front_of_a_map_keeps_rejecting() {
    assert!(matches!(decode::<Loose>(json!({"grade": "a"})), Loose::Strict(_)));
    assert!(matches!(decode::<Loose>(json!({"grade": "z"})), Loose::Rest(_)));
}

#[test]
fn an_untagged_union_is_left_as_it_is() {
    assert_eq!(decode::<Untagged>(json!("active")), Untagged::Status(Status::Active));
    assert_eq!(decode::<Untagged>(json!(3)), Untagged::Integer(3));
    assert_eq!(decode::<Untagged>(json!("archived")), Untagged::Status(Status::Unknown));
    from_value::<Untagged>(json!({"x": 1})).expect_err("no variant takes an object");
}

/// A type only a request holds has nothing to tolerate.
#[test]
fn what_only_a_request_holds_stays_closed() {
    from_value::<Priority>(json!("whenever")).unwrap_err();
    from_value::<order::Urgency>(json!("whenever")).unwrap_err();
    let message = error::<Payment>(json!({"method": "cash"}));
    assert!(message.contains("unknown `method` value"), "{message}");
    // What both hold is open in both.
    assert_eq!(decode::<Status>(json!("archived")), Status::Unknown);
    assert_eq!(to_value(Priority::Rush).unwrap(), "rush");
}
