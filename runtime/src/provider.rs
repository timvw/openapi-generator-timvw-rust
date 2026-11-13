//! Send a generated client to another provider of the same API: [`ProviderLayer`] moves each request to the provider's
//! base URL and adds the headers, query parameters and path changes the provider needs.
//!
//! The layer is provider-neutral: it knows no provider by name. A crate for one API (OpenAI-compatible providers, for
//! example) builds its presets on it.

use std::fmt;
use std::sync::Arc;
use std::task::{Context, Poll};

use futures_util::future::{self, Either, Ready};
use futures_util::TryFutureExt;
use http::header::{HeaderName, HeaderValue};
use http::{HeaderMap, Uri};
use serde::Serialize;
use tower::{Layer, Service};
use url::Url;

use crate::request_options::Applied;
use crate::{BoxError, Error};

/// Rewrites a path relative to the base URL, see [`ProviderLayer::map_path`].
type MapPath = Arc<dyn Fn(&str) -> String + Send + Sync>;

/// A tower layer that sends requests to a provider: another host (and path prefix) serving the same API, with its own
/// headers, query parameters and paths.
///
/// A request is the provider's when its URL starts with the provider's base URL, or with the base declared with
/// [`ProviderLayer::rewrite_from`], which is then replaced with the provider's: same scheme, host and port, and a path
/// that starts with the base's path at a segment boundary (`/v1` holds `/v1` and `/v1/models`, not `/v10`). To such a
/// request the layer, in this order:
///
/// 1. replaces the base (scheme, host, port and path prefix) with the provider's, keeping the rest of the path and the
///    query;
/// 2. rewrites the rest of the path with [`ProviderLayer::map_path`], if set;
/// 3. sets the [headers](ProviderLayer::header), each replacing every value the request has under its name;
/// 4. sets the [query parameters](ProviderLayer::query), each replacing the parameters of its name the request has.
///
/// Any other request (a download from another host, say) is passed on unchanged, so the provider's credentials are
/// never sent elsewhere.
///
/// Per-call [`RequestOptions`](crate::RequestOptions) win over the layer:
///
/// - a header the options set is not replaced by the layer's header of that name;
/// - a query parameter the options added is not replaced by the layer's parameter of that name (with an `api-version`
///   in both, the options' is sent, once);
/// - a call the options sent to a [base URL](crate::RequestOptions::base_url) of their own is not moved: the layer
///   ignores [`ProviderLayer::rewrite_from`] for it. When that base is the provider's (or under it), the layer still
///   rewrites the path and adds its headers and query parameters; any other base gets nothing from the layer, so its
///   credentials do not go there.
///
/// There are two ways to point a generated client at a provider:
///
/// - Set `Configuration.base_path` to the provider's base URL and add `ProviderLayer::new(provider_base)` for the
///   headers, query parameters and paths. The requests already start with the provider's base.
/// - Keep the generated `base_path` and declare it with `.rewrite_from(generated_base)`: the layer moves every request
///   under it to the provider. This suits a `Client` shared by several `Configuration`s, or a crate that hands out a
///   ready-made client for a provider.
///
/// ```
/// use timvw_rust_runtime::provider::ProviderLayer;
///
/// // An OpenAI-compatible provider under another base URL, with headers of its own.
/// let openrouter_like = ProviderLayer::new("https://openrouter.example/api/v1")?
///     .rewrite_from("https://api.openai.com/v1")?
///     .sensitive_header("authorization", "Bearer sk-or-123")?
///     .header("x-title", "my app")?;
///
/// assert_eq!(
///     openrouter_like.rewrite_uri(&"https://api.openai.com/v1/models?limit=2".parse()?)?.to_string(),
///     "https://openrouter.example/api/v1/models?limit=2"
/// );
///
/// // A provider that puts a deployment in the path and wants an `api-version` on every request.
/// let azure_like = ProviderLayer::new("https://my-resource.example.com/openai")?
///     .rewrite_from("https://api.openai.com/v1")?
///     .sensitive_header("api-key", "secret")?
///     .query(&[("api-version", "2024-10-21")])?
///     .map_path(|path| format!("/deployments/my-deployment{path}"));
///
/// assert_eq!(
///     azure_like.rewrite_uri(&"https://api.openai.com/v1/chat/completions".parse()?)?.to_string(),
///     "https://my-resource.example.com/openai/deployments/my-deployment/chat/completions?api-version=2024-10-21"
/// );
/// # Ok::<(), Box<dyn std::error::Error>>(())
/// ```
///
/// Add it with [`Client::layer`](crate::Client::layer), to `Client::default()` or to a generated `Configuration.client`.
/// Added last, it sees each request first and rewrites it once, before the retries.
#[derive(Clone)]
pub struct ProviderLayer {
    base: Url,
    rewrite_from: Option<Url>,
    headers: HeaderMap,
    query: Vec<(String, String)>,
    map_path: Option<MapPath>,
}

