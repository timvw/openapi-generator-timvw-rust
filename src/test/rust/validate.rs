//! What the generated `validate()` does at run time, on the models of src/test/resources/validate.yaml.
//! ValidateTest#whatItDoesAtRunTime runs it with cargo.

use serde_json::json;
use validate_fixture::models::{self, Content, Request, Shape};
use validate_fixture::models::validation::{Rule, Validate, ValidationError};

fn valid() -> Request {
    serde_json::from_value(json!({
        "messages": [
            {"role": "user", "content": "héllo", "name": "bob42"},
            {"role": "assistant", "content": "hi"}
        ],
        "count": 95,
        "temperature": null,
        "top_p": 0.3,
        "ratio": 0.1,
        "half": 1,
        "tags": ["a", "b"],
        "points": [{"x": 1.0}, {"x": 2.0}],
        "metadata": {"k": "abc"},
        "ratings": {"a": "low"},
        "rating_lists": {"a": ["low"]},
        "stops": [["a"], ["b", "c"]],
        "content": [{"role": "user", "content": "x"}],
        "shape": {"kind": "Circle", "radius": 0.5},
        "node": {"value": 0, "children": [{"value": 1, "next": {"value": 2}}]},
        "validate": "ok",
        "lookahead": "anything: the pattern is not checked",
        "payload": {"free": "form"},
        "colors": ["red"],
        "holder": {"inner": {"role": "user", "content": "x"}, "plain": {"x": 1.0}},
        "clash": {"name": "n"},
        "error": {"code": 10}
    }))
    .expect("the valid request decodes")
}

/// The error `change` causes in an otherwise valid request.
fn error(change: impl FnOnce(&mut Request)) -> ValidationError {
    let mut request = valid();
    change(&mut request);
    request.validate().expect_err("the change breaks a rule")
}

fn assert_error(change: impl FnOnce(&mut Request), path: &str, rule: Rule, actual: &str) {
    let e = error(change);
    assert_eq!((e.path.as_str(), &e.rule, e.actual.as_str()), (path, &rule, actual), "{e}");
}

fn message(content: &str) -> models::Message {
    serde_json::from_value(json!({"role": "user", "content": content})).unwrap()
}

#[test]
fn a_valid_request_passes() {
    assert_eq!(valid().validate(), Ok(()));
}

#[test]
fn optional_fields_left_out_pass() {
    let request = Request::new(vec![message("x")], 5);
    assert_eq!(request.validate(), Ok(()));
}

#[test]
fn string_length_counts_characters() {
    assert_error(|r| r.messages[1].content = String::new(), "messages[1].content", Rule::MinLength(1), "length 0");
    assert_error(|r| r.messages[1].content = "é".repeat(11), "messages[1].content", Rule::MaxLength(10), "length 11");
    assert_eq!(error(|r| r.messages[1].content = "é".repeat(11)).to_string(),
        "messages[1].content: length 11 breaks maxLength 10");
    let mut ok = valid();
    ok.messages[1].content = "é".repeat(10);
    assert_eq!(ok.validate(), Ok(()));
}

#[test]
fn pattern() {
    assert_error(|r| r.messages[0].name = Some("Bob".into()), "messages[0].name", Rule::Pattern(r"^[a-z]+\d*$"),
        "\"Bob\"");
}

#[test]
fn a_pattern_regex_cannot_compile_is_not_checked() {
    let mut ok = valid();
    ok.lookahead = Some("b".into());
    assert_eq!(ok.validate(), Ok(()));
}

#[test]
fn integer_bounds_and_multiple_of() {
    assert_error(|r| r.count = 0, "count", Rule::Minimum(1.0), "0");
    assert_error(|r| r.count = 100, "count", Rule::ExclusiveMaximum(100.0), "100");
    assert_error(|r| r.count = 7, "count", Rule::MultipleOf(5.0), "7");
    assert_error(|r| r.half = Some(0), "half", Rule::Minimum(1.0), "0");
    assert_error(|r| r.error = Some(models::ValidationError { code: Some(11) }), "error.code", Rule::Maximum(10.0),
        "11");
}

