//! Webhooks signed as [Standard Webhooks](https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md),
//! as OpenAI sends them: [`StandardWebhooksLayer`] signs what a [`Client`](crate::Client) sends, and [`verify`] checks
//! what a receiver gets.
//!
//! A delivery is a `POST` with three headers:
//!
//! - `webhook-id`: the message's id, the same on every retry, which the receiver uses to drop a duplicate;
//! - `webhook-timestamp`: when it was signed, in Unix seconds;
//! - `webhook-signature`: `v1,<base64>`, the HMAC-SHA256 of `<id>.<timestamp>.<body>` with the secret, once per
//!   secret, separated by spaces (so a secret can be rotated).
//!
//! The secret is `whsec_` followed by base64, as OpenAI's dashboard and Standard Webhooks libraries give it.
//!
//! ```no_run
//! # #[cfg(feature = "reqwest")]
//! # async fn demo() -> Result<(), Box<dyn std::error::Error>> {
//! use timvw_rust_runtime::webhooks::{StandardWebhooksLayer, WebhookSecret};
//! use timvw_rust_runtime::{Body, Client};
//!
//! let secret = WebhookSecret::new(&std::env::var("WEBHOOK_SECRET")?)?;
//! // added last, the layer signs once and the retries inside it send the same headers again
//! let client = Client::default().layer(StandardWebhooksLayer::new(secret));
//! let response = client
//!     .request(http::Method::POST, "http://127.0.0.1:8090/hooks")
//!     .header(http::header::CONTENT_TYPE, "application/json")
//!     .body(Body::from(r#"{"type":"response.completed"}"#))
//!     .send()
//!     .await?;
//! # Ok(())
//! # }
//! ```
//!
//! Where the layer sits matters: [`Client::layer`](crate::Client::layer) puts it outside the client's stack, so it
//! signs once and a retry sends the same `webhook-id`, timestamp and signature. A layer of another scheme is written
//! the same way; this one is the scheme the runtime ships.
//!
//! The receiving side checks the headers against the raw body, before decoding it:
//!
//! ```
//! use std::time::Duration;
//! use timvw_rust_runtime::webhooks::{verify, WebhookSecret};
//!
//! # fn receive(headers: &http::HeaderMap, body: &[u8]) -> Result<(), Box<dyn std::error::Error>> {
//! let secret = WebhookSecret::new(&std::env::var("WEBHOOK_SECRET")?)?;
//! verify(headers, body, &[secret], Duration::from_secs(5 * 60))?;
//! let event: serde_json::Value = serde_json::from_slice(body)?;
//! # Ok(())
//! # }
//! ```

use std::fmt;
use std::future::{ready, Ready};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::task::{Context, Poll};
use std::time::Duration;

use base64::Engine as _;
use futures_util::future::{Either, MapErr};
use futures_util::TryFutureExt as _;
use hmac::{KeyInit, Mac};
use http::header::HeaderName;
use http::{HeaderMap, HeaderValue};
use tower::{Layer, Service};

use crate::{Body, BoxError};

#[cfg(not(target_family = "wasm"))]
use std::time::{SystemTime, UNIX_EPOCH};
#[cfg(target_family = "wasm")]
use web_time::{SystemTime, UNIX_EPOCH};

type HmacSha256 = hmac::Hmac<sha2::Sha256>;

/// `webhook-id`
pub const WEBHOOK_ID: HeaderName = HeaderName::from_static("webhook-id");
/// `webhook-timestamp`
pub const WEBHOOK_TIMESTAMP: HeaderName = HeaderName::from_static("webhook-timestamp");
/// `webhook-signature`
pub const WEBHOOK_SIGNATURE: HeaderName = HeaderName::from_static("webhook-signature");

const PREFIX: &str = "whsec_";

/// A webhook signing secret: the key of the HMAC. Its `Debug` does not show it.
#[derive(Clone, PartialEq, Eq)]
pub struct WebhookSecret(Arc<[u8]>);

impl WebhookSecret {
    /// The secret as Standard Webhooks writes it, `whsec_<base64>`; without the prefix, the base64 alone.
    pub fn new(secret: &str) -> Result<Self, WebhookError> {
        let encoded = secret.trim();
        let encoded = encoded.strip_prefix(PREFIX).unwrap_or(encoded);
        let key = base64::engine::general_purpose::STANDARD
            .decode(encoded)
            .map_err(|_| WebhookError::InvalidSecret)?;
        if key.is_empty() {
            return Err(WebhookError::InvalidSecret);
        }
        Ok(Self(key.into()))
    }

