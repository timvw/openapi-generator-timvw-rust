//! WebAssembly: services that are not `Send`, retries and request timeouts that wait with the JavaScript host's timers,
//! the SSE idle timeout and uploads, with no file system.
//!
//! `cargo test --target wasm32-unknown-unknown --no-default-features --features reqwest,retry --test wasm`, with
//! `wasm-bindgen-test-runner` as the target's runner (it runs the tests in Node.js).
#![cfg(all(target_family = "wasm", feature = "retry"))]

use std::cell::Cell;
use std::rc::Rc;
use std::time::Duration;

use futures_util::StreamExt;
use timvw_rust_runtime::multipart::{Form, Part};
use timvw_rust_runtime::retry::{RetryLayer, RetryPolicy};
use timvw_rust_runtime::timeout::TimeoutLayer;
use timvw_rust_runtime::{Body, BoxError, Client, Method, StatusCode, Upload};
use wasm_bindgen_test::wasm_bindgen_test;
use web_time::Instant;

/// A client whose service answers with `script`, one status per call (then `200`), counting calls in an `Rc`: a
/// service that is not `Send`. Each answer carries `retry-after-ms` when given.
fn scripted(script: Vec<(u16, Option<&'static str>)>) -> (Client, Rc<Cell<usize>>) {
    let calls = Rc::new(Cell::new(0));
    let counter = calls.clone();
    let service = tower::service_fn(move |_request: http::Request<Body>| {
        let n = counter.get();
        counter.set(n + 1);
        let (status, retry_after_ms) = script.get(n).copied().unwrap_or((200, None));
        async move {
            let mut response = http::Response::builder().status(status);
            if let Some(ms) = retry_after_ms {
                response = response.header("retry-after-ms", ms);
            }
            Ok::<_, BoxError>(response.body(Body::from("body"))?)
        }
    });
    (Client::from_service(service), calls)
}

#[wasm_bindgen_test]
async fn retries_wait_as_long_as_the_server_asks() {
    let (client, calls) = scripted(vec![(503, Some("300")), (429, Some("100"))]);
    let client = client.layer(RetryLayer::new(RetryPolicy::default()));
    let started = Instant::now();
    let response = client
        .request(Method::GET, "https://example.invalid/")
        .send()
        .await
        .unwrap();
    let waited = started.elapsed();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(calls.get(), 3);
    assert!(
        (Duration::from_millis(400)..Duration::from_secs(2)).contains(&waited),
        "{waited:?}"
    );
}

/// `TimeoutLayer` waits with `setTimeout`: a service that never answers times out, inside the retries for each attempt
/// (retried with `retry_timeouts`), outside them for the whole call.
#[wasm_bindgen_test]
async fn a_request_times_out_with_the_hosts_timer() {
    let calls = Rc::new(Cell::new(0));
    let counter = calls.clone();
    let hangs_once = tower::service_fn(move |_request: http::Request<Body>| {
        let n = counter.get();
        counter.set(n + 1);
        async move {
            if n == 0 {
                futures_util::future::pending::<()>().await;
            }
            Ok::<_, BoxError>(http::Response::new(Body::from("late")))
        }
    });
    let client = Client::from_service(hangs_once)
        .layer(TimeoutLayer::new(Duration::from_millis(200)))
        .layer(RetryLayer::new(
            RetryPolicy::new(1)
                .base_delay(Duration::from_millis(10))
                .retry_timeouts(true),
        ));
    let started = Instant::now();
    let response = client
        .request(Method::GET, "https://example.invalid/")
        .send()
        .await
        .unwrap();
    assert_eq!(response.text().await.unwrap(), "late");
    assert_eq!(calls.get(), 2);
    assert!(started.elapsed() >= Duration::from_millis(200));

    let never = tower::service_fn(|_request: http::Request<Body>| async {
        futures_util::future::pending::<()>().await;
        Ok::<_, BoxError>(http::Response::new(Body::empty()))
    });
    let client = Client::from_service(never).layer(TimeoutLayer::new(Duration::from_millis(150)));
    let started = Instant::now();
    let error = client
        .request(Method::GET, "https://example.invalid/")
        .send()
        .await
        .unwrap_err();
    let waited = started.elapsed();
    assert!(error.is_timeout(), "{error:?}");
    assert!(
        (Duration::from_millis(150)..Duration::from_secs(2)).contains(&waited),
        "{waited:?}"
    );
}

#[wasm_bindgen_test]
async fn retries_back_off_without_advice() {
    let (client, calls) = scripted(vec![(503, None), (503, None)]);
    let policy = RetryPolicy::new(3)
        .base_delay(Duration::from_millis(100))
        .jitter(false);
    let client = client.layer(RetryLayer::new(policy));
    let started = Instant::now();
    let response = client
        .request(Method::GET, "https://example.invalid/")
        .send()
        .await
        .unwrap();
    let waited = started.elapsed();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(calls.get(), 3);
    // 100 ms, then 200 ms.
    assert!(
        (Duration::from_millis(300)..Duration::from_secs(2)).contains(&waited),
        "{waited:?}"
    );
}

#[wasm_bindgen_test]
async fn an_idle_event_stream_times_out() {
    let (tx, rx) =
        futures_channel::mpsc::unbounded::<Result<&'static [u8], timvw_rust_runtime::Error>>();
    tx.unbounded_send(Ok(b"data: 1\n\n")).unwrap();
    let mut events =
        timvw_rust_runtime::sse::Events::from_bytes(rx).idle_timeout(Duration::from_millis(150));
    assert_eq!(events.next().await.unwrap().unwrap().data, "1");
    let started = Instant::now();
    let error = events.next().await.unwrap().unwrap_err();
    assert!(error.is_timeout(), "{error}");
    assert!(started.elapsed() >= Duration::from_millis(150));
    assert!(events.next().await.is_none());
    drop(tx);
}

#[wasm_bindgen_test]
async fn uploads_of_bytes_and_streams_encode_without_a_file_system() {
    let counter = Rc::new(Cell::new(0));
    let made = counter.clone();
    let replayed = Upload::from_fn(move || {
        made.set(made.get() + 1);
        futures_util::stream::iter([Ok::<_, BoxError>(bytes::Bytes::from_static(b"abc"))])
    })
    .len(3)
    .file_name("a.txt");
    let form = Form::new()
        .text("purpose", "test")
        .part("file", Part::upload(replayed))
        .part(
            "raw",
            Part::upload(Upload::bytes(&b"xyz"[..]).file_name("b.bin")),
        );
    let body = form.into_body();
    let copy = body.try_clone().expect("every part can be sent again");
    let first = body.collect_bytes().await.unwrap();
    let second = copy.collect_bytes().await.unwrap();
    assert_eq!(first, second);
    assert_eq!(counter.get(), 2);
    let text = String::from_utf8(first.to_vec()).unwrap();
    assert!(text.contains("filename=\"a.txt\"\r\n"), "{text}");
    assert!(text.contains("\r\n\r\nabc\r\n"), "{text}");
    assert!(text.contains("\r\n\r\nxyz\r\n"), "{text}");
}

/// reqwest's `fetch` backend end to end, against the server of `TIMVW_WASM_TEST_SERVER` (read when the test is built):
/// `/flaky?id=..` answers `503` with `retry-after-ms` the first time, `/echo` echoes the body, `/sse` sends two events,
/// `/slow?ms=..` answers after a delay.
/// Skipped without it.
#[cfg(feature = "reqwest")]
#[wasm_bindgen_test]
async fn reqwest_sends_with_fetch() {
    let Some(server) = option_env!("TIMVW_WASM_TEST_SERVER") else {
        return;
    };
    let client = Client::new();
    let started = Instant::now();
    let response = client
        .request(
            Method::GET,
            &format!(
                "{server}/flaky?id={}",
                web_time::SystemTime::now()
                    .duration_since(web_time::UNIX_EPOCH)
                    .unwrap()
                    .as_nanos()
            ),
        )
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["x-test"], "yes");
    assert_eq!(response.text().await.unwrap(), "ok");
    assert!(started.elapsed() >= Duration::from_millis(250));

    // A streamed upload is read into memory for `fetch`.
    let upload = Upload::stream(futures_util::stream::iter([
        Ok::<_, BoxError>(bytes::Bytes::from_static(b"strea")),
        Ok(bytes::Bytes::from_static(b"med")),
    ]))
    .file_name("s.txt");
    let form = Form::new().part("file", Part::upload(upload));
    let response = client
        .request(Method::POST, &format!("{server}/echo"))
        .multipart(form)
        .send()
        .await
        .unwrap();
    assert!(response.headers()["x-content-type"]
        .to_str()
        .unwrap()
        .starts_with("multipart/form-data; boundary="));
    let echoed = response.text().await.unwrap();
    assert!(echoed.contains("\r\n\r\nstreamed\r\n"), "{echoed}");

    let response = client
        .request(Method::GET, &format!("{server}/sse"))
        .send()
        .await
        .unwrap();
    let numbers: Vec<serde_json::Value> = response
        .events()
        .json::<serde_json::Value>()
        .map(|event| event.unwrap())
        .collect()
        .await;
    assert_eq!(
        numbers,
        [serde_json::json!({"n": 1}), serde_json::json!({"n": 2})]
    );

    // `fetch` has no timeout of its own: `TimeoutLayer` gives up on a slow response (and reqwest aborts the fetch).
    let client = Client::new().layer(TimeoutLayer::new(Duration::from_millis(300)));
    let started = Instant::now();
    let error = client
        .request(Method::GET, &format!("{server}/slow?ms=3000"))
        .send()
        .await
        .unwrap_err();
    assert!(error.is_timeout(), "{error:?}");
    assert!(started.elapsed() < Duration::from_secs(2));
    let response = client
        .request(Method::GET, &format!("{server}/slow?ms=10"))
        .send()
        .await
        .unwrap();
    assert_eq!(response.text().await.unwrap(), "slow");
}

/// With `fs` on WebAssembly a path still makes an upload (generated models with a file variant hold one), which fails
/// when resolved or sent: there is no file system.
#[cfg(feature = "fs")]
#[wasm_bindgen_test]
async fn a_path_upload_fails_without_a_file_system() {
    let upload = Upload::from(std::path::PathBuf::from("photo.png"));
    let error = upload.clone().resolve().await.unwrap_err();
    assert!(error.to_string().contains("no file system"), "{error}");
    let error = Body::from(upload).collect_bytes().await.unwrap_err();
    assert!(error.to_string().contains("no file system"), "{error}");
}
