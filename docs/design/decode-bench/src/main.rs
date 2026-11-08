//! Decoding cost of a response body: the generated type versus a subset, a borrowed subset and raw JSON fields.
//!
//! Run with `cargo run --release`. Each variant decodes the same bytes many times; the table shows the median time per
//! decode and the allocations (count and bytes) of one decode, counted by a global allocator.

use serde::Deserialize;
use serde_json::{json, value::RawValue, Value};
use std::alloc::{GlobalAlloc, Layout, System};
use std::borrow::Cow;
use std::hint::black_box;
use std::sync::atomic::{AtomicUsize, Ordering::Relaxed};
use std::time::Instant;

// ------------------------------------------------------------------------------------------- allocation counting

struct Counting;

static ALLOCS: AtomicUsize = AtomicUsize::new(0);
static BYTES: AtomicUsize = AtomicUsize::new(0);

unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        ALLOCS.fetch_add(1, Relaxed);
        BYTES.fetch_add(layout.size(), Relaxed);
        System.alloc(layout)
    }
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        System.dealloc(ptr, layout)
    }
    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        ALLOCS.fetch_add(1, Relaxed);
        BYTES.fetch_add(new_size, Relaxed);
        System.realloc(ptr, layout, new_size)
    }
}

#[global_allocator]
static GLOBAL: Counting = Counting;

/// Median nanoseconds per call, and the allocations (count, bytes) of one call.
fn measure<T>(body: &[u8], decode: impl Fn(&[u8]) -> T) -> (f64, usize, usize) {
    // allocations of one call
    let (a0, b0) = (ALLOCS.load(Relaxed), BYTES.load(Relaxed));
    let value = decode(body);
    let (allocs, bytes) = (ALLOCS.load(Relaxed) - a0, BYTES.load(Relaxed) - b0);
    drop(black_box(value));
    // time: 15 rounds, median of the per-call average
    let iterations = (20_000_000 / body.len()).max(20);
    let mut rounds: Vec<f64> = (0..15)
        .map(|_| {
            let start = Instant::now();
            for _ in 0..iterations {
                black_box(decode(black_box(body)));
            }
            start.elapsed().as_nanos() as f64 / iterations as f64
        })
        .collect();
    rounds.sort_by(|a, b| a.partial_cmp(b).unwrap());
    (rounds[rounds.len() / 2], allocs, bytes)
}

fn report<T>(baseline: &mut Option<f64>, name: &str, body: &[u8], decode: impl Fn(&[u8]) -> T) {
    let (ns, allocs, bytes) = measure(body, decode);
    let base = *baseline.get_or_insert(ns);
    println!(
        "| {name} | {:.0} µs | {:.2}× | {allocs} | {:.0} KiB |",
        ns / 1000.0,
        ns / base,
        bytes as f64 / 1024.0
    );
}

// ------------------------------------------------------------------------------------------- payloads

/// A chat completion with token log probabilities: 2 choices of 400 tokens, each with 5 alternatives.
fn chat_completion() -> Vec<u8> {
    let token = |i: usize| {
        let alternatives: Vec<Value> = (0..5)
            .map(|j| json!({"token": format!(" alt{i}_{j}"), "logprob": -1.5 - j as f64, "bytes": [32, 97, 108, 116]}))
            .collect();
        json!({"token": format!(" word{i}"), "logprob": -0.25, "bytes": [32, 119, 111, 114, 100], "top_logprobs": alternatives})
    };
    let choice = |index: usize| {
        json!({
            "index": index,
            "finish_reason": "stop",
            "message": {
                "role": "assistant",
                "content": "The quick brown fox jumps over the lazy dog. ".repeat(40),
                "refusal": null,
                "annotations": []
            },
            "logprobs": {"content": (0..400).map(token).collect::<Vec<_>>(), "refusal": null}
        })
    };
    let body = json!({
        "id": "chatcmpl-123",
        "object": "chat.completion",
        "created": 1_700_000_000,
        "model": "gpt-4o-2024-08-06",
        "system_fingerprint": "fp_1",
        "service_tier": "default",
        "choices": [choice(0), choice(1)],
        "usage": {"prompt_tokens": 50, "completion_tokens": 800, "total_tokens": 850}
    });
    serde_json::to_vec(&body).unwrap()
}

