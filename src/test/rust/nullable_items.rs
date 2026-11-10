//! Nullable list items and map values decode, serialize and validate, on the crate generated from
//! src/test/resources/nullable_items.yaml. `NullableItemsTest` copies this file into that crate's tests/ and runs
//! cargo.

use nullable_items_fixture::apis::configuration::Configuration;
use nullable_items_fixture::apis::default_api;
use nullable_items_fixture::models::holder::{ModeGrid, Modes};
use nullable_items_fixture::models::validation::Validate;
use nullable_items_fixture::models::{Holder, Lone, Thing, Uni};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

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

#[test]
fn a_wrapped_reference_and_a_union_variant_take_null_items() {
    let holder: Holder = serde_json::from_value(serde_json::json!({
        "wrapped": [null, {"name": "ok"}],
        "uni": ["a", null]
    }))
    .expect("decodes");
    assert!(holder.wrapped.as_ref().unwrap()[0].is_none());
    assert_eq!(
        holder.uni,
        Some(Uni::ArrayOfNullableStrings(vec![
            Some("a".to_string()),
            None
        ]))
    );
    let lone: Lone = serde_json::from_value(serde_json::json!([null, "b"])).expect("decodes");
    assert_eq!(
        lone,
        Lone::ArrayOfNullableStrings(vec![None, Some("b".to_string())])
    );
    let plain: Uni = serde_json::from_value(serde_json::json!(["a"])).expect("decodes");
    assert_eq!(plain, Uni::ArrayOfStrings(vec!["a".to_string()]));
}

/// Serves one request with 204; returns the request's headers and body.
async fn serve() -> (Configuration, tokio::task::JoinHandle<String>) {
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
                let chunked = text[..end]
                    .to_ascii_lowercase()
                    .contains("transfer-encoding: chunked");
                if chunked && text.ends_with("0\r\n\r\n")
                    || !chunked && request.len() >= end + 4 + length
                {
                    break;
                }
            }
            if n == 0 {
                break;
            }
        }
        socket
            .write_all(b"HTTP/1.1 204 No Content\r\nconnection: close\r\n\r\n")
            .await
            .unwrap();
        socket.shutdown().await.unwrap();
        String::from_utf8_lossy(&request).to_string()
    });
    let mut configuration = Configuration::new();
    configuration.base_path = format!("http://127.0.0.1:{port}");
    (configuration, handle)
}

/// Form fields keep plain items: a urlencoded or multipart field has no null to send.
#[tokio::test]
async fn form_fields_send_their_items() {
    let (configuration, server) = serve().await;
    default_api::put_form()
        .configuration(&configuration)
        .tags(vec!["a".to_string(), "b".to_string()])
        .call()
        .await
        .unwrap();
    assert!(server.await.unwrap().ends_with("tags=a&tags=b"));

    let file = std::env::temp_dir().join("nullable_items_form.txt");
    std::fs::write(&file, "content").unwrap();
    let (configuration, server) = serve().await;
    default_api::post_form()
        .configuration(&configuration)
        .tags(vec!["a".to_string()])
        .files(vec![file.clone()])
        .call()
        .await
        .unwrap();
    let request = server.await.unwrap();
    // multipart/form-data, sent chunked: the parts are in the body, between the chunk sizes
    for part in [
        "name=\"tags\"",
        "[\"a\"]",
        "filename=\"nullable_items_form.txt\"",
        "content",
    ] {
        assert!(request.contains(part), "{part} in {request}");
    }
}