impl ProviderLayer {
    /// A provider whose API is at `base_url`, such as `https://openrouter.ai/api/v1`. Fails, with an error whose
    /// [`is_builder()`](Error::is_builder) is true, on a URL that is not absolute or that has a query or fragment.
    pub fn new(base_url: &str) -> Result<Self, Error> {
        Ok(Self {
            base: parse_base(base_url)?,
            rewrite_from: None,
            headers: HeaderMap::new(),
            query: Vec::new(),
            map_path: None,
        })
    }

    /// Moves the requests under `base_url`, typically a generated client's default `base_path` such as
    /// `https://api.openai.com/v1`, to the provider: `base_url` is replaced with the provider's base URL, and the rest
    /// of the path and the query are kept. Fails like [`ProviderLayer::new`]. Calling it again replaces the base.
    pub fn rewrite_from(mut self, base_url: &str) -> Result<Self, Error> {
        self.rewrite_from = Some(parse_base(base_url)?);
        Ok(self)
    }

    /// Sets a header on every request to the provider, replacing every value the request has under that name (the
    /// generated client's included). Calling it again for the same name replaces the value. Fails, with an error
    /// whose [`is_builder()`](Error::is_builder) is true, on an invalid name or value.
    pub fn header<K, V>(mut self, name: K, value: V) -> Result<Self, Error>
    where
        HeaderName: TryFrom<K>,
        <HeaderName as TryFrom<K>>::Error: Into<http::Error>,
        HeaderValue: TryFrom<V>,
        <HeaderValue as TryFrom<V>>::Error: Into<http::Error>,
    {
        let (name, value) = header(name, value)?;
        self.headers.insert(name, value);
        Ok(self)
    }

    /// Like [`ProviderLayer::header`], and marks the value sensitive (see [`HeaderValue::set_sensitive`]): for an API
    /// key, so that it is left out of `Debug` output.
    pub fn sensitive_header<K, V>(mut self, name: K, value: V) -> Result<Self, Error>
    where
        HeaderName: TryFrom<K>,
        <HeaderName as TryFrom<K>>::Error: Into<http::Error>,
        HeaderValue: TryFrom<V>,
        <HeaderValue as TryFrom<V>>::Error: Into<http::Error>,
    {
        let (name, mut value) = header(name, value)?;
        value.set_sensitive(true);
        self.headers.insert(name, value);
        Ok(self)
    }

    /// Sets every header of a map, replacing the values set before under its names. A name with several values keeps
    /// them all. Values marked sensitive stay sensitive.
    pub fn headers(mut self, headers: HeaderMap) -> Self {
        self.headers.extend(headers);
        self
    }

    /// Sets query parameters on every request to the provider, such as `api-version`, serialized as
    /// `application/x-www-form-urlencoded`: a sequence of pairs, a map or a struct. Each replaces the parameters of its
    /// name the request has; a name given twice here is sent twice. Fails, with an error whose
    /// [`is_builder()`](Error::is_builder) is true, on a value that is not a flat list of pairs.
    pub fn query<T: Serialize + ?Sized>(mut self, query: &T) -> Result<Self, Error> {
        let encoded = serde_urlencoded::to_string(query).map_err(Error::builder)?;
        let pairs: Vec<(String, String)> = url::form_urlencoded::parse(encoded.as_bytes())
            .into_owned()
            .collect();
        self.query
            .retain(|(name, _)| !pairs.iter().any(|(new, _)| new == name));
        self.query.extend(pairs);
        Ok(self)
    }

    /// Rewrites the path of each request to the provider, relative to the base URL: `f` gets the path after the base
    /// (`/chat/completions` for `https://api.openai.com/v1/chat/completions` with base `https://api.openai.com/v1`;
    /// empty for the base itself), percent-encoded, and returns the new one, which is put after the provider's base.
    pub fn map_path<F>(mut self, f: F) -> Self
    where
        F: Fn(&str) -> String + Send + Sync + 'static,
    {
        self.map_path = Some(Arc::new(f));
        self
    }

