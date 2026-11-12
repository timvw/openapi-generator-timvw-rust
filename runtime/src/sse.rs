//! Server-sent events (`text/event-stream`): a parser, the events of a response, and their `data:` decoded as JSON.
//!
//! [`Response::events`] gives the events of a response as they arrive; [`Events::json`] decodes each `data:` payload
//! to a type of your own. A `data: [DONE]` event or the end of the body ends the stream. [`Decoder`] is the parser
//! underneath, for a body read some other way.
//!
//! ```no_run
//! # async fn demo(client: timvw_rust_runtime::Client) -> Result<(), timvw_rust_runtime::Error> {
//! use futures_util::StreamExt;
//!
//! #[derive(serde::Deserialize)]
//! struct Chunk {
//!     id: String,
//! }
//!
//! let response = client
//!     .request(timvw_rust_runtime::Method::POST, "https://api.example.com/v1/stream")
//!     .json(&serde_json::json!({"stream": true}))
//!     .send()
//!     .await?;
//! let mut chunks = response.events().json::<Chunk>();
//! while let Some(chunk) = chunks.next().await {
//!     println!("{}", chunk?.id);
//! }
//! # Ok(())
//! # }
//! ```

use std::collections::VecDeque;
use std::fmt;
use std::marker::PhantomData;
use std::pin::Pin;
use std::task::{Context, Poll};

use futures_core::Stream;
use serde::de::DeserializeOwned;

use crate::{Error, Response};

/// The `data:` value that ends a stream.
pub const DONE: &str = "[DONE]";

/// One server-sent event, before its data is decoded.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Event {
    /// The `event:` field, when the event has one.
    pub event: Option<String>,
    /// The `data:` lines of the event, joined with `\n`.
    pub data: String,
    /// The last `id:` field seen on the stream, when there was one.
    pub id: Option<String>,
}

/// An incremental `text/event-stream` parser, following the HTML standard: lines end with CR, LF or CRLF, a line
/// starting with `:` is a comment, an empty line dispatches the event, and an event without `data:` is not
/// dispatched. Feed it the body as it arrives.
#[derive(Clone, Debug, Default)]
pub struct Decoder {
    buffer: Vec<u8>,
    after_cr: bool,
    started: bool,
    event: Option<String>,
    data: String,
    has_data: bool,
    id: Option<String>,
}

impl Decoder {
    /// A parser at the start of a stream.
    pub fn new() -> Self {
        Self::default()
    }

    /// Takes the next chunk of the body and appends the events it completes to `events`.
    pub fn feed(&mut self, chunk: &[u8], events: &mut VecDeque<Event>) {
        let mut chunk = chunk;
        if !self.started {
            // A UTF-8 byte order mark at the start of the stream is skipped.
            let pending = self.buffer.len() + chunk.len();
            let mut head = self.buffer.clone();
            head.extend_from_slice(&chunk[..chunk.len().min(3)]);
            if pending < 3 && b"\xEF\xBB\xBF".starts_with(&head) {
                self.buffer.extend_from_slice(chunk);
                return;
            }
            self.started = true;
            let mut all = std::mem::take(&mut self.buffer);
            all.extend_from_slice(chunk);
            let rest = all.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(&all).to_vec();
            self.feed_lines(&rest, events);
            return;
        }
        if self.after_cr {
            if let Some(rest) = chunk.strip_prefix(b"\n") {
                chunk = rest;
            }
            self.after_cr = false;
        }
        self.feed_lines(chunk, events);
    }

    fn feed_lines(&mut self, chunk: &[u8], events: &mut VecDeque<Event>) {
        let mut start = 0;
        let mut i = 0;
        while i < chunk.len() {
            match chunk[i] {
                b'\n' | b'\r' => {
                    let mut line = std::mem::take(&mut self.buffer);
                    line.extend_from_slice(&chunk[start..i]);
                    self.line(&line, events);
                    if chunk[i] == b'\r' {
                        if i + 1 < chunk.len() {
                            if chunk[i + 1] == b'\n' {
                                i += 1;
                            }
                        } else {
                            self.after_cr = true;
                        }
                    }
                    start = i + 1;
                }
                _ => {}
            }
            i += 1;
        }
        self.buffer.extend_from_slice(&chunk[start..]);
    }

