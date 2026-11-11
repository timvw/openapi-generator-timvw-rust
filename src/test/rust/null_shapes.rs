//! Schemas of only null, and of any value or null, decode and encode `null` and a value, on the crate generated
//! from src/test/resources/null_shapes.yaml. `NullShapesTest` copies this file into that crate's tests/ and runs
//! cargo.

use null_shapes_fixture::apis::configuration::Configuration;
use null_shapes_fixture::apis::default_api;
use null_shapes_fixture::models::{Holder, Refs, TextOrNullRef, Uni, Wrapped};
use serde_json::{json, Value};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

#[test]
fn null_and_values_round_trip_in_every_position() {
    let json = json!({
        "any_opt": null,
        "any_req": {"a": 1},
        "any_items": [null, 1, "x"],
        "any_values": {"a": null, "b": [true]},
        "nulls": [null, null],
        "nulls_req": [null],
        "null_values": {"a": null},
        "null_opt": null,
        "null_req": null
    });
    let holder: Holder = serde_json::from_value(json.clone()).expect("decodes");
    // an optional field that is present and null is Some(None), as any nullable field
    assert_eq!(holder.any_opt, Some(None));
    assert_eq!(holder.any_req, Some(json!({"a": 1})));
    assert_eq!(holder.any_items, Some(vec![Value::Null, json!(1), json!("x")]));
    assert_eq!(holder.any_values.as_ref().unwrap()["a"], Value::Null);
    assert_eq!(holder.nulls, Some(vec![Value::Null, Value::Null]));
    assert_eq!(holder.nulls_req, vec![Value::Null]);
    assert_eq!(holder.null_values.as_ref().unwrap()["a"], Value::Null);
    assert_eq!(holder.null_opt, Some(None));
    assert_eq!(holder.null_req, None);
    assert_eq!(serde_json::to_value(&holder).unwrap(), json);

    // a value where null is allowed
    let json = json!({
        "any_opt": "v",
        "any_req": null,
        "nulls_req": [],
        "null_req": null
    });
    let holder: Holder = serde_json::from_value(json.clone()).expect("decodes");
    assert_eq!(holder.any_opt, Some(Some(json!("v"))));
    assert_eq!(holder.any_req, None);
    // absent optional fields stay absent
    assert_eq!(holder.null_opt, None);
    assert_eq!(serde_json::to_value(&holder).unwrap(), json);
}

#[test]
fn references_to_any_or_null_and_null_components() {
    let json = json!({
        "any": null,
        "null_only": null,
        "nullable_any": {"k": "v"},
        "list_of_any_refs": [null, 2]
    });
    let refs: Refs = serde_json::from_value(json.clone()).expect("decodes");
    assert_eq!(refs.any, Some(None));
    assert_eq!(refs.null_only, Some(None));
    assert_eq!(refs.nullable_any, Some(Some(json!({"k": "v"}))));
    assert_eq!(refs.list_of_any_refs, Some(vec![Value::Null, json!(2)]));
    assert_eq!(serde_json::to_value(&refs).unwrap(), json);
}

#[test]
fn union_variants_of_null_items() {
    // the array branches of null items and of any-or-null items are one variant, of any values
    let uni: Uni = serde_json::from_value(json!([null])).expect("decodes");
    assert_eq!(uni, Uni::ArrayOfItems(vec![Value::Null]));
    assert_eq!(serde_json::to_value(&uni).unwrap(), json!([null]));
    let uni: Uni = serde_json::from_value(json!("s")).expect("decodes");
    assert_eq!(uni, Uni::Text("s".to_string()));
}