#[test]
fn number_bounds_and_multiple_of_with_a_tolerance() {
    assert_error(|r| r.temperature = Some(Some(2.5)), "temperature", Rule::Maximum(2.0), "2.5");
    assert_error(|r| r.temperature = Some(Some(-0.1)), "temperature", Rule::Minimum(0.0), "-0.1");
    assert_error(|r| r.top_p = Some(0.0), "top_p", Rule::ExclusiveMinimum(0.0), "0");
    assert_error(|r| r.top_p = Some(0.35), "top_p", Rule::MultipleOf(0.1), "0.35");
    for fine in [0.1, 0.3, 0.7, 1.1, 12.3] {
        let mut ok = valid();
        ok.top_p = Some(fine);
        assert_eq!(ok.validate(), Ok(()), "{fine}");
    }
    // f32: 0.1 is the bound's own f32 value
    assert_error(|r| r.ratio = Some(0.11), "ratio", Rule::Maximum(0.1f32 as f64), "0.11");
    assert_error(|r| r.shape = Some(Shape::Circle(models::Circle::new(models::circle::Kind::Circle, 0.0))),
        "shape.radius", Rule::ExclusiveMinimum(0.0), "0");
}

#[test]
fn item_counts_and_unique_items() {
    assert_error(|r| r.messages.clear(), "messages", Rule::MinItems(1), "0 items");
    assert_error(|r| r.messages = vec![message("a"); 4], "messages", Rule::MaxItems(3), "4 items");
    assert_error(|r| r.tags = Some(vec!["a".into(), "b".into(), "a".into()]), "tags", Rule::UniqueItems,
        "items 0 and 2 are equal");
    assert_error(|r| r.tags = Some(vec!["a".into(), "abcdef".into()]), "tags[1]", Rule::MaxLength(5), "length 6");
    // any item type, by equality: models holding f64
    assert_error(|r| r.points = Some(vec![models::Point::new(1.0), models::Point::new(1.0)]), "points",
        Rule::UniqueItems, "items 0 and 1 are equal");
    assert_error(|r| r.colors = Some(vec![]), "colors", Rule::MinItems(1), "0 items");
    assert_error(|r| r.stops = Some(vec![vec!["a".into()], vec![]]), "stops[1]", Rule::MinItems(1), "0 items");
    assert_error(|r| r.stops = Some(vec![vec!["a".into(), String::new()]]), "stops[0][1]", Rule::MinLength(1),
        "length 0");
}

#[test]
fn map_sizes_and_values() {
    assert_error(|r| r.metadata = Some(Default::default()), "metadata", Rule::MinProperties(1), "0 properties");
    assert_error(
        |r| r.metadata = Some([("a", "1"), ("b", "2"), ("c", "3")].map(|(k, v)| (k.into(), v.into())).into()),
        "metadata", Rule::MaxProperties(2), "3 properties");
    assert_error(|r| r.metadata = Some([("k".to_string(), "abcd".to_string())].into()), "metadata[\"k\"]",
        Rule::MaxLength(3), "length 4");
    // A map of an inline enum: its size is checked, its values are enum values with nothing to check.
    assert_error(|r| r.ratings = Some(Default::default()), "ratings", Rule::MinProperties(1), "0 properties");
    assert_error(
        |r| r.ratings = Some(serde_json::from_value(json!({"a": "low", "b": "high", "c": "low"})).unwrap()),
        "ratings", Rule::MaxProperties(2), "3 properties");
    // A map of lists of an inline enum: each list's size.
    assert_error(
        |r| r.rating_lists = Some(serde_json::from_value(json!({"a": ["low", "high"]})).unwrap()),
        "rating_lists[\"a\"]", Rule::MaxItems(1), "2 items");
}

