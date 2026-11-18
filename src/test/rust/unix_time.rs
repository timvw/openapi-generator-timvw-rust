//! Unix timestamps as `jiff::Timestamp`s, on the crate generated from src/test/resources/unix_time.yaml with
//! `dateLibrary=jiff` and `targets: [client, server]`. `UnixTimeTest` copies this file into that crate's tests/ and
//! runs cargo. The generated router serves an implementation on a local port; the generated client calls it.

use jiff::Timestamp;
use serde_json::json;
use unix_time::apis::configuration::Configuration;
use unix_time::apis::events_api;
use unix_time::models::{Event, EventWindow};
use unix_time::server::events_api::{StoreEventRequest, StoreEventResponse};
use unix_time::server::{self, ErrorHandler, EventsApi, ServerError};

/// Answers with the event it read; `sequence` is the `since` parameter.
struct Echo;

impl ErrorHandler for Echo {}

impl EventsApi for Echo {
    async fn store_event(&self, request: StoreEventRequest) -> Result<StoreEventResponse, ServerError> {
        Ok(StoreEventResponse::Status200(Event { sequence: request.since.unwrap_or(-1), ..request.body }))
    }
}

async fn serve() -> Configuration {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move { axum::serve(listener, server::router(Echo)).await.unwrap() });
    let mut configuration = Configuration::new();
    configuration.base_path = format!("http://127.0.0.1:{port}");
    configuration.client = timvw_rust_runtime::Client::from_service(timvw_rust_runtime::ReqwestService::default());
    configuration
}

fn at(second: i64) -> Timestamp {
    Timestamp::from_second(second).unwrap()
}

fn event() -> Event {
    Event::builder()
        .created_at(at(1_700_000_000))
        .completed_at(Timestamp::new(1_700_000_060, 500_000_000).unwrap())
        .sequence(7)
        .build()
}

/// Every shape, through the client's encoder, the server's decoder and back.
#[tokio::test]
async fn the_server_reads_what_the_client_writes() {
    let configuration = serve().await;
    let sent = Event {
        expires_at: Some(at(1_700_003_600)),
        updated_at: Some(at(1_700_000_030)),
        cancelled_at: Some(None),
        started_at: Some(Some(Timestamp::new(1_700_000_001, 250_000_000).unwrap())),
        history: Some(vec![1_600_000_000]),
        window: Some(EventWindow { opens_at: at(0), closes_at: Some(at(-86_400)) }),
        ..event()
    };
    let read = events_api::store_event()
        .configuration(&configuration)
        .event(&sent)
        .since(1_650_000_000)
        .call()
        .await
        .unwrap();
    assert_eq!(read, Event { sequence: 1_650_000_000, ..sent });
}

/// An integer is whole seconds, a number keeps its fraction, and an absent field, `null` and a value differ.
#[test]
fn what_is_written() {
    assert_eq!(
        serde_json::to_value(event()).unwrap(),
        json!({"created_at": 1_700_000_000, "expires_at": null, "completed_at": 1_700_000_060.5, "sequence": 7})
    );
    let full = Event {
        // an integer drops the fraction of a second; a number without one is written as an integer
        created_at: Timestamp::new(1_700_000_000, 900_000_000).unwrap(),
        expires_at: Some(at(1_700_003_600)),
        updated_at: Some(at(1)),
        cancelled_at: Some(None),
        completed_at: at(1_700_000_060),
        started_at: Some(Some(at(2))),
        ..event()
    };
    let text = serde_json::to_string(&full).unwrap();
    assert_eq!(
        text,
        r#"{"created_at":1700000000,"expires_at":1700003600,"updated_at":1,"cancelled_at":null,"completed_at":1700000060,"started_at":2,"sequence":7}"#
    );
}

#[test]
fn what_is_read() {
    let read: Event = serde_json::from_value(json!({
        "created_at": 1_700_000_000, "expires_at": null, "cancelled_at": null, "completed_at": 1_700_000_060,
        "started_at": 1.25, "sequence": 7, "window": {"opens_at": -1}
    }))
    .unwrap();
    assert_eq!(read.created_at, at(1_700_000_000));
    assert_eq!((read.expires_at, read.updated_at, read.cancelled_at), (None, None, Some(None)));
    assert_eq!(read.completed_at, at(1_700_000_060));
    assert_eq!(read.started_at, Some(Some(Timestamp::new(1, 250_000_000).unwrap())));
    assert_eq!(read.window.unwrap().opens_at, at(-1));

    // an integer field reads a number with a fraction too: a server that writes 1700000000.0 is understood
    let read: Event = serde_json::from_str(
        r#"{"created_at": 1700000000.0, "expires_at": 1700003600.5, "completed_at": 0, "sequence": 7}"#,
    )
    .unwrap();
    assert_eq!(read.created_at, at(1_700_000_000));
    assert_eq!(read.expires_at, Some(Timestamp::new(1_700_003_600, 500_000_000).unwrap()));
}

#[test]
fn what_is_refused() {
    let read = |created_at: serde_json::Value| {
        serde_json::from_value::<Event>(json!({
            "created_at": created_at, "expires_at": null, "completed_at": 0, "sequence": 7
        }))
        .map_err(|error| error.to_string())
    };
    assert!(read(json!(1_700_000_000)).is_ok());
    // milliseconds are past the year 9999
    let error = read(json!(1_700_000_000_000_i64)).unwrap_err();
    assert!(error.contains("1700000000000 is not a Unix timestamp in seconds"), "{error}");
    assert!(read(json!("2023-11-14T22:13:20Z")).unwrap_err().contains("a Unix timestamp, in seconds"));
    assert!(read(json!(null)).is_err());
    assert!(read(json!(u64::MAX)).is_err());
    // a required field that can be null must still be there
    let missing = serde_json::from_value::<Event>(json!({"created_at": 0, "completed_at": 0, "sequence": 7}));
    assert!(missing.unwrap_err().to_string().contains("expires_at"));
}

/// The serde modules are public: a type of your own can name them.
#[test]
fn the_modules_serve_other_types() {
    #[derive(serde::Serialize, serde::Deserialize, PartialEq, Debug)]
    struct Mine {
        #[serde(with = "unix_time::models::unix_time")]
        at: Timestamp,
        #[serde(default, with = "unix_time::models::unix_time::fractional::option")]
        maybe: Option<Timestamp>,
    }
    let mine: Mine = serde_json::from_str(r#"{"at": 5}"#).unwrap();
    assert_eq!(mine, Mine { at: at(5), maybe: None });
    assert_eq!(serde_json::to_string(&mine).unwrap(), r#"{"at":5,"maybe":null}"#);
}