/// Serves one request with `status` and `body`; returns the request.
async fn serve(response: &'static str) -> (Configuration, tokio::task::JoinHandle<String>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let handle = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.unwrap();
        let mut request = Vec::new();
        let mut buf = [0u8; 4096];
        loop {
            let n = socket.read(&mut buf).await.unwrap();
            request.extend_from_slice(&buf[..n]);
            let text = String::from_utf8_lossy(&request).to_string();
            if let Some(end) = text.find("\r\n\r\n") {
                let length = text[..end]
                    .lines()
                    .find_map(|l| {
                        l.to_ascii_lowercase()
                            .strip_prefix("content-length:")
                            .map(|v| v.trim().parse::<usize>().unwrap())
                    })
                    .unwrap_or(0);
                if request.len() >= end + 4 + length {
                    break;
                }
            }
            if n == 0 {
                break;
            }
        }
        socket.write_all(response.as_bytes()).await.unwrap();
        socket.shutdown().await.unwrap();
        String::from_utf8_lossy(&request).to_string()
    });
    let mut configuration = Configuration::new();
    configuration.base_path = format!("http://127.0.0.1:{port}");
    (configuration, handle)
}

/// A request body of any value or null sends `null`; a response of only null decodes it.
#[tokio::test]
async fn a_null_body_is_sent_and_a_null_response_decoded() {
    let (configuration, server) = serve(
        "HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: 4\r\nconnection: close\r\n\r\nnull",
    )
    .await;
    let result = default_api::post_things()
        .configuration(&configuration)
        .body(&Value::Null)
        .call()
        .await
        .unwrap();
    assert_eq!(result, Value::Null);
    assert!(server.await.unwrap().ends_with("\r\n\r\nnull"));
}

/// A urlencoded field of any value or null is sent as its JSON text.
#[tokio::test]
async fn an_any_form_field_is_sent_as_its_json() {
    let (configuration, server) =
        serve("HTTP/1.1 204 No Content\r\nconnection: close\r\n\r\n").await;
    default_api::post_form()
        .configuration(&configuration)
        .anything(json!({"a": 1}))
        .count(2)
        .call()
        .await
        .unwrap();
    assert!(server.await.unwrap().ends_with("anything=%7B%22a%22%3A1%7D&count=2"));
}

/// A union of an object and a branch of any value or null holds any value: an object, a number, a string, null.
#[test]
fn a_union_holding_any_or_null_holds_any_value() {
    // inline, with {$ref: Obj}, and through the component NestedAny ({$ref: Obj} or any value or null)
    for json in [json!({"a": "x"}), json!(5), json!("x"), Value::Null] {
        let wrapped: Wrapped = serde_json::from_value(json!({
            "all_null": null,
            "nested_any": json.clone(),
            "nested_ref": json.clone(),
            "nested_component": json.clone()
        }))
        .expect("decodes");
        // null is the property's Some(None), as for any nullable property
        let expected = if json.is_null() { Some(None) } else { Some(Some(json.clone())) };
        assert_eq!(wrapped.nested_any, expected);
        assert_eq!(wrapped.nested_ref, expected);
        assert_eq!(wrapped.nested_component, expected);
        let encoded = serde_json::to_value(&wrapped).unwrap();
        for field in ["nested_any", "nested_ref", "nested_component"] {
            assert_eq!(encoded[field], json);
        }
    }
}

/// A reference to a component of only null makes the union nullable; enum [null] and allOf [null] hold null.
#[test]
fn null_references_enums_and_all_ofs() {
    let t: TextOrNullRef = serde_json::from_value(json!({"value": null})).expect("decodes");
    assert_eq!(t.value, Some(None));
    let t: TextOrNullRef = serde_json::from_value(json!({"value": "s"})).expect("decodes");
    assert_eq!(t.value, Some(Some("s".to_string())));
    let json = json!({"all_null": null, "enum_null": null, "enum_ref": null});
    let wrapped: Wrapped = serde_json::from_value(json.clone()).expect("decodes");
    assert_eq!(wrapped.all_null, None);
    assert_eq!(wrapped.enum_null, Some(None));
    assert_eq!(serde_json::to_value(&wrapped).unwrap(), json);
}