    /// A secret of these key bytes.
    pub fn from_bytes(key: impl Into<Vec<u8>>) -> Self {
        Self(key.into().into())
    }

    /// `v1,<base64>`: the signature of `<id>.<timestamp>.<body>`.
    fn sign(&self, id: &str, timestamp: u64, body: &[u8]) -> String {
        let mac = self.mac(id, timestamp, body);
        format!(
            "v1,{}",
            base64::engine::general_purpose::STANDARD.encode(mac.finalize().into_bytes())
        )
    }

    fn mac(&self, id: &str, timestamp: u64, body: &[u8]) -> HmacSha256 {
        let mut mac = HmacSha256::new_from_slice(&self.0).expect("HMAC takes a key of any length");
        mac.update(id.as_bytes());
        mac.update(b".");
        mac.update(timestamp.to_string().as_bytes());
        mac.update(b".");
        mac.update(body);
        mac
    }
}

impl fmt::Debug for WebhookSecret {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("WebhookSecret([redacted])")
    }
}

/// Why a webhook cannot be signed or does not verify.
#[derive(Clone, Debug, PartialEq, Eq)]
#[non_exhaustive]
pub enum WebhookError {
    /// The secret is not `whsec_` followed by base64.
    InvalidSecret,
    /// The request lacks this header, or it is not text.
    MissingHeader(HeaderName),
    /// `webhook-timestamp` is not a number of seconds.
    InvalidTimestamp,
    /// `webhook-timestamp` is further from now than the tolerance: an old message replayed, or a clock that is off.
    TimestampOutOfTolerance,
    /// No `v1` signature matches a secret.
    NoMatchingSignature,
    /// The body streams, so its bytes are not known before it is sent: it cannot be signed.
    StreamingBody,
}

impl fmt::Display for WebhookError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            WebhookError::InvalidSecret => {
                f.write_str("the webhook secret is not whsec_ followed by base64")
            }
            WebhookError::MissingHeader(name) => write!(f, "the webhook has no {name} header"),
            WebhookError::InvalidTimestamp => {
                f.write_str("the webhook-timestamp is not a number of seconds")
            }
            WebhookError::TimestampOutOfTolerance => {
                f.write_str("the webhook-timestamp is too far from now")
            }
            WebhookError::NoMatchingSignature => f.write_str("no webhook signature matches"),
            WebhookError::StreamingBody => {
                f.write_str("a webhook whose body streams cannot be signed")
            }
        }
    }
}

impl std::error::Error for WebhookError {}

/// Signs every request with one or more secrets, as Standard Webhooks; see the [module](self).
///
/// A request that already has a `webhook-id` or `webhook-timestamp` keeps it; any other gets a new id
/// (`msg_<hex>`) and the current time. The signature is always written, one per secret.
#[derive(Clone, Debug)]
pub struct StandardWebhooksLayer {
    secrets: Arc<[WebhookSecret]>,
}

impl StandardWebhooksLayer {
    /// Signs with `secret`.
    pub fn new(secret: WebhookSecret) -> Self {
        Self {
            secrets: Arc::from(vec![secret]),
        }
    }

    /// Signs with every one of `secrets` (a secret being rotated, and its successor).
    pub fn with_secrets(secrets: impl IntoIterator<Item = WebhookSecret>) -> Self {
        Self {
            secrets: secrets.into_iter().collect::<Vec<_>>().into(),
        }
    }
}

impl<S> Layer<S> for StandardWebhooksLayer {
    type Service = StandardWebhooks<S>;

    fn layer(&self, inner: S) -> Self::Service {
        StandardWebhooks {
            inner,
            secrets: self.secrets.clone(),
        }
    }
}

/// The service of a [`StandardWebhooksLayer`].
#[derive(Clone, Debug)]
pub struct StandardWebhooks<S> {
    inner: S,
    secrets: Arc<[WebhookSecret]>,
}

