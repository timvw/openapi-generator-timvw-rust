use std::error::Error as StdError;
use std::fmt;

use crate::BoxError;

/// An error building a request, sending it, or reading the response body.
pub struct Error {
    kind: Kind,
    source: Option<BoxError>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Kind {
    Builder,
    Request,
    Body,
    Decode,
    Io,
}

impl Error {
    pub(crate) fn builder(source: impl Into<BoxError>) -> Self {
        Self {
            kind: Kind::Builder,
            source: Some(source.into()),
        }
    }

    pub(crate) fn request(source: impl Into<BoxError>) -> Self {
        Self {
            kind: Kind::Request,
            source: Some(source.into()),
        }
    }

    pub(crate) fn body(source: impl Into<BoxError>) -> Self {
        Self {
            kind: Kind::Body,
            source: Some(source.into()),
        }
    }

    pub(crate) fn decode(source: impl Into<BoxError>) -> Self {
        Self {
            kind: Kind::Decode,
            source: Some(source.into()),
        }
    }

    pub(crate) fn io(source: impl Into<BoxError>) -> Self {
        Self {
            kind: Kind::Io,
            source: Some(source.into()),
        }
    }

    /// The request could not be built: an invalid URL, header, or body.
    pub fn is_builder(&self) -> bool {
        self.kind == Kind::Builder
    }

    /// The service failed to send the request or to receive a response: connection refused, a timeout layer gave up,
    /// and so on. [`Error::source`](std::error::Error::source) is the service's error.
    pub fn is_request(&self) -> bool {
        self.kind == Kind::Request
    }

    /// Reading the response body failed.
    pub fn is_body(&self) -> bool {
        self.kind == Kind::Body
    }

    /// The response body could not be decoded.
    pub fn is_decode(&self) -> bool {
        self.kind == Kind::Decode
    }

    /// A file could not be read or written: an [`Upload`](crate::Upload) whose file is missing, or
    #[cfg_attr(
        all(feature = "fs", not(target_family = "wasm")),
        doc = "[`Response::save_to`](crate::Response::save_to) failing to write."
    )]
    #[cfg_attr(
        not(all(feature = "fs", not(target_family = "wasm"))),
        doc = "`Response::save_to` (feature `fs`, not on WebAssembly) failing to write."
    )]
    /// [`Error::source`](std::error::Error::source) is the `std::io::Error`.
    pub fn is_io(&self) -> bool {
        self.kind == Kind::Io
    }

    /// Whether the request timed out: a reqwest timeout, tower's `timeout` layer, this crate's
    #[cfg_attr(
        feature = "time",
        doc = "[`TimeoutLayer`](crate::timeout::TimeoutLayer), or an [`idle_timeout`](crate::sse::Events::idle_timeout)"
    )]
    #[cfg_attr(
        not(feature = "time"),
        doc = "`timeout::TimeoutLayer`, or an `idle_timeout` (both feature `time`)"
    )]
    /// of server-sent events.
    pub fn is_timeout(&self) -> bool {
        self.source
            .as_deref()
            .is_some_and(|source| is_timeout(source as _))
    }

    /// Whether connecting to the server failed (only known for reqwest, and not on WebAssembly, where `fetch` does not
    /// tell).
    pub fn is_connect(&self) -> bool {
        #[cfg(all(feature = "reqwest", not(target_family = "wasm")))]
        {
            let mut source: Option<&(dyn StdError + 'static)> =
                self.source.as_deref().map(|e| e as _);
            while let Some(error) = source {
                if let Some(error) = error.downcast_ref::<reqwest::Error>() {
                    if error.is_connect() {
                        return true;
                    }
                }
                source = error.source();
            }
        }
        false
    }
}

impl fmt::Debug for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Error")
            .field("kind", &self.kind)
            .field("source", &self.source)
            .finish()
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let what = match self.kind {
            Kind::Builder => "error building the request",
            Kind::Request => "error sending the request",
            Kind::Body => "error reading the response body",
            Kind::Decode => "error decoding the response body",
            Kind::Io => "I/O error",
        };
        match &self.source {
            Some(source) => write!(f, "{what}: {source}"),
            None => f.write_str(what),
        }
    }
}

impl StdError for Error {
    fn source(&self) -> Option<&(dyn StdError + 'static)> {
        self.source.as_deref().map(|e| e as _)
    }
}

/// Whether `error`, or an error it was caused by, is a timeout: tower's `Elapsed`, a reqwest timeout, or [`TimedOut`].
pub(crate) fn is_timeout(error: &(dyn StdError + 'static)) -> bool {
    let mut source = Some(error);
    while let Some(error) = source {
        if error.to_string() == "request timed out" || error.is::<TimedOut>() {
            return true;
        }
        #[cfg(feature = "reqwest")]
        if let Some(error) = error.downcast_ref::<reqwest::Error>() {
            if error.is_timeout() {
                return true;
            }
        }
        source = error.source();
    }
    false
}

/// Nothing arrived within a time limit, such as an `idle_timeout` of server-sent events.
#[derive(Debug)]
#[cfg_attr(not(feature = "time"), allow(dead_code))]
pub(crate) struct TimedOut(pub(crate) String);

impl fmt::Display for TimedOut {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl StdError for TimedOut {}
