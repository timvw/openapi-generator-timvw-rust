use std::fmt;
use std::future::Future;
use std::pin::Pin;
use std::task::{Context, Poll};

use tower::{Service, ServiceExt};

type LocalBoxFuture<T> = Pin<Box<dyn Future<Output = T>>>;

/// A boxed service that can be cloned, like tower's `BoxCloneService`, but neither the service nor its future need be
/// `Send`: [`HttpService`](crate::HttpService) on WebAssembly (`target_family = "wasm"`), where reqwest's futures are
/// not `Send`.
///
/// Natively [`HttpService`](crate::HttpService) is tower's `BoxCloneSyncService`; this type compiles there too, for
/// code of your own.
pub struct LocalBoxCloneService<T, U, E>(
    Box<dyn CloneService<T, Response = U, Error = E, Future = LocalBoxFuture<Result<U, E>>>>,
);

impl<T, U, E> LocalBoxCloneService<T, U, E> {
    /// Boxes `inner`.
    pub fn new<S>(inner: S) -> Self
    where
        S: Service<T, Response = U, Error = E> + Clone + 'static,
        S::Future: 'static,
    {
        let inner = inner.map_future(|future| Box::pin(future) as LocalBoxFuture<_>);
        Self(Box::new(inner))
    }
}

impl<T, U, E> Service<T> for LocalBoxCloneService<T, U, E> {
    type Response = U;
    type Error = E;
    type Future = LocalBoxFuture<Result<U, E>>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), E>> {
        self.0.poll_ready(cx)
    }

    fn call(&mut self, request: T) -> Self::Future {
        self.0.call(request)
    }
}

impl<T, U, E> Clone for LocalBoxCloneService<T, U, E> {
    fn clone(&self) -> Self {
        Self(self.0.clone_box())
    }
}

impl<T, U, E> fmt::Debug for LocalBoxCloneService<T, U, E> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("LocalBoxCloneService")
            .finish_non_exhaustive()
    }
}

trait CloneService<R>: Service<R> {
    #[allow(clippy::type_complexity)]
    fn clone_box(
        &self,
    ) -> Box<
        dyn CloneService<R, Response = Self::Response, Error = Self::Error, Future = Self::Future>,
    >;
}

impl<R, S> CloneService<R> for S
where
    S: Service<R> + Clone + 'static,
{
    fn clone_box(
        &self,
    ) -> Box<dyn CloneService<R, Response = S::Response, Error = S::Error, Future = S::Future>>
    {
        Box::new(self.clone())
    }
}

#[cfg(test)]
mod tests {
    use std::rc::Rc;

    use super::*;

    #[tokio::test]
    async fn holds_and_clones_a_service_that_is_not_send() {
        let shared = Rc::new(2);
        let service = tower::service_fn(move |n: i32| {
            let shared = shared.clone();
            async move { Ok::<_, std::convert::Infallible>(n * *shared) }
        });
        let boxed = LocalBoxCloneService::new(service);
        let copy = boxed.clone();
        assert_eq!(boxed.oneshot(3).await.unwrap(), 6);
        assert_eq!(copy.oneshot(4).await.unwrap(), 8);
    }
}