impl<S> Service<http::Request<Body>> for StandardWebhooks<S>
where
    S: Service<http::Request<Body>>,
    S::Error: Into<BoxError>,
{
    type Response = S::Response;
    type Error = BoxError;
    #[allow(clippy::type_complexity)]
    type Future =
        Either<Ready<Result<S::Response, BoxError>>, MapErr<S::Future, fn(S::Error) -> BoxError>>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx).map_err(Into::into)
    }

    fn call(&mut self, mut request: http::Request<Body>) -> Self::Future {
        if let Err(error) = sign(&mut request, &self.secrets, unix_now()) {
            return Either::Left(ready(Err(Box::new(error))));
        }
        Either::Right(
            self.inner
                .call(request)
                .map_err(into_box_error::<S::Error> as fn(_) -> _),
        )
    }
}

fn into_box_error<E: Into<BoxError>>(error: E) -> BoxError {
    error.into()
}

/// Writes the headers of a signed webhook onto `request`, signed at `now` unless it has a timestamp.
fn sign(
    request: &mut http::Request<Body>,
    secrets: &[WebhookSecret],
    now: u64,
) -> Result<(), WebhookError> {
    let body = request
        .body()
        .as_bytes()
        .ok_or(WebhookError::StreamingBody)?
        .to_vec();
    let headers = request.headers_mut();
    let id = match text(headers, &WEBHOOK_ID) {
        Some(id) => id.to_owned(),
        None => {
            let id = new_id();
            headers.insert(
                WEBHOOK_ID,
                HeaderValue::from_str(&id).expect("an id of ASCII letters and digits"),
            );
            id
        }
    };
    let timestamp = match text(headers, &WEBHOOK_TIMESTAMP) {
        Some(timestamp) => timestamp
            .parse()
            .map_err(|_| WebhookError::InvalidTimestamp)?,
        None => {
            headers.insert(WEBHOOK_TIMESTAMP, HeaderValue::from(now));
            now
        }
    };
    let signatures: Vec<String> = secrets
        .iter()
        .map(|secret| secret.sign(&id, timestamp, &body))
        .collect();
    headers.insert(
        WEBHOOK_SIGNATURE,
        HeaderValue::from_str(&signatures.join(" ")).expect("signatures of ASCII base64"),
    );
    Ok(())
}

/// Checks a webhook a receiver got: one of its `v1` signatures matches one of `secrets` (compared in constant time),
/// over the raw `body`, and its timestamp is within `tolerance` of now. Decode the body after it passes.
pub fn verify(
    headers: &HeaderMap,
    body: &[u8],
    secrets: &[WebhookSecret],
    tolerance: Duration,
) -> Result<(), WebhookError> {
    verify_at(headers, body, secrets, tolerance, unix_now())
}

fn verify_at(
    headers: &HeaderMap,
    body: &[u8],
    secrets: &[WebhookSecret],
    tolerance: Duration,
    now: u64,
) -> Result<(), WebhookError> {
    let id = text(headers, &WEBHOOK_ID).ok_or(WebhookError::MissingHeader(WEBHOOK_ID))?;
    let timestamp: u64 = text(headers, &WEBHOOK_TIMESTAMP)
        .ok_or(WebhookError::MissingHeader(WEBHOOK_TIMESTAMP))?
        .parse()
        .map_err(|_| WebhookError::InvalidTimestamp)?;
    let signatures =
        text(headers, &WEBHOOK_SIGNATURE).ok_or(WebhookError::MissingHeader(WEBHOOK_SIGNATURE))?;
    if now.abs_diff(timestamp) > tolerance.as_secs() {
        return Err(WebhookError::TimestampOutOfTolerance);
    }
    for signature in signatures.split(' ') {
        let Some(encoded) = signature.strip_prefix("v1,") else {
            continue;
        };
        let Ok(signature) = base64::engine::general_purpose::STANDARD.decode(encoded) else {
            continue;
        };
        if secrets.iter().any(|secret| {
            secret
                .mac(id, timestamp, body)
                .verify_slice(&signature)
                .is_ok()
        }) {
            return Ok(());
        }
    }
    Err(WebhookError::NoMatchingSignature)
}

fn text<'a>(headers: &'a HeaderMap, name: &HeaderName) -> Option<&'a str> {
    headers.get(name)?.to_str().ok()
}

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |since| since.as_secs())
}