    fn line(&mut self, line: &[u8], events: &mut VecDeque<Event>) {
        if line.is_empty() {
            if self.has_data {
                events.push_back(Event {
                    event: self.event.take(),
                    data: std::mem::take(&mut self.data),
                    id: self.id.clone(),
                });
            }
            self.event = None;
            self.data.clear();
            self.has_data = false;
            return;
        }
        if line[0] == b':' {
            return;
        }
        let line = String::from_utf8_lossy(line);
        let (field, value) = match line.find(':') {
            Some(colon) => {
                let value = &line[colon + 1..];
                (&line[..colon], value.strip_prefix(' ').unwrap_or(value))
            }
            None => (&line[..], ""),
        };
        match field {
            "data" => {
                if self.has_data {
                    self.data.push('\n');
                }
                self.data.push_str(value);
                self.has_data = true;
            }
            "event" => self.event = Some(value.to_owned()),
            "id" if !value.contains('\0') => self.id = Some(value.to_owned()),
            _ => {}
        }
    }

    /// The body ended: an event not completed by an empty line is discarded, as the standard requires.
    pub fn finish(&mut self) {
        self.buffer.clear();
        self.event = None;
        self.data.clear();
        self.has_data = false;
    }
}

/// The events of a `text/event-stream` body, as they come. A `data: [DONE]` event ends it, as does the end of the body
/// or a failure reading it (yielded as `Err` first); the body is dropped then, which frees the connection.
pub struct Events {
    bytes: Option<Body>,
    decoder: Decoder,
    ready: VecDeque<Event>,
}

type Chunk = Box<dyn AsRef<[u8]> + Send>;
type Body = Pin<Box<dyn Stream<Item = Result<Chunk, Error>> + Send>>;

impl Events {
    /// The events of a response body. The status and the content type are not checked.
    pub fn from_response(response: Response) -> Self {
        Self::from_bytes(response.bytes_stream())
    }

    /// The events of a body read as a stream of chunks.
    pub fn from_bytes<S, B>(bytes: S) -> Self
    where
        S: Stream<Item = Result<B, Error>> + Send + 'static,
        B: AsRef<[u8]> + Send + 'static,
    {
        Self {
            bytes: Some(Box::pin(Chunks(Box::pin(bytes)))),
            decoder: Decoder::new(),
            ready: VecDeque::new(),
        }
    }
}

impl Stream for Events {
    type Item = Result<Event, Error>;

    fn poll_next(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        let this = &mut *self;
        loop {
            if let Some(event) = this.ready.pop_front() {
                if event.data == DONE {
                    this.ready.clear();
                    this.bytes = None;
                    return Poll::Ready(None);
                }
                return Poll::Ready(Some(Ok(event)));
            }
            let Some(bytes) = this.bytes.as_mut() else {
                return Poll::Ready(None);
            };
            match bytes.as_mut().poll_next(cx) {
                Poll::Pending => return Poll::Pending,
                Poll::Ready(Some(Ok(chunk))) => {
                    this.decoder.feed((*chunk).as_ref(), &mut this.ready)
                }
                Poll::Ready(Some(Err(error))) => {
                    this.bytes = None;
                    this.decoder.finish();
                    return Poll::Ready(Some(Err(error)));
                }
                Poll::Ready(None) => {
                    this.bytes = None;
                    this.decoder.finish();
                }
            }
        }
    }
}

impl fmt::Debug for Events {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Events")
            .field("open", &self.bytes.is_some())
            .field("ready", &self.ready)
            .finish_non_exhaustive()
    }
}

/// A byte stream whose chunks are boxed, so that [`Events`] holds any body.
struct Chunks<S>(Pin<Box<S>>);

