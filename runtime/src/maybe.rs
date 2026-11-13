//! `Send` and `Sync` natively, nothing on WebAssembly.
//!
//! On `wasm32-unknown-unknown` there is one thread, and what the JavaScript host hands out (a `fetch` response, a
//! timer) cannot leave it: reqwest's futures and streams there are neither `Send` nor `Sync`. So the bounds this crate
//! puts on services, bodies, streams and futures are [`MaybeSend`] and [`MaybeSync`]: `Send` and `Sync` natively, no
//! bound at all on WebAssembly (`target_family = "wasm"`).

use std::future::Future;
use std::pin::Pin;

use futures_core::Stream;

/// `Send` natively; any type on WebAssembly (`target_family = "wasm"`), where futures and streams of the JavaScript
/// host are not `Send`. Use it in bounds that must hold on both, such as a service given to
/// [`Client::from_service`](crate::Client::from_service).
#[cfg(not(target_family = "wasm"))]
pub trait MaybeSend: Send {}
#[cfg(not(target_family = "wasm"))]
impl<T: Send + ?Sized> MaybeSend for T {}

/// `Send` natively; any type on WebAssembly (`target_family = "wasm"`), where futures and streams of the JavaScript
/// host are not `Send`. Use it in bounds that must hold on both, such as a service given to
/// [`Client::from_service`](crate::Client::from_service).
#[cfg(target_family = "wasm")]
pub trait MaybeSend {}
#[cfg(target_family = "wasm")]
impl<T: ?Sized> MaybeSend for T {}

/// `Sync` natively; any type on WebAssembly (`target_family = "wasm"`).
#[cfg(not(target_family = "wasm"))]
pub trait MaybeSync: Sync {}
#[cfg(not(target_family = "wasm"))]
impl<T: Sync + ?Sized> MaybeSync for T {}

/// `Sync` natively; any type on WebAssembly (`target_family = "wasm"`).
#[cfg(target_family = "wasm")]
pub trait MaybeSync {}
#[cfg(target_family = "wasm")]
impl<T: ?Sized> MaybeSync for T {}

/// A boxed future: `Send` natively.
#[cfg(not(target_family = "wasm"))]
pub(crate) type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;
#[cfg(target_family = "wasm")]
pub(crate) type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + 'a>>;

/// A boxed stream: `Send` natively.
#[cfg(not(target_family = "wasm"))]
pub(crate) type BoxStream<'a, T> = Pin<Box<dyn Stream<Item = T> + Send + 'a>>;
#[cfg(target_family = "wasm")]
pub(crate) type BoxStream<'a, T> = Pin<Box<dyn Stream<Item = T> + 'a>>;

/// Boxes a stream: `Send` natively.
pub(crate) fn box_stream<'a, S: Stream + MaybeSend + 'a>(stream: S) -> BoxStream<'a, S::Item> {
    Box::pin(stream)
}
