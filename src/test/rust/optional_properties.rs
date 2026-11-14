//! `optionalProperties` makes required properties the API omits optional, on the crate generated from
//! src/test/resources/optional_properties.yaml with
//! `optionalProperties: OutputText.logprobs|Usage.cache_write_tokens|Reply.access_programs`
//! `|ResponseUsage.input_tokens_details.cache_write_tokens|ResponseUsage.output_tokens_details.audio_tokens`
//! `|ResponseUsage.totals.cached`.
//! `OptionalPropertiesTest` copies this file into that crate's tests/ and runs cargo.

use optional_properties::models::{
    OutputText, Reply, ResponseUsage, ResponseUsageInputTokensDetails, TokenTotals, Usage,
};
use serde_json::json;

#[test]
fn a_body_without_the_properties_decodes() {
    let reply: Reply = serde_json::from_value(json!({
        "id": "r",
        "output": {"text": "hi"},
        "usage": {"input_tokens": 3, "cached_tokens": 0}
    }))
    .expect("decodes without the properties");
    assert_eq!(reply.output.logprobs, None);
    assert_eq!(reply.usage.cache_write_tokens, None);
    assert_eq!(reply.access_programs, None);
    // and they are left out again
    assert_eq!(
        serde_json::to_value(&reply).unwrap(),
        json!({"id": "r", "output": {"text": "hi"}, "usage": {"input_tokens": 3, "cached_tokens": 0}})
    );
}

#[test]
fn a_body_with_the_properties_decodes_and_round_trips() {
    let body = json!({
        "id": "r",
        "output": {"text": "hi", "logprobs": [0.5]},
        "usage": {"input_tokens": 3, "cached_tokens": 0, "cache_write_tokens": 7},
        "access_programs": ["a"]
    });
    let reply: Reply = serde_json::from_value(body.clone()).expect("decodes with the properties");
    assert_eq!(reply.output.logprobs, Some(vec![0.5]));
    assert_eq!(reply.usage.cache_write_tokens, Some(7));
    assert_eq!(reply.access_programs, Some(Some(vec!["a".to_string()])));
    assert_eq!(serde_json::to_value(&reply).unwrap(), body);
}

#[test]
fn a_required_nullable_property_still_decodes_null() {
    let body = json!({
        "id": "r",
        "output": {"text": "hi"},
        "usage": {"input_tokens": 3, "cached_tokens": 0},
        "access_programs": null
    });
    let reply: Reply = serde_json::from_value(body.clone()).expect("decodes null");
    assert_eq!(reply.access_programs, Some(None));
    assert_eq!(serde_json::to_value(&reply).unwrap(), body);
}

#[test]
fn the_other_required_properties_stay_required() {
    assert!(serde_json::from_value::<OutputText>(json!({"logprobs": []})).is_err());
    assert!(serde_json::from_value::<Usage>(json!({"input_tokens": 3})).is_err());
    let text = OutputText::new("hi".to_string());
    assert_eq!(text.logprobs, None);
}

#[test]
fn a_body_without_the_nested_properties_decodes() {
    let body = json!({
        "input_tokens": 3,
        "input_tokens_details": {"cached_tokens": 1},
        "output_tokens_details": {"reasoning_tokens": 2},
        "totals": {"total": 5}
    });
    let usage: ResponseUsage = serde_json::from_value(body.clone()).expect("decodes without them");
    assert_eq!(usage.input_tokens_details.cached_tokens, 1);
    assert_eq!(usage.input_tokens_details.cache_write_tokens, None);
    let output = usage.output_tokens_details.as_ref().expect("present");
    assert_eq!(output.reasoning_tokens, 2);
    assert_eq!(output.audio_tokens, None);
    assert_eq!(usage.totals.cached, None);
    assert_eq!(serde_json::to_value(&usage).unwrap(), body);
}

#[test]
fn a_body_with_the_nested_properties_decodes_and_round_trips() {
    let body = json!({
        "input_tokens": 3,
        "input_tokens_details": {"cached_tokens": 1, "cache_write_tokens": 4},
        "output_tokens_details": {"reasoning_tokens": 2, "audio_tokens": 6},
        "totals": {"total": 5, "cached": 1}
    });
    let usage: ResponseUsage = serde_json::from_value(body.clone()).expect("decodes with them");
    assert_eq!(usage.input_tokens_details.cache_write_tokens, Some(4));
    assert_eq!(usage.totals.cached, Some(1));
    assert_eq!(serde_json::to_value(&usage).unwrap(), body);
    // the nested object is reached through a component property
    let reply: Reply = serde_json::from_value(json!({
        "id": "r",
        "output": {"text": "hi"},
        "usage": {"input_tokens": 3, "cached_tokens": 0},
        "response_usage": body
    }))
    .expect("decodes in a reply");
    assert!(reply.response_usage.is_some());
}

#[test]
fn the_other_nested_required_properties_stay_required() {
    assert!(serde_json::from_value::<ResponseUsageInputTokensDetails>(json!({"cache_write_tokens": 4})).is_err());
    assert!(serde_json::from_value::<TokenTotals>(json!({"cached": 1})).is_err());
    let details = ResponseUsageInputTokensDetails::new(1);
    assert_eq!(details.cache_write_tokens, None);
}