impl<S, B> Stream for Chunks<S>
where
    S: Stream<Item = Result<B, Error>>,
    B: AsRef<[u8]> + Send + 'static,
{
    type Item = Result<Chunk, Error>;

    fn poll_next(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        self.0
            .as_mut()
            .poll_next(cx)
            .map(|item| item.map(|chunk| chunk.map(|bytes| Box::new(bytes) as Chunk)))
    }
}

impl Events {
    /// Decodes the `data:` payload of every event as JSON, to `T`.
    ///
    /// A payload that does not decode yields an error for which [`Error::is_decode`] is true, and the stream goes on
    /// with the next event; an event with an empty payload yields nothing. A failure reading the body yields an error
    /// and ends the stream.
    pub fn json<T: DeserializeOwned>(self) -> JsonEvents<T> {
        JsonEvents {
            events: self,
            types: PhantomData,
        }
    }
}

/// The events of a stream with their `data:` decoded as JSON: [`Events::json`].
pub struct JsonEvents<T> {
    events: Events,
    types: PhantomData<fn() -> T>,
}

impl<T> JsonEvents<T> {
    /// The events not yet read, undecoded.
    pub fn into_events(self) -> Events {
        self.events
    }
}

impl<T: DeserializeOwned> Stream for JsonEvents<T> {
    type Item = Result<T, Error>;

    fn poll_next(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        loop {
            match Pin::new(&mut self.events).poll_next(cx) {
                Poll::Ready(Some(Ok(event))) if event.data.trim().is_empty() => {}
                Poll::Ready(Some(Ok(event))) => {
                    return Poll::Ready(Some(
                        serde_json::from_str(&event.data).map_err(Error::decode),
                    ));
                }
                Poll::Ready(Some(Err(error))) => return Poll::Ready(Some(Err(error))),
                Poll::Ready(None) => return Poll::Ready(None),
                Poll::Pending => return Poll::Pending,
            }
        }
    }
}

impl<T> fmt::Debug for JsonEvents<T> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("JsonEvents")
            .field("events", &self.events)
            .finish()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures_util::StreamExt;

    fn events(chunks: &[&'static str]) -> Events {
        let chunks: Vec<Result<&'static [u8], Error>> =
            chunks.iter().map(|chunk| Ok(chunk.as_bytes())).collect();
        Events::from_bytes(futures_util::stream::iter(chunks))
    }

    #[test]
    fn parses_across_chunks_and_line_endings() {
        let mut decoder = Decoder::new();
        let mut out = VecDeque::new();
        decoder.feed(b"\xEF\xBB\xBFevent: a\r", &mut out);
        decoder.feed(b"\ndata: 1\r\ndata:", &mut out);
        decoder.feed(b"2\n: comment\nid: 7\n\ndata: x\n", &mut out);
        decoder.finish();
        assert_eq!(
            out.into_iter().collect::<Vec<_>>(),
            vec![Event {
                event: Some("a".into()),
                data: "1\n2".into(),
                id: Some("7".into()),
            }]
        );
    }

    #[tokio::test]
    async fn done_ends_the_stream() {
        let data: Vec<_> = events(&["data: 1\n\n", "data: [DONE]\n\ndata: 2\n\n"])
            .map(|event| event.unwrap().data)
            .collect()
            .await;
        assert_eq!(data, vec!["1"]);
    }

    #[tokio::test]
    async fn decodes_json_and_goes_on_after_a_bad_payload() {
        #[derive(serde::Deserialize, Debug, PartialEq)]
        struct Chunk {
            n: u32,
        }
        let items: Vec<_> =
            events(&["data: {\"n\":1}\n\ndata: oops\n\ndata:\n\ndata: {\"n\":2}\n\n"])
                .json::<Chunk>()
                .collect()
                .await;
        assert_eq!(items.len(), 3);
        assert_eq!(items[0].as_ref().unwrap(), &Chunk { n: 1 });
        assert!(items[1].as_ref().unwrap_err().is_decode());
        assert_eq!(items[2].as_ref().unwrap(), &Chunk { n: 2 });
    }
}