/// A page of 100 Langfuse traces, each with chat messages as input and output and some metadata.
fn traces() -> Vec<u8> {
    let trace = |i: usize| {
        json!({
            "id": format!("trace-{i}"),
            "timestamp": "2026-11-08T10:00:00.000Z",
            "name": "chat",
            "input": {"messages": [
                {"role": "system", "content": "You are a helpful assistant. ".repeat(10)},
                {"role": "user", "content": format!("Question number {i}: ").repeat(8)}
            ]},
            "output": {"role": "assistant", "content": "An answer that is a few sentences long. ".repeat(12)},
            "sessionId": format!("session-{}", i / 10),
            "release": "1.2.3",
            "version": "v7",
            "userId": format!("user-{}", i % 7),
            "metadata": {"region": "eu", "tier": "pro", "attempt": 1, "flags": ["a", "b"]},
            "tags": ["prod", "chat"],
            "public": false,
            "environment": "production",
            "htmlPath": format!("/project/p/traces/trace-{i}"),
            "latency": 1.25,
            "totalCost": 0.0042,
            "observations": (0..8).map(|o| format!("obs-{i}-{o}")).collect::<Vec<_>>(),
            "scores": ["score-1", "score-2"]
        })
    };
    let body = json!({
        "data": (0..100).map(trace).collect::<Vec<_>>(),
        "meta": {"page": 1, "limit": 100, "totalItems": 1000, "totalPages": 10}
    });
    serde_json::to_vec(&body).unwrap()
}

// ------------------------------------------------------------------------------------------- consumer types

/// What a caller of a chat completion often reads: the id and each choice's text.
#[derive(Deserialize)]
#[allow(dead_code)]
struct ChatSubset {
    id: String,
    choices: Vec<ChoiceSubset>,
}
#[derive(Deserialize)]
#[allow(dead_code)]
struct ChoiceSubset {
    message: MessageSubset,
}
#[derive(Deserialize)]
#[allow(dead_code)]
struct MessageSubset {
    content: Option<String>,
}

/// The same, borrowing from the body: `Cow` because a JSON string with escapes cannot be borrowed as it is.
#[derive(Deserialize)]
#[allow(dead_code)]
struct ChatBorrowed<'a> {
    #[serde(borrow)]
    id: Cow<'a, str>,
    #[serde(borrow)]
    choices: Vec<ChoiceBorrowed<'a>>,
}
#[derive(Deserialize)]
#[allow(dead_code)]
struct ChoiceBorrowed<'a> {
    #[serde(borrow)]
    message: MessageBorrowed<'a>,
}
#[derive(Deserialize)]
#[allow(dead_code)]
struct MessageBorrowed<'a> {
    #[serde(borrow)]
    content: Option<Cow<'a, str>>,
}

/// Every field of a choice, with the heavy `logprobs` kept as unparsed JSON.
#[derive(Deserialize)]
#[allow(dead_code)]
struct ChatLazy<'a> {
    id: String,
    #[serde(borrow)]
    choices: Vec<ChoiceLazy<'a>>,
    created: i64,
    model: String,
    usage: Option<openai_client_base::models::CompletionUsage>,
}
#[derive(Deserialize)]
#[allow(dead_code)]
struct ChoiceLazy<'a> {
    index: i64,
    finish_reason: String,
    message: openai_client_base::models::ChatCompletionResponseMessage,
    #[serde(borrow)]
    logprobs: Option<&'a RawValue>,
}

/// What a trace list is often read for: id, name and latency.
#[derive(Deserialize)]
#[allow(dead_code)]
struct TracesSubset {
    data: Vec<TraceSubset>,
}
#[derive(Deserialize)]
#[allow(dead_code)]
struct TraceSubset {
    id: String,
    name: Option<String>,
    latency: Option<f64>,
}