    /// The provider's base URL.
    pub fn base_url(&self) -> &str {
        self.base.as_str()
    }

    /// The headers set on each request to the provider.
    pub fn header_map(&self) -> &HeaderMap {
        &self.headers
    }

    /// The query parameters set on each request to the provider, decoded, in order.
    pub fn query_pairs(&self) -> &[(String, String)] {
        &self.query
    }

    /// The URL a request to `uri` is sent to: moved to the provider, its path rewritten and its query parameters set,
    /// or `uri` unchanged when it is not the provider's. Fails, with an error whose
    /// [`is_builder()`](Error::is_builder) is true, when the rewritten URL is invalid.
    pub fn rewrite_uri(&self, uri: &Uri) -> Result<Uri, Error> {
        Ok(self.rewrite(uri, None)?.unwrap_or_else(|| uri.clone()))
    }

    /// The rewritten URL, or `None` when the request is not the provider's. `applied` is what per-call options set.
    fn rewrite(&self, uri: &Uri, applied: Option<&Applied>) -> Result<Option<Uri>, Error> {
        let Ok(mut url) = Url::parse(&uri.to_string()) else {
            return Ok(None);
        };
        // A call the options sent to a base of their own is not moved.
        let rewrite_from = self
            .rewrite_from
            .as_ref()
            .filter(|_| !applied.is_some_and(|applied| applied.base));
        let rest = rewrite_from
            .into_iter()
            .chain([&self.base])
            .find_map(|base| rest_of_path(&url, base));
        let Some(rest) = rest else {
            return Ok(None);
        };
        let rest = match &self.map_path {
            Some(map_path) => map_path(&rest),
            None => rest,
        };
        let mut path = trimmed_path(&self.base).to_owned();
        path.push_str(&rest);
        // Scheme, host and port of the provider; the scheme is http(s) on both sides, so these do not fail.
        let _ = url.set_scheme(self.base.scheme());
        url.set_host(self.base.host_str()).map_err(Error::builder)?;
        let _ = url.set_port(self.base.port());
        url.set_path(if path.is_empty() { "/" } else { &path });
        self.set_query(&mut url, applied);
        url.as_str()
            .parse::<Uri>()
            .map(Some)
            .map_err(Error::builder)
    }

    fn set_query(&self, url: &mut Url, applied: Option<&Applied>) {
        // The options' parameters win: the layer leaves their names alone.
        let query: Vec<&(String, String)> = self
            .query
            .iter()
            .filter(|(name, _)| !applied.is_some_and(|applied| applied.has_query(name)))
            .collect();
        if query.is_empty() {
            return;
        }
        let replaced = url
            .query_pairs()
            .any(|(name, _)| query.iter().any(|(new, _)| *new == name));
        if replaced {
            // Re-encode the query without the replaced names.
            let kept: Vec<(String, String)> = url
                .query_pairs()
                .filter(|(name, _)| !query.iter().any(|(new, _)| new == name))
                .map(|(name, value)| (name.into_owned(), value.into_owned()))
                .collect();
            url.set_query(None);
            url.query_pairs_mut().extend_pairs(kept);
        }
        url.query_pairs_mut()
            .extend_pairs(query.iter().map(|(name, value)| (name, value)));
    }
}

impl fmt::Debug for ProviderLayer {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ProviderLayer")
            .field("base", &self.base.as_str())
            .field("rewrite_from", &self.rewrite_from.as_ref().map(Url::as_str))
            .field("headers", &self.headers)
            .field("query", &self.query)
            .field("map_path", &self.map_path.is_some())
            .finish()
    }
}

impl<S> Layer<S> for ProviderLayer {
    type Service = Provider<S>;

    fn layer(&self, inner: S) -> Self::Service {
        Provider {
            inner,
            provider: Arc::new(self.clone()),
        }
    }
}

/// The service of a [`ProviderLayer`].
#[derive(Clone)]
pub struct Provider<S> {
    inner: S,
    provider: Arc<ProviderLayer>,
}

impl<S: fmt::Debug> fmt::Debug for Provider<S> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Provider")
            .field("inner", &self.inner)
            .field("provider", &self.provider)
            .finish()
    }
}

