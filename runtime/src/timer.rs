//! Clocks and timers (feature `time`): tokio's natively; on WebAssembly (`target_family = "wasm"`) the JavaScript
//! host's `setTimeout`, with `web-time`'s clocks (`std::time::Instant::now` panics on `wasm32-unknown-unknown`).

use std::future::Future;
use std::pin::Pin;
use std::task::{Context, Poll};
use std::time::Duration;

/// The monotonic clock deadlines are read against: tokio's natively (so that tests can pause time).
#[cfg(not(target_family = "wasm"))]
pub(crate) use tokio::time::Instant;
/// The monotonic clock deadlines are read against: `performance.now()` on WebAssembly.
#[cfg(target_family = "wasm")]
pub(crate) use web_time::Instant;

/// The wall clock, as the time since the Unix epoch: `Date.now()` on WebAssembly.
#[cfg(feature = "retry")]
pub(crate) fn since_epoch() -> Duration {
    #[cfg(not(target_family = "wasm"))]
    use std::time::{SystemTime, UNIX_EPOCH};
    #[cfg(target_family = "wasm")]
    use web_time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
}

/// A timer that completes at a deadline, and can be moved to another.
pub(crate) struct Sleep {
    #[cfg(not(target_family = "wasm"))]
    inner: Pin<Box<tokio::time::Sleep>>,
    #[cfg(target_family = "wasm")]
    deadline: Instant,
    #[cfg(target_family = "wasm")]
    timeout: Option<gloo_timers::future::TimeoutFuture>,
}

impl Sleep {
    /// A timer that completes at `deadline`: at once when it is past.
    pub(crate) fn until(deadline: Instant) -> Self {
        #[cfg(not(target_family = "wasm"))]
        {
            Self {
                inner: Box::pin(tokio::time::sleep_until(deadline)),
            }
        }
        #[cfg(target_family = "wasm")]
        {
            Self {
                deadline,
                timeout: None,
            }
        }
    }

    /// Moves the timer to `deadline`.
    pub(crate) fn reset(&mut self, deadline: Instant) {
        #[cfg(not(target_family = "wasm"))]
        self.inner.as_mut().reset(deadline);
        #[cfg(target_family = "wasm")]
        {
            self.deadline = deadline;
            self.timeout = None;
        }
    }
}

impl Future for Sleep {
    type Output = ();

    #[cfg(not(target_family = "wasm"))]
    fn poll(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<()> {
        self.inner.as_mut().poll(cx)
    }

    #[cfg(target_family = "wasm")]
    fn poll(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<()> {
        let this = &mut *self;
        loop {
            let left = this.deadline.saturating_duration_since(Instant::now());
            if left.is_zero() {
                this.timeout = None;
                return Poll::Ready(());
            }
            // `setTimeout` counts whole milliseconds, and fires at once for a delay over 2^31 - 1 ms: round up, and
            // wait in steps for a longer one. A timer that fires early (clocks of different precision) is armed again.
            let timeout = this.timeout.get_or_insert_with(|| {
                gloo_timers::future::TimeoutFuture::new(timeout_millis(left))
            });
            match Pin::new(timeout).poll(cx) {
                Poll::Ready(()) => this.timeout = None,
                Poll::Pending => return Poll::Pending,
            }
        }
    }
}

/// A delay for `setTimeout`: whole milliseconds, rounded up, at most 2^31 - 1.
#[cfg_attr(not(target_family = "wasm"), allow(dead_code))]
fn timeout_millis(delay: Duration) -> u32 {
    const MAX: u128 = i32::MAX as u128;
    delay.as_nanos().div_ceil(1_000_000).min(MAX) as u32
}

impl std::fmt::Debug for Sleep {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Sleep").finish_non_exhaustive()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn timeouts_round_up_and_stay_in_range() {
        assert_eq!(timeout_millis(Duration::ZERO), 0);
        assert_eq!(timeout_millis(Duration::from_nanos(1)), 1);
        assert_eq!(timeout_millis(Duration::from_micros(1500)), 2);
        assert_eq!(timeout_millis(Duration::from_secs(2)), 2000);
        assert_eq!(
            timeout_millis(Duration::from_secs(100 * 86_400)),
            i32::MAX as u32
        );
    }
}
