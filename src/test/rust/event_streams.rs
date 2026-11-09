//! The methods of typedEventStreams and contentTypeMethods at run time, on the crate generated from
//! src/test/resources/event_streams.yaml. `EventStreamsTest` copies this file into that crate's tests/ and runs cargo.
//! A one-shot HTTP server on a local port answers each call and hands back the request it read.

use std::collections::VecDeque;

use event_streams_fixture::apis::configuration::Configuration;
use event_streams_fixture::apis::sse::{Decoder, Event, EventStream, Events};
use event_streams_fixture::apis::{default_api, Error};
use event_streams_fixture::models::*;
use futures_util::StreamExt;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

/// Serves one request with `status`, `content_type` and `body`, written in the given chunks; returns the request.
async fn serve(
    status: &str,
    content_type: &str,
    chunks: Vec<&'static str>,
) -> (Configuration, tokio::task::JoinHandle<String>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let head =
        format!("HTTP/1.1 {status}\r\ncontent-type: {content_type}\r\nconnection: close\r\n\r\n");
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
        socket.write_all(head.as_bytes()).await.unwrap();
        for chunk in chunks {
            socket.write_all(chunk.as_bytes()).await.unwrap();
            socket.flush().await.unwrap();
            tokio::time::sleep(std::time::Duration::from_millis(5)).await;
        }
        socket.shutdown().await.unwrap();
        String::from_utf8_lossy(&request).to_string()
    });
    let mut configuration = Configuration::new();
    configuration.base_path = format!("http://127.0.0.1:{port}");
    (configuration, handle)
}

fn request() -> CreateResponse {
    CreateResponse::builder()
        .model("m".to_owned())
        .stream(true)
        .build()
}

fn decode_all(chunks: &[&[u8]]) -> Vec<Event> {
    let mut decoder = Decoder::new();
    let mut events = VecDeque::new();
    for chunk in chunks {
        decoder.feed(chunk, &mut events);
    }
    decoder.finish();
    events.into_iter().collect()
}

/// The parser follows the standard: any line ending, comments, multi-line data, the last id, no event without data,
/// a byte order mark, and an unterminated event at the end of the body is dropped.
#[test]
fn the_decoder_reads_server_sent_events() {
    let body: &[u8] =
        b"\xEF\xBB\xBF: a comment\r\nevent: first\r\nid: 1\r\ndata: {\"a\":1}\r\n\r\n\
        event: no data\n\n\
        data: line one\ndata:line two\n\n\
        data: [DONE]\r\r\
        data: cut off";
    let expected = vec![
        Event {
            event: Some("first".into()),
            data: "{\"a\":1}".into(),
            id: Some("1".into()),
        },
        Event {
            event: None,
            data: "line one\nline two".into(),
            id: Some("1".into()),
        },
        Event {
            event: None,
            data: "[DONE]".into(),
            id: Some("1".into()),
        },
    ];
    assert_eq!(decode_all(&[body]), expected);
    // The same body cut at every byte gives the same events.
    let bytes: Vec<&[u8]> = body.chunks(1).collect();
    assert_eq!(decode_all(&bytes), expected);
    for cut in 0..body.len() {
        assert_eq!(
            decode_all(&[&body[..cut], &body[cut..]]),
            expected,
            "cut at {cut}"
        );
    }
}

/// Each `data:` payload decodes by its `type`, whatever the `event:` name; a new type is kept as `Unknown`, a broken
/// payload is an error the stream goes on after, and `[DONE]` ends the stream.
#[tokio::test]
async fn a_stream_decodes_each_payload_by_its_type() {
    let body: Vec<Result<&'static [u8], reqwest::Error>> = vec![
        Ok(b"event: response.created\ndata: {\"type\":\"response.created\",\"sequence_number\":0,\"response\":{\"id\":\"r\"}}\n\n"),
        Ok(b"event: whatever\ndata: {\"type\":\"response.output_text.delta\",\"sequence_"),
        Ok(b"number\":1,\"delta\":\"Hi\"}\n\n"),
        Ok(b"data: {\"type\":\"response.new_kind\",\"x\":1}\n\n"),
        Ok(b"data: {\"type\":\"response.output_text.delta\"}\n\n"),
        Ok(b": keep-alive\n\ndata: {\"type\":\"response.completed\",\"sequence_number\":2,\"response\":{\"id\":\"r\",\"status\":\"completed\"}}\n\n"),
        Ok(b"data: [DONE]\n\ndata: {\"type\":\"response.created\"}\n\n"),
    ];
    let stream: EventStream<ResponseStreamEvent, default_api::CreateResponseError> =
        EventStream::from_bytes(futures_util::stream::iter(body));
    let items: Vec<_> = stream.collect().await;
    assert_eq!(items.len(), 5, "{items:?}");
    assert!(
        matches!(&items[0], Ok(ResponseStreamEvent::ResponseCreatedEvent(e)) if e.response.id == "r")
    );
    assert!(
        matches!(&items[1], Ok(ResponseStreamEvent::ResponseTextDeltaEvent(e)) if e.delta == "Hi" && e.sequence_number == 1)
    );
    match &items[2] {
        Ok(ResponseStreamEvent::Unknown(value)) => assert_eq!(value["type"], "response.new_kind"),
        other => panic!("{other:?}"),
    }
    assert!(matches!(&items[3], Err(Error::Serde(_))), "{:?}", items[3]);
    assert!(
        matches!(&items[4], Ok(ResponseStreamEvent::ResponseCompletedEvent(e)) if e.response.status.as_deref() == Some("completed"))
    );
}