#[derive(Deserialize)]
#[allow(dead_code)]
struct TracesBorrowed<'a> {
    #[serde(borrow)]
    data: Vec<TraceBorrowed<'a>>,
}
#[derive(Deserialize)]
#[allow(dead_code)]
struct TraceBorrowed<'a> {
    #[serde(borrow)]
    id: Cow<'a, str>,
    #[serde(borrow)]
    name: Option<Cow<'a, str>>,
    latency: Option<f64>,
}

/// Every trace field but `input`, `output` and `metadata`, which stay unparsed JSON.
#[derive(Deserialize)]
#[allow(dead_code)]
#[serde(rename_all = "camelCase")]
struct TracesLazy<'a> {
    #[serde(borrow)]
    data: Vec<TraceLazy<'a>>,
}
#[derive(Deserialize)]
#[allow(dead_code)]
#[serde(rename_all = "camelCase")]
struct TraceLazy<'a> {
    id: String,
    timestamp: String,
    name: Option<String>,
    #[serde(borrow)]
    input: Option<&'a RawValue>,
    #[serde(borrow)]
    output: Option<&'a RawValue>,
    #[serde(borrow)]
    metadata: Option<&'a RawValue>,
    session_id: Option<String>,
    user_id: Option<String>,
    tags: Vec<String>,
    environment: String,
    latency: Option<f64>,
    total_cost: Option<f64>,
    observations: Option<Vec<String>>,
}

// ------------------------------------------------------------------------------------------- main

fn run(title: &str, body: &[u8], variants: &mut dyn FnMut(&mut Option<f64>)) {
    println!("\n### {title} ({:.0} KiB)\n", body.len() as f64 / 1024.0);
    println!("| Variant | Time | vs. today | Allocations | Allocated |");
    println!("| --- | --- | --- | --- | --- |");
    let mut baseline = None;
    variants(&mut baseline);
}

fn main() {
    use langfuse_client_base::models::Traces;
    use openai_client_base::models::CreateChatCompletionResponse;

    let chat = chat_completion();
    serde_json::from_slice::<CreateChatCompletionResponse>(&chat).expect("the chat payload decodes");
    run("OpenAI chat completion with logprobs", &chat, &mut |b| {
        // Today: the generated function reads the body as text, then decodes the text.
        report(b, "generated type, from text (today)", &chat, |s| {
            let text = String::from_utf8(s.to_vec()).unwrap();
            serde_json::from_str::<CreateChatCompletionResponse>(&text).unwrap()
        });
        report(b, "generated type, from bytes", &chat, |s| {
            serde_json::from_slice::<CreateChatCompletionResponse>(s).unwrap()
        });
        report(b, "generated type, logprobs as raw JSON", &chat, |s| {
            serde_json::from_slice::<ChatLazy>(s).unwrap().choices.len()
        });
        report(b, "caller's subset, owned", &chat, |s| serde_json::from_slice::<ChatSubset>(s).unwrap());
        report(b, "caller's subset, borrowed", &chat, |s| {
            serde_json::from_slice::<ChatBorrowed>(s).unwrap().choices.len()
        });
    });

    let traces = traces();
    serde_json::from_slice::<Traces>(&traces).expect("the traces payload decodes");
    run("Langfuse trace list, 100 traces", &traces, &mut |b| {
        report(b, "generated type, from text (today)", &traces, |s| {
            let text = String::from_utf8(s.to_vec()).unwrap();
            serde_json::from_str::<Traces>(&text).unwrap()
        });
        report(b, "generated type, from bytes", &traces, |s| serde_json::from_slice::<Traces>(s).unwrap());
        report(b, "input, output and metadata as raw JSON", &traces, |s| {
            serde_json::from_slice::<TracesLazy>(s).unwrap().data.len()
        });
        report(b, "caller's subset, owned", &traces, |s| serde_json::from_slice::<TracesSubset>(s).unwrap());
        report(b, "caller's subset, borrowed", &traces, |s| {
            serde_json::from_slice::<TracesBorrowed>(s).unwrap().data.len()
        });
    });
}