#[test]
fn unions_check_their_active_variant() {
    assert_error(|r| r.content = Some(Content::Text("abcde".into())), "content", Rule::MaxLength(4), "length 5");
    assert_error(|r| r.content = Some(Content::ArrayOfMessages(vec![message("a"), message("")])),
        "content[1].content", Rule::MinLength(1), "length 0");
    let mut ok = valid();
    ok.content = Some(Content::Text("abcd".into()));
    ok.shape = Some(serde_json::from_value(json!({"kind": "Hexagon"})).unwrap());
    assert_eq!(ok.validate(), Ok(()));
}

#[test]
fn nested_and_recursive_models() {
    let e = error(|r| {
        let node = r.node.as_mut().unwrap();
        node.children.as_mut().unwrap()[0].next.as_mut().unwrap().value = Some(-1);
    });
    assert_eq!((e.path.as_str(), e.rule), ("node.children[0].next.value", Rule::Minimum(0.0)));
    assert_error(|r| r.holder.as_mut().unwrap().inner.as_mut().unwrap().content = String::new(),
        "holder.inner.content", Rule::MinLength(1), "length 0");
}

#[test]
fn names_that_could_collide() {
    assert_error(|r| r.validate = Some("a".into()), "validate", Rule::MinLength(2), "length 1");
    assert_error(|r| r.clash = Some(models::Validate { name: Some(String::new()) }), "clash.name", Rule::MinLength(1),
        "length 0");
}

#[test]
fn the_first_error_is_reported() {
    let e = error(|r| {
        r.messages.clear();
        r.count = 0;
    });
    assert_eq!(e.path, "messages");
}

#[test]
fn validation_error_is_an_error() {
    let e = error(|r| r.count = 0);
    let boxed: Box<dyn std::error::Error> = Box::new(e);
    assert_eq!(boxed.to_string(), "count: 0 breaks minimum 1");
}

fn patterns(json: serde_json::Value) -> Result<(), ValidationError> {
    serde_json::from_value::<models::Patterns>(json).unwrap().validate()
}

#[test]
fn patterns_the_generator_accepts_are_checked() {
    assert_eq!(patterns(json!({
        "ok_phone": "+3212345678", "ok_slug": "a.b-c", "ok_letters": "héllo", "ok_path": "/a/bc",
        "ok_named": "word", "ok_lazy": "aabb"
    })), Ok(()));
    for (field, bad) in [("ok_phone", "3212"), ("ok_slug", "a"), ("ok_letters", "a1"), ("ok_path", "a/b"),
        ("ok_named", "Word"), ("ok_lazy", "ab")] {
        let e = patterns(json!({ field: bad })).expect_err(field);
        assert_eq!(e.path, field);
        assert!(matches!(e.rule, Rule::Pattern(_)), "{e}");
    }
}

#[test]
fn patterns_the_generator_skips_are_not_checked() {
    let fields = ["skip_empty_negated", "skip_bracket_first", "skip_open_count", "skip_big_z", "skip_escape_e",
        "skip_backreference", "skip_nested_class", "skip_double_star", "skip_word_start", "skip_dash_after_class",
        "skip_inline_flag", "skip_control", "skip_lookbehind", "skip_set_operation"];
    let all: serde_json::Map<String, serde_json::Value> =
        fields.iter().map(|f| (f.to_string(), json!("anything at all"))).collect();
    assert_eq!(patterns(serde_json::Value::Object(all)), Ok(()));
}

/// Checking these would not compile (clippy's invalid_regex denies them), which is why the generator skips them.
#[test]
fn the_skipped_patterns_include_ones_regex_rejects() {
    for pattern in [r"[^]", r"^a{,3}$", r"^a\Z", r"^\e$", r"(a)\1", r"[\w-.]", r"\cJ", r"(?<=a)b"] {
        assert!(regex::Regex::new(pattern).is_err(), "{pattern}");
    }
}

#[test]
fn an_f32_bound_beyond_f32_is_compared_as_f64() {
    let mut ok = valid();
    ok.huge = Some(f32::MAX);
    assert_eq!(ok.validate(), Ok(()));
}

#[test]
fn plain_enums_implement_the_trait() {
    assert_eq!(models::Status::Draft.validate(), Ok(()));
    assert_eq!(models::Level::Variant1.validate(), Ok(()));
}