/// The events undecoded: names, ids and raw data, up to `[DONE]`; a typed stream hands them over unread.
#[tokio::test]
async fn the_events_can_be_read_undecoded() {
    let body = || -> Vec<Result<&'static [u8], reqwest::Error>> {
        vec![
        Ok(b"event: one\nid: 7\ndata: {\"type\":\"response.output_text.delta\",\"sequence_number\":1,\"delta\":\"a\"}\n\n"),
        Ok(b"event: two\ndata: not json\n\ndata: [DONE]\n\ndata: after\n\n"),
    ]
    };
    let events: Vec<Event> = Events::from_bytes(futures_util::stream::iter(body()))
        .map(Result::unwrap)
        .collect()
        .await;
    assert_eq!(events.len(), 2);
    assert_eq!(events[0].event.as_deref(), Some("one"));
    assert_eq!(events[0].id.as_deref(), Some("7"));
    assert_eq!(events[1].data, "not json");

    let mut typed: EventStream<ResponseStreamEvent, default_api::CreateResponseError> =
        EventStream::from_bytes(futures_util::stream::iter(body()));
    assert!(matches!(
        typed.next().await,
        Some(Ok(ResponseStreamEvent::ResponseTextDeltaEvent(_)))
    ));
    let rest: Vec<Event> = typed.into_events().map(Result::unwrap).collect().await;
    assert_eq!(
        rest,
        vec![Event {
            event: Some("two".into()),
            data: "not json".into(),
            id: Some("7".into())
        }]
    );
}

/// The `_stream` method asks for the stream, checks the status and the content type, and streams the body.
#[tokio::test]
async fn the_stream_method_streams_the_response() {
    let (configuration, server) = serve(
        "200 OK",
        "text/event-stream; charset=utf-8",
        vec![
            "event: response.created\ndata: {\"type\":\"response.created\",\"sequence_number\":0,\"response\":{\"id\":\"r\"}}\n\n",
            "data: {\"type\":\"response.output_text.delta\",\"sequence_number\":1,\"delta\":\"He\"}\n\ndata: {\"type\":\"response.output_",
            "text.delta\",\"sequence_number\":2,\"delta\":\"llo\"}\n\n",
        ],
    )
    .await;
    let stream = default_api::create_response_stream()
        .configuration(&configuration)
        .create_response(&request())
        .call()
        .await
        .unwrap();
    let events: Vec<ResponseStreamEvent> = stream.map(Result::unwrap).collect().await;
    let text: String = events
        .iter()
        .filter_map(|e| match e {
            ResponseStreamEvent::ResponseTextDeltaEvent(d) => Some(d.delta.as_str()),
            _ => None,
        })
        .collect();
    assert_eq!(text, "Hello");
    assert_eq!(events.len(), 3);
    let request = server.await.unwrap();
    assert!(request.starts_with("POST /responses "), "{request}");
    assert!(
        request
            .to_ascii_lowercase()
            .contains("accept: text/event-stream"),
        "{request}"
    );
    assert!(request.contains("\"stream\":true"), "{request}");
}

/// An operation that only streams returns the stream from its own method; a plain struct streams with `[DONE]`.
#[tokio::test]
async fn an_operation_that_only_streams_returns_the_stream() {
    let (configuration, server) = serve(
        "200 OK",
        "text/event-stream",
        vec!["data: {\"type\":\"response.completed\",\"sequence_number\":0,\"response\":{\"id\":\"s\"}}\n\n"],
    )
    .await;
    let events: Vec<_> = default_api::list_events()
        .configuration(&configuration)
        .session_id("abc")
        .call()
        .await
        .unwrap()
        .collect()
        .await;
    assert_eq!(events.len(), 1);
    assert!(server
        .await
        .unwrap()
        .starts_with("GET /sessions/abc/events "));

    let (configuration, _server) = serve(
        "200 OK",
        "text/event-stream",
        vec![
            "data: {\"id\":\"c\",\"delta\":\"a\"}\n\n",
            "data: {\"id\":\"c\",\"delta\":\"b\"}\n\ndata: [DONE]\n\n",
        ],
    )
    .await;
    let chunks: Vec<Chunk> = default_api::create_chat_stream()
        .configuration(&configuration)
        .create_response(&request())
        .call()
        .await
        .unwrap()
        .map(Result::unwrap)
        .collect()
        .await;
    assert_eq!(
        chunks.iter().map(|c| c.delta.as_str()).collect::<String>(),
        "ab"
    );
}