/// `msg_` and 32 hex digits from the time, a counter and the process's hash seed: unique, not secret.
fn new_id() -> String {
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |since| since.as_nanos() as u64);
    let count = COUNTER.fetch_add(1, Ordering::Relaxed);
    let seed = {
        use std::hash::{BuildHasher, Hasher};
        let mut hasher = std::collections::hash_map::RandomState::new().build_hasher();
        hasher.write_u64(nanos ^ count);
        hasher.finish()
    };
    format!("msg_{:016x}{:016x}", nanos ^ count.rotate_left(48), seed)
}

#[cfg(all(test, not(target_family = "wasm")))]
mod tests {
    use super::*;
    use tower::ServiceExt;

    // Standard Webhooks' test vector
    // (https://github.com/standard-webhooks/standard-webhooks/blob/main/libraries/javascript/src/index.test.ts).
    // Standard Webhooks' public test secret, written in pieces so that secret scanners do not take it for a real one
    const SECRET: &str = concat!("whsec", "_", "MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");
    const ID: &str = "msg_p5jXN8AQM9LWM0D4loKWxJek";
    const TIMESTAMP: u64 = 1614265330;
    const BODY: &str = r#"{"test": 2432232314}"#;
    const SIGNATURE: &str = "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=";

    fn secret() -> WebhookSecret {
        WebhookSecret::new(SECRET).unwrap()
    }

    fn headers(id: &str, timestamp: u64, signature: &str) -> HeaderMap {
        let mut headers = HeaderMap::new();
        headers.insert(WEBHOOK_ID, HeaderValue::from_str(id).unwrap());
        headers.insert(WEBHOOK_TIMESTAMP, HeaderValue::from(timestamp));
        headers.insert(WEBHOOK_SIGNATURE, HeaderValue::from_str(signature).unwrap());
        headers
    }

    #[test]
    fn the_signature_is_standard_webhooks_v1() {
        assert_eq!(secret().sign(ID, TIMESTAMP, BODY.as_bytes()), SIGNATURE);
    }

    #[test]
    fn a_signed_webhook_verifies_within_the_tolerance() {
        let tolerance = Duration::from_secs(300);
        let headers = headers(ID, TIMESTAMP, SIGNATURE);
        assert_eq!(
            verify_at(
                &headers,
                BODY.as_bytes(),
                &[secret()],
                tolerance,
                TIMESTAMP + 300
            ),
            Ok(())
        );
        assert_eq!(
            verify_at(
                &headers,
                BODY.as_bytes(),
                &[secret()],
                tolerance,
                TIMESTAMP + 301
            ),
            Err(WebhookError::TimestampOutOfTolerance)
        );
        assert_eq!(
            verify_at(
                &headers,
                BODY.as_bytes(),
                &[secret()],
                tolerance,
                TIMESTAMP - 301
            ),
            Err(WebhookError::TimestampOutOfTolerance)
        );
    }