impl<S, B> Service<http::Request<B>> for Provider<S>
where
    S: Service<http::Request<B>>,
    S::Error: Into<BoxError>,
{
    type Response = S::Response;
    type Error = BoxError;
    #[allow(clippy::type_complexity)]
    type Future = Either<
        Ready<Result<S::Response, BoxError>>,
        futures_util::future::MapErr<S::Future, fn(S::Error) -> BoxError>,
    >;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx).map_err(Into::into)
    }

    fn call(&mut self, mut request: http::Request<B>) -> Self::Future {
        let applied = request.extensions().get::<Applied>().cloned();
        match self.provider.rewrite(request.uri(), applied.as_ref()) {
            Err(error) => Either::Left(future::ready(Err(error.into()))),
            Ok(None) => Either::Right(
                self.inner
                    .call(request)
                    .map_err(into_box_error::<S::Error> as fn(_) -> _),
            ),
            Ok(Some(uri)) => {
                *request.uri_mut() = uri;
                match &applied {
                    None => request.headers_mut().extend(self.provider.headers.clone()),
                    Some(applied) => {
                        // The options' headers win: the layer leaves their names alone.
                        let mut kept = HeaderMap::new();
                        for (name, value) in &self.provider.headers {
                            if !applied.has_header(name) {
                                kept.append(name.clone(), value.clone());
                            }
                        }
                        request.headers_mut().extend(kept);
                    }
                }
                Either::Right(
                    self.inner
                        .call(request)
                        .map_err(into_box_error::<S::Error> as fn(_) -> _),
                )
            }
        }
    }
}

fn into_box_error<E: Into<BoxError>>(error: E) -> BoxError {
    error.into()
}

fn header<K, V>(name: K, value: V) -> Result<(HeaderName, HeaderValue), Error>
where
    HeaderName: TryFrom<K>,
    <HeaderName as TryFrom<K>>::Error: Into<http::Error>,
    HeaderValue: TryFrom<V>,
    <HeaderValue as TryFrom<V>>::Error: Into<http::Error>,
{
    let name = HeaderName::try_from(name).map_err(|e| Error::builder(e.into()))?;
    let value = HeaderValue::try_from(value).map_err(|e| Error::builder(e.into()))?;
    Ok((name, value))
}

/// A base URL: absolute, with a host, without a query or fragment.
pub(crate) fn parse_base(base_url: &str) -> Result<Url, Error> {
    let url = Url::parse(base_url).map_err(Error::builder)?;
    if url.cannot_be_a_base() || url.host_str().is_none() {
        return Err(Error::builder(format!(
            "not a base URL with a host: {base_url}"
        )));
    }
    if url.query().is_some() || url.fragment().is_some() {
        return Err(Error::builder(format!(
            "a base URL has no query or fragment (set query parameters with `query`): {base_url}"
        )));
    }
    Ok(url)
}

/// The path of a base URL without its trailing `/`: empty for the root.
pub(crate) fn trimmed_path(base: &Url) -> &str {
    base.path().trim_end_matches('/')
}

