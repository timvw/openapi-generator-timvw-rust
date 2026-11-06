//! The unions jsonValueSchemas used to hide, at run time, on the crate generated from
//! src/test/resources/event_unions.yaml. `EventUnionsTest` copies this file into that crate's tests/ and runs cargo.

use event_unions_fixture::models::*;
use serde::de::DeserializeOwned;
use serde_json::{from_value, json, to_value, Value};

fn decode<T: DeserializeOwned>(value: Value) -> T {
    from_value(value.clone()).unwrap_or_else(|e| panic!("{value}: {e}"))
}

fn error<T: DeserializeOwned + std::fmt::Debug>(value: Value) -> String {
    from_value::<T>(value.clone())
        .map(|v| panic!("{value} decoded to {v:?}"))
        .unwrap_err()
        .to_string()
}

/// A stream of server-sent events: each `data:` payload decodes to the variant its `type` names.
#[test]
fn a_response_stream_decodes_event_by_event() {
    let events = [
        json!({"type": "response.created", "sequence_number": 0,
               "response": {"id": "resp_1", "output": []}}),
        json!({"type": "response.output_item.added", "sequence_number": 1, "output_index": 0,
               "item": {"type": "function_call", "call_id": "c1", "name": "f", "arguments": "{}"}}),
        json!({"type": "response.output_text.delta", "sequence_number": 2, "delta": "Hi"}),
        json!({"type": "response.reasoning.delta", "sequence_number": 3, "delta": "new"}),
    ];
    let decoded: Vec<ResponseStreamEvent> = events.iter().cloned().map(decode).collect();
    match &decoded[0] {
        ResponseStreamEvent::ResponseCreatedEvent(e) => assert_eq!(e.response.id, "resp_1"),
        other => panic!("{other:?}"),
    }
    match &decoded[1] {
        ResponseStreamEvent::ResponseOutputItemAddedEvent(e) => match &e.item {
            OutputItem::FunctionCall(call) => assert_eq!(call.name, "f"),
            other => panic!("{other:?}"),
        },
        other => panic!("{other:?}"),
    }
    match &decoded[2] {
        ResponseStreamEvent::ResponseTextDeltaEvent(e) => assert_eq!(e.delta, "Hi"),
        other => panic!("{other:?}"),
    }
    // A new event type does not end the stream: it is kept as it came.
    assert_eq!(decoded[3], ResponseStreamEvent::Unknown(events[3].clone()));
    // Every event serializes to what came, tag included once.
    for (event, value) in decoded.iter().zip(events.iter()) {
        assert_eq!(&to_value(event).unwrap(), value);
    }
}

/// The output of a response: every item type, the one that is allOf included, and one this client does not know.
#[test]
fn a_response_output_is_typed_items() {
    let value = json!({"id": "resp_1", "output": [
        {"type": "message", "id": "m1", "text": "hello"},
        {"type": "function_call_output", "call_id": "c1", "output": "42", "id": "o1"},
        {"type": "computer_call", "id": "cc1", "action": {"type": "click", "x": 1, "y": 2},
         "actions": [{"type": "wait"}, {"type": "drag", "path": []}]},
        {"type": "new_tool_call", "id": "n1"},
    ]});
    let response: Response = decode(value.clone());
    assert!(matches!(&response.output[0], OutputItem::OutputMessage(m) if m.text == "hello"));
    assert!(matches!(&response.output[1], OutputItem::FunctionCallOutputResource(o) if o.id == "o1" && o.output == "42"));
    match &response.output[2] {
        OutputItem::ComputerCall(call) => {
            assert!(matches!(call.action.as_ref().unwrap(), ComputerAction::ClickParam(c) if c.x == 1 && c.y == 2));
            let actions = call.actions.as_ref().unwrap();
            assert!(matches!(actions[0], ComputerAction::WaitParam(_)));
            assert_eq!(actions[1], ComputerAction::Unknown(json!({"type": "drag", "path": []})));
        }
        other => panic!("{other:?}"),
    }
    assert!(matches!(&response.output[3], OutputItem::Unknown(v) if v["id"] == "n1"));
    assert_eq!(to_value(&response).unwrap(), value);
}

/// The tag selects the variant, so the error is that variant's, not "did not match any variant".
#[test]
fn a_known_tag_with_a_broken_payload_names_the_variant() {
    let message = error::<OutputItem>(json!({"type": "function_call", "call_id": "c1"}));
    assert!(message.contains("FunctionCall"), "{message}");
    assert!(message.contains("name"), "{message}");
}

#[test]
fn transcription_events_decode_by_tag() {
    let delta: TranscriptionStreamEvent = decode(json!({"type": "transcript.text.delta", "delta": "he"}));
    assert!(matches!(delta, TranscriptionStreamEvent::TranscriptTextDeltaEvent(d) if d.delta == "he"));
    let done: TranscriptionStreamEvent = decode(json!({"type": "transcript.text.done", "text": "hello"}));
    assert!(matches!(done, TranscriptionStreamEvent::TranscriptTextDoneEvent(d) if d.text == "hello"));
    let new: TranscriptionStreamEvent = decode(json!({"type": "transcript.text.segment", "id": "s"}));
    assert!(matches!(new, TranscriptionStreamEvent::Unknown(_)));
}

/// No operation reaches the realtime events, so they stay closed: an unknown type is an error that says so.
#[test]
fn realtime_events_are_typed_and_closed() {
    let created: RealtimeServerEvent = decode(json!({"type": "session.created", "event_id": "e1"}));
    assert!(matches!(created, RealtimeServerEvent::RealtimeSessionCreated(e) if e.event_id == "e1"));
    let failed: RealtimeServerEvent = decode(json!({"type": "error", "event_id": "e2", "message": "no"}));
    assert!(matches!(failed, RealtimeServerEvent::RealtimeError(e) if e.message == "no"));
    let message = error::<RealtimeServerEvent>(json!({"type": "session.updated", "event_id": "e3"}));
    assert!(message.contains("session.updated"), "{message}");
}