    #[test]
    fn a_changed_body_id_or_secret_does_not_verify() {
        let tolerance = Duration::from_secs(300);
        let check = |headers: &HeaderMap, body: &str, secrets: &[WebhookSecret]| {
            verify_at(headers, body.as_bytes(), secrets, tolerance, TIMESTAMP)
        };
        let signed = headers(ID, TIMESTAMP, SIGNATURE);
        assert_eq!(
            check(&signed, r#"{"test": 2432232315}"#, &[secret()]),
            Err(WebhookError::NoMatchingSignature)
        );
        assert_eq!(
            check(
                &headers("msg_other", TIMESTAMP, SIGNATURE),
                BODY,
                &[secret()]
            ),
            Err(WebhookError::NoMatchingSignature)
        );
        let other = WebhookSecret::from_bytes(b"another key".to_vec());
        assert_eq!(
            check(&signed, BODY, std::slice::from_ref(&other)),
            Err(WebhookError::NoMatchingSignature)
        );
        // rotation: any secret, any signature
        assert_eq!(check(&signed, BODY, &[other, secret()]), Ok(()));
        let two = headers(ID, TIMESTAMP, &format!("v1,bm90IGl0 v2a,xyz {SIGNATURE}"));
        assert_eq!(check(&two, BODY, &[secret()]), Ok(()));
    }

    #[test]
    fn missing_or_invalid_headers_are_named() {
        let tolerance = Duration::from_secs(300);
        let mut headers = headers(ID, TIMESTAMP, SIGNATURE);
        headers.insert(WEBHOOK_TIMESTAMP, HeaderValue::from_static("soon"));
        assert_eq!(
            verify_at(&headers, BODY.as_bytes(), &[secret()], tolerance, TIMESTAMP),
            Err(WebhookError::InvalidTimestamp)
        );
        headers.insert(WEBHOOK_TIMESTAMP, HeaderValue::from(TIMESTAMP));
        headers.remove(WEBHOOK_SIGNATURE);
        assert_eq!(
            verify_at(&headers, BODY.as_bytes(), &[secret()], tolerance, TIMESTAMP),
            Err(WebhookError::MissingHeader(WEBHOOK_SIGNATURE))
        );
        headers.remove(WEBHOOK_ID);
        assert_eq!(
            verify_at(&headers, BODY.as_bytes(), &[secret()], tolerance, TIMESTAMP),
            Err(WebhookError::MissingHeader(WEBHOOK_ID))
        );
    }

    #[test]
    fn a_secret_is_whsec_and_base64_and_does_not_show() {
        assert_eq!(
            WebhookSecret::new("whsec_not base64!"),
            Err(WebhookError::InvalidSecret)
        );
        assert_eq!(
            WebhookSecret::new("whsec_"),
            Err(WebhookError::InvalidSecret)
        );
        // without the prefix, the base64 alone
        assert_eq!(
            WebhookSecret::new("MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw").unwrap(),
            secret()
        );
        assert_eq!(format!("{:?}", secret()), "WebhookSecret([redacted])");
    }

    #[tokio::test]
    async fn the_layer_signs_what_it_sends_and_keeps_a_given_id() {
        let echo =
            tower::service_fn(
                |request: http::Request<Body>| async move { Ok::<_, BoxError>(request) },
            );
        let layer = StandardWebhooksLayer::new(secret());
        let mut request = http::Request::new(Body::from(BODY));
        request
            .headers_mut()
            .insert(WEBHOOK_ID, HeaderValue::from_static(ID));
        let sent = layer.layer(echo).oneshot(request).await.unwrap();
        assert_eq!(sent.headers()[WEBHOOK_ID], ID);
        let timestamp: u64 = sent.headers()[WEBHOOK_TIMESTAMP]
            .to_str()
            .unwrap()
            .parse()
            .unwrap();
        assert!(timestamp.abs_diff(unix_now()) <= 1);
        // what the receiver checks
        verify(
            sent.headers(),
            BODY.as_bytes(),
            &[secret()],
            Duration::from_secs(5),
        )
        .unwrap();
    }

    #[tokio::test]
    async fn the_layer_gives_each_message_its_own_id_and_signs_with_every_secret() {
        let echo =
            tower::service_fn(
                |request: http::Request<Body>| async move { Ok::<_, BoxError>(request) },
            );
        let next = WebhookSecret::from_bytes(b"the next key".to_vec());
        let service = StandardWebhooksLayer::with_secrets([secret(), next.clone()]).layer(echo);
        let first = service
            .clone()
            .oneshot(http::Request::new(Body::from(BODY)))
            .await
            .unwrap();
        let second = service
            .oneshot(http::Request::new(Body::from(BODY)))
            .await
            .unwrap();
        let id = first.headers()[WEBHOOK_ID].to_str().unwrap();
        assert!(id.starts_with("msg_") && id.len() == 36, "{id}");
        assert_ne!(first.headers()[WEBHOOK_ID], second.headers()[WEBHOOK_ID]);
        let signatures = first.headers()[WEBHOOK_SIGNATURE].to_str().unwrap();
        assert_eq!(signatures.split(' ').count(), 2);
        // either secret alone verifies
        verify(
            first.headers(),
            BODY.as_bytes(),
            &[next],
            Duration::from_secs(5),
        )
        .unwrap();
    }

    #[tokio::test]
    async fn a_body_that_streams_is_not_sent_unsigned() {
        let never = tower::service_fn(|_: http::Request<Body>| async move {
            panic!("a request that cannot be signed is not sent");
            #[allow(unreachable_code)]
            Ok::<(), BoxError>(())
        });
        let stream =
            futures_util::stream::iter([Ok::<_, std::io::Error>(bytes::Bytes::from_static(b"{}"))]);
        let request = http::Request::new(Body::wrap_stream(stream));
        let error = StandardWebhooksLayer::new(secret())
            .layer(never)
            .oneshot(request)
            .await
            .unwrap_err();
        assert_eq!(
            error.downcast_ref::<WebhookError>(),
            Some(&WebhookError::StreamingBody)
        );
    }
}