/// The path of `url` after `base`'s, when `url` is under `base`: the same scheme, host and port, and a path that starts
/// with `base`'s at a segment boundary.
pub(crate) fn rest_of_path(url: &Url, base: &Url) -> Option<String> {
    if url.scheme() != base.scheme()
        || url.host_str() != base.host_str()
        || url.port_or_known_default() != base.port_or_known_default()
    {
        return None;
    }
    let rest = url.path().strip_prefix(trimmed_path(base))?;
    (rest.is_empty() || rest.starts_with('/')).then(|| rest.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rewrite(layer: &ProviderLayer, uri: &str) -> String {
        layer
            .rewrite_uri(&uri.parse().unwrap())
            .unwrap()
            .to_string()
    }

    #[test]
    fn moves_requests_under_the_declared_base() {
        let layer = ProviderLayer::new("https://openrouter.ai/api/v1/")
            .unwrap()
            .rewrite_from("https://api.openai.com/v1")
            .unwrap();
        assert_eq!(
            rewrite(&layer, "https://api.openai.com/v1/chat/completions"),
            "https://openrouter.ai/api/v1/chat/completions"
        );
        assert_eq!(
            rewrite(
                &layer,
                "https://api.openai.com:443/v1/models?limit=2&after=a%20b"
            ),
            "https://openrouter.ai/api/v1/models?limit=2&after=a%20b"
        );
        assert_eq!(
            rewrite(&layer, "https://api.openai.com/v1"),
            "https://openrouter.ai/api/v1"
        );
        // Not under the base: unchanged.
        for uri in [
            "https://api.openai.com/v10/models",
            "https://api.openai.com/models",
            "http://api.openai.com/v1/models",
            "https://api.openai.com:8443/v1/models",
            "https://files.example.com/v1/download",
        ] {
            assert_eq!(rewrite(&layer, uri), uri);
        }
    }

    #[test]
    fn a_request_already_at_the_provider_gets_its_path_mapped_once() {
        let layer = ProviderLayer::new("http://localhost:11434/v1")
            .unwrap()
            .map_path(|path| format!("/x{path}"));
        assert_eq!(
            rewrite(&layer, "http://localhost:11434/v1/models"),
            "http://localhost:11434/v1/x/models"
        );
        assert_eq!(
            rewrite(&layer, "http://localhost:11435/v1/models"),
            "http://localhost:11435/v1/models"
        );
    }

    #[test]
    fn root_bases() {
        let layer = ProviderLayer::new("https://proxy.example")
            .unwrap()
            .rewrite_from("https://api.example.com/")
            .unwrap();
        assert_eq!(
            rewrite(&layer, "https://api.example.com/items?a=1"),
            "https://proxy.example/items?a=1"
        );
        assert_eq!(
            rewrite(&layer, "https://api.example.com"),
            "https://proxy.example/"
        );
        let layer = ProviderLayer::new("https://proxy.example/prefix")
            .unwrap()
            .rewrite_from("https://api.example.com")
            .unwrap();
        assert_eq!(
            rewrite(&layer, "https://api.example.com/items"),
            "https://proxy.example/prefix/items"
        );
    }

    #[test]
    fn query_parameters_replace_those_of_their_name() {
        let layer = ProviderLayer::new("https://p.example/openai")
            .unwrap()
            .query(&[("api-version", "old")])
            .unwrap()
            .query(&[("api-version", "2024-10-21"), ("tag", "a"), ("tag", "b")])
            .unwrap();
        assert_eq!(
            layer.query_pairs(),
            [
                ("api-version".to_owned(), "2024-10-21".to_owned()),
                ("tag".to_owned(), "a".to_owned()),
                ("tag".to_owned(), "b".to_owned()),
            ]
        );
        assert_eq!(
            rewrite(&layer, "https://p.example/openai/models"),
            "https://p.example/openai/models?api-version=2024-10-21&tag=a&tag=b"
        );
        // Existing ones kept as sent, unless replaced.
        assert_eq!(
            rewrite(&layer, "https://p.example/openai/models?limit=2&q=a%20b"),
            "https://p.example/openai/models?limit=2&q=a%20b&api-version=2024-10-21&tag=a&tag=b"
        );
        assert_eq!(
            rewrite(
                &layer,
                "https://p.example/openai/models?api-version=x&limit=2&tag=z"
            ),
            "https://p.example/openai/models?limit=2&api-version=2024-10-21&tag=a&tag=b"
        );
    }

    #[test]
    fn invalid_input_is_a_builder_error() {
        for base in [
            "not a url",
            "mailto:someone@example.com",
            "https://p.example/v1?a=1",
            "https://p.example/v1#top",
        ] {
            assert!(ProviderLayer::new(base).unwrap_err().is_builder(), "{base}");
        }
        let layer = ProviderLayer::new("https://p.example").unwrap();
        assert!(layer.clone().header("x bad", "v").unwrap_err().is_builder());
        assert!(layer
            .clone()
            .sensitive_header("x-key", "line\nbreak")
            .unwrap_err()
            .is_builder());
        assert!(layer.query(&[("a", [1, 2])]).unwrap_err().is_builder());
    }

    #[test]
    fn debug_hides_sensitive_headers() {
        let layer = ProviderLayer::new("https://p.example")
            .unwrap()
            .sensitive_header("api-key", "secret-123")
            .unwrap()
            .header("x-title", "app")
            .unwrap();
        let debug = format!("{layer:?}");
        assert!(!debug.contains("secret-123"), "{debug}");
        assert!(debug.contains("app"), "{debug}");
        assert!(layer.header_map()["api-key"].is_sensitive());
        assert_eq!(layer.base_url(), "https://p.example/");
    }
}