/// An error status is the operation's typed error, as for the JSON method, with the response's headers; a body that is
/// not an event stream is an error instead of an empty stream.
#[tokio::test]
async fn errors_stay_typed() {
    let (configuration, _server) = serve(
        "400 Bad Request",
        // Two more header lines after the content type.
        "application/json\r\nx-request-id: req-stream\r\nretry-after: 7",
        vec!["{\"error\":{\"message\":\"no\"}}"],
    )
    .await;
    let error = default_api::create_response_stream()
        .configuration(&configuration)
        .create_response(&request())
        .call()
        .await
        .unwrap_err();
    match error {
        Error::ResponseError(content) => {
            assert_eq!(content.status.as_u16(), 400);
            assert_eq!(content.headers["x-request-id"], "req-stream");
            assert_eq!(content.headers["retry-after"], "7");
            match content.entity {
                Some(default_api::CreateResponseError::Status400(e)) => {
                    assert_eq!(e.error.message, "no")
                }
                other => panic!("{other:?}"),
            }
        }
        other => panic!("{other:?}"),
    }

    let (configuration, _server) =
        serve("200 OK", "application/json", vec!["{\"id\":\"r\"}"]).await;
    let error = default_api::create_response_stream()
        .configuration(&configuration)
        .create_response(&request())
        .call()
        .await
        .unwrap_err();
    assert!(
        matches!(&error, Error::Serde(e) if e.to_string().contains("application/json")),
        "{error:?}"
    );
}

/// The other request media types: JSON where the operation sends multipart, multipart where it sends JSON, and an SDP
/// text body.
#[tokio::test]
async fn each_request_media_type_has_its_method() {
    let (configuration, server) =
        serve("200 OK", "application/json", vec!["{\"text\":\"ok\"}"]).await;
    let out = default_api::create_edit_json()
        .configuration(&configuration)
        .create_edit_json(&CreateEditJson::builder().prompt("p".to_owned()).build())
        .call()
        .await
        .unwrap();
    assert_eq!(out.text, "ok");
    let request = server.await.unwrap().to_ascii_lowercase();
    assert!(
        request.contains("content-type: application/json"),
        "{request}"
    );
    assert!(request.ends_with("{\"prompt\":\"p\"}"), "{request}");

    let audio = std::env::temp_dir().join("event_streams_voice.wav");
    std::fs::write(&audio, b"RIFF").unwrap();
    let (configuration, server) =
        serve("200 OK", "application/json", vec!["{\"text\":\"voice\"}"]).await;
    default_api::create_voice_multipart()
        .configuration(&configuration)
        .audio(audio)
        .name("n")
        .call()
        .await
        .unwrap();
    let request = server.await.unwrap();
    assert!(
        request
            .to_ascii_lowercase()
            .contains("content-type: multipart/form-data; boundary="),
        "{request}"
    );
    assert!(
        request.contains("name=\"audio\"") && request.contains("RIFF"),
        "{request}"
    );

    let (configuration, server) = serve("201 Created", "text/plain", vec!["v=0 answer"]).await;
    let answer = default_api::create_call_sdp()
        .configuration(&configuration)
        .body("v=0 offer")
        .call()
        .await
        .unwrap();
    assert_eq!(answer, "v=0 answer");
    let request = server.await.unwrap();
    assert!(
        request
            .to_ascii_lowercase()
            .contains("content-type: application/sdp"),
        "{request}"
    );
    assert!(request.ends_with("\r\n\r\nv=0 offer"), "{request}");
}

/// A text response has its own method.
#[tokio::test]
async fn a_text_response_has_its_method() {
    let file = std::env::temp_dir().join("event_streams_audio.wav");
    std::fs::write(&file, b"RIFF").unwrap();
    let (configuration, _server) =
        serve("200 OK", "text/plain; charset=utf-8", vec!["hello world"]).await;
    let text = default_api::create_transcription_text()
        .configuration(&configuration)
        .file(file)
        .call()
        .await
        .unwrap();
    assert_eq!(text, "hello world");
}
