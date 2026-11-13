use std::fmt;

use base64::Engine;
use http::header::{HeaderName, HeaderValue, AUTHORIZATION, CONTENT_LENGTH, CONTENT_TYPE};
use http::HeaderMap;
use serde::Serialize;
use url::Url;

use crate::multipart::Form;
use crate::provider::{rest_of_path, trimmed_path};
use crate::request_options::Applied;
use crate::{Body, Client, Error, Method, RequestOptions, Response};

/// Builds a request, with the methods of reqwest's `RequestBuilder` that generated clients use.
///
/// An error in any step (an invalid URL, header or body) is kept and returned by [`RequestBuilder::build`].
pub struct RequestBuilder {
    client: Client,
    request: Result<Parts, Error>,
}

struct Parts {
    method: Method,
    url: Url,
    headers: HeaderMap,
    body: Option<Body>,
    /// The base the URL was built on ([`RequestBuilder::base`]).
    base: Option<Url>,
    /// What [`RequestBuilder::options`] set.
    applied: Option<Applied>,
}

impl RequestBuilder {
    pub(crate) fn new(client: Client, method: Method, url: &str) -> Self {
        let request = Url::parse(url)
            .map(|url| Parts {
                method,
                url,
                headers: HeaderMap::new(),
                body: None,
                base: None,
                applied: None,
            })
            .map_err(Error::builder);
        Self { client, request }
    }

    fn with(mut self, f: impl FnOnce(&mut Parts) -> Result<(), Error>) -> Self {
        if let Ok(parts) = &mut self.request {
            if let Err(error) = f(parts) {
                self.request = Err(error);
            }
        }
        self
    }

    /// Adds a header.
    pub fn header<K, V>(self, name: K, value: V) -> Self
    where
        HeaderName: TryFrom<K>,
        <HeaderName as TryFrom<K>>::Error: Into<http::Error>,
        HeaderValue: TryFrom<V>,
        <HeaderValue as TryFrom<V>>::Error: Into<http::Error>,
    {
        self.with(|parts| {
            let name = HeaderName::try_from(name).map_err(|e| Error::builder(e.into()))?;
            let value = HeaderValue::try_from(value).map_err(|e| Error::builder(e.into()))?;
            parts.headers.append(name, value);
            Ok(())
        })
    }

    /// Adds every header of a map.
    pub fn headers(self, headers: HeaderMap) -> Self {
        self.with(|parts| {
            parts.headers.extend(headers);
            Ok(())
        })
    }

    /// Appends query parameters, serialized as `application/x-www-form-urlencoded`: a sequence of pairs, a map or a
    /// struct.
    pub fn query<T: Serialize + ?Sized>(self, query: &T) -> Self {
        self.with(|parts| {
            {
                let mut pairs = parts.url.query_pairs_mut();
                query
                    .serialize(serde_urlencoded::Serializer::new(&mut pairs))
                    .map_err(Error::builder)?;
            }
            if parts.url.query() == Some("") {
                parts.url.set_query(None);
            }
            Ok(())
        })
    }

    /// Sets an `Authorization: Bearer` header.
    pub fn bearer_auth<T: fmt::Display>(self, token: T) -> Self {
        self.sensitive_header(AUTHORIZATION, format!("Bearer {token}"))
    }

    /// Sets an `Authorization: Basic` header.
    pub fn basic_auth<U: fmt::Display, P: fmt::Display>(
        self,
        username: U,
        password: Option<P>,
    ) -> Self {
        let credentials = match password {
            Some(password) => format!("{username}:{password}"),
            None => format!("{username}:"),
        };
        let encoded = base64::engine::general_purpose::STANDARD.encode(credentials);
        self.sensitive_header(AUTHORIZATION, format!("Basic {encoded}"))
    }

    /// Sets a header, replacing any earlier value, and marks it sensitive (see [`HeaderValue::set_sensitive`]): for
    /// credentials such as an API key.
    pub fn sensitive_header<K, V>(self, name: K, value: V) -> Self
    where
        HeaderName: TryFrom<K>,
        <HeaderName as TryFrom<K>>::Error: Into<http::Error>,
        HeaderValue: TryFrom<V>,
        <HeaderValue as TryFrom<V>>::Error: Into<http::Error>,
    {
        self.with(|parts| {
            let name = HeaderName::try_from(name).map_err(|e| Error::builder(e.into()))?;
            let mut value = HeaderValue::try_from(value).map_err(|e| Error::builder(e.into()))?;
            value.set_sensitive(true);
            parts.headers.insert(name, value);
            Ok(())
        })
    }

    /// Sets the body: bytes, a [`Body`], or an [`Upload`](crate::Upload) (a file, say, streamed and sent again by a
    /// retry).
    pub fn body<T: Into<Body>>(self, body: T) -> Self {
        self.with(|parts| {
            parts.body = Some(body.into());
            Ok(())
        })
    }

    /// Sets a JSON body, and `Content-Type: application/json` unless a content type is set.
    pub fn json<T: Serialize + ?Sized>(self, json: &T) -> Self {
        self.with(|parts| {
            let bytes = serde_json::to_vec(json).map_err(Error::builder)?;
            parts
                .headers
                .entry(CONTENT_TYPE)
                .or_insert(HeaderValue::from_static("application/json"));
            parts.body = Some(bytes.into());
            Ok(())
        })
    }

    /// Sets a form body (`application/x-www-form-urlencoded`).
    pub fn form<T: Serialize + ?Sized>(self, form: &T) -> Self {
        self.with(|parts| {
            let text = serde_urlencoded::to_string(form).map_err(Error::builder)?;
            parts.headers.insert(
                CONTENT_TYPE,
                HeaderValue::from_static("application/x-www-form-urlencoded"),
            );
            parts.body = Some(text.into());
            Ok(())
        })
    }

    /// Sets a `multipart/form-data` body, with a `Content-Length` when the length of every part is known.
    pub fn multipart(self, form: Form) -> Self {
        self.with(|parts| {
            let content_type =
                HeaderValue::try_from(format!("multipart/form-data; boundary={}", form.boundary()))
                    .map_err(Error::builder)?;
            parts.headers.insert(CONTENT_TYPE, content_type);
            let body = form.into_body();
            if let Some(len) = http_body::Body::size_hint(&body).exact() {
                parts.headers.insert(CONTENT_LENGTH, HeaderValue::from(len));
            }
            parts.body = Some(body);
            Ok(())
        })
    }

    /// Declares the base URL the request's URL was built on: a generated client's `Configuration.base_path`. A
    /// [`RequestOptions::base_url`] replaces it. A base the URL is not under is ignored.
    pub fn base(self, base_url: &str) -> Self {
        self.with(|parts| {
            parts.base = Url::parse(base_url).ok();
            Ok(())
        })
    }

    /// Applies [`RequestOptions`]: each of its headers replaces every value the request has under that name (call it
    /// last to override what was set before, as generated clients do), its query parameters are appended, and its
    /// [base URL](RequestOptions::base_url) replaces the request's [base](RequestBuilder::base), keeping the rest of
    /// the path and the query. A [`ProviderLayer`](crate::ProviderLayer) leaves what the options set alone.
    pub fn options(self, options: &RequestOptions) -> Self {
        let query = options.query_pairs();
        let this = if query.is_empty() {
            self
        } else {
            self.query(query)
        };
        this.with(|parts| {
            if let Some(to) = options.base_url_parsed() {
                rebase(&mut parts.url, parts.base.as_ref(), to)?;
                parts.base = Some(to.clone());
            }
            parts.headers.extend(options.header_map().clone());
            if !options.is_empty() {
                parts
                    .applied
                    .get_or_insert_with(Applied::default)
                    .add(options);
            }
            Ok(())
        })
    }

    /// The request, or the first error of the steps that built it.
    pub fn build(self) -> Result<http::Request<Body>, Error> {
        let parts = self.request?;
        let mut request = http::Request::builder()
            .method(parts.method)
            .uri(parts.url.as_str())
            .body(parts.body.unwrap_or_default())
            .map_err(Error::builder)?;
        *request.headers_mut() = parts.headers;
        if let Some(applied) = parts.applied {
            request.extensions_mut().insert(applied);
        }
        Ok(request)
    }

    /// Builds the request and sends it with the client that started it.
    pub async fn send(self) -> Result<Response, Error> {
        let client = self.client.clone();
        client.execute(self.build()?).await
    }
}

/// Moves `url` from the base `from` (its origin when `from` is unknown or `url` is not under it) to `to`, keeping the
/// rest of the path and the query.
fn rebase(url: &mut Url, from: Option<&Url>, to: &Url) -> Result<(), Error> {
    let rest = from
        .and_then(|from| rest_of_path(url, from))
        .unwrap_or_else(|| url.path().to_owned());
    let mut path = trimmed_path(to).to_owned();
    path.push_str(&rest);
    url.set_scheme(to.scheme())
        .map_err(|()| Error::builder(format!("cannot move {url} to {to}")))?;
    url.set_host(to.host_str()).map_err(Error::builder)?;
    url.set_port(to.port())
        .map_err(|()| Error::builder(format!("cannot move {url} to {to}")))?;
    url.set_path(if path.is_empty() { "/" } else { &path });
    Ok(())
}

impl fmt::Debug for RequestBuilder {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match &self.request {
            Ok(parts) => f
                .debug_struct("RequestBuilder")
                .field("method", &parts.method)
                .field("url", &parts.url.as_str())
                .field("headers", &parts.headers)
                .finish_non_exhaustive(),
            Err(error) => f
                .debug_struct("RequestBuilder")
                .field("error", error)
                .finish(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn builder(url: &str) -> RequestBuilder {
        Client::default().request(Method::GET, url)
    }

    #[test]
    fn query_pairs_are_appended_and_encoded() {
        let request = builder("https://example.com/items?a=1")
            .query(&[("b", "x y"), ("c", "&")])
            .query(&[("d", 2)])
            .build()
            .unwrap();
        assert_eq!(
            request.uri(),
            "https://example.com/items?a=1&b=x+y&c=%26&d=2"
        );
    }

    #[test]
    fn an_empty_query_leaves_no_question_mark() {
        let empty: [(&str, &str); 0] = [];
        let request = builder("https://example.com/items")
            .query(&empty)
            .build()
            .unwrap();
        assert_eq!(request.uri(), "https://example.com/items");
    }

    #[test]
    fn json_sets_content_type_and_body() {
        let request = builder("https://example.com")
            .json(&serde_json::json!({"a": 1}))
            .build()
            .unwrap();
        assert_eq!(request.headers()[CONTENT_TYPE], "application/json");
        assert_eq!(request.body().as_bytes(), Some(&br#"{"a":1}"#[..]));
    }

    #[test]
    fn form_sets_content_type_and_body() {
        let request = builder("https://example.com")
            .form(&[("a", "1"), ("b", "x y")])
            .build()
            .unwrap();
        assert_eq!(
            request.headers()[CONTENT_TYPE],
            "application/x-www-form-urlencoded"
        );
        assert_eq!(request.body().as_bytes(), Some(&b"a=1&b=x+y"[..]));
    }

    #[test]
    fn auth_headers_are_sensitive() {
        let bearer = builder("https://example.com")
            .bearer_auth("secret")
            .build()
            .unwrap();
        assert_eq!(bearer.headers()[AUTHORIZATION], "Bearer secret");
        assert!(bearer.headers()[AUTHORIZATION].is_sensitive());
        let basic = builder("https://example.com")
            .basic_auth("user", Some("pass"))
            .build()
            .unwrap();
        assert_eq!(basic.headers()[AUTHORIZATION], "Basic dXNlcjpwYXNz");
        assert!(basic.headers()[AUTHORIZATION].is_sensitive());
    }

    #[test]
    fn secrets_are_exposed_only_into_sensitive_headers() {
        use crate::{ExposeSecret, SecretString};
        let key = SecretString::from("sk-123");
        assert!(!format!("{key:?}").contains("sk-123"));
        let request = builder("https://example.com")
            .header("x-api-key", "old")
            .sensitive_header("x-api-key", key.expose_secret())
            .bearer_auth(key.expose_secret())
            .build()
            .unwrap();
        let values: Vec<_> = request.headers().get_all("x-api-key").iter().collect();
        assert_eq!(values, ["sk-123"]);
        assert!(values[0].is_sensitive());
        assert!(!format!("{:?}", request.headers()).contains("sk-123"));
        assert_eq!(request.headers()[AUTHORIZATION], "Bearer sk-123");
    }

    #[test]
    fn options_override_headers_and_append_query() {
        let mut tags = HeaderMap::new();
        tags.append("x-tag", HeaderValue::from_static("a"));
        tags.append("x-tag", HeaderValue::from_static("b"));
        let options = RequestOptions::new()
            .header("x-generated", "from-options")
            .unwrap()
            .header(AUTHORIZATION, "Bearer other")
            .unwrap()
            .headers(tags)
            .query(&[("b", "2"), ("a", "3")])
            .unwrap();
        let request = builder("https://example.com/items?a=1")
            .header("x-generated", "one")
            .header("x-generated", "two")
            .header("x-kept", "kept")
            .header("x-tag", "generated")
            .bearer_auth("secret")
            .options(&options)
            .build()
            .unwrap();
        let headers = request.headers();
        assert_eq!(
            headers.get_all("x-generated").iter().collect::<Vec<_>>(),
            ["from-options"]
        );
        assert_eq!(headers["x-kept"], "kept");
        assert_eq!(
            headers.get_all("x-tag").iter().collect::<Vec<_>>(),
            ["a", "b"]
        );
        assert_eq!(headers[AUTHORIZATION], "Bearer other");
        assert_eq!(request.uri(), "https://example.com/items?a=1&b=2&a=3");
    }

    #[test]
    fn empty_options_change_nothing() {
        let request = builder("https://example.com/items")
            .header("x-kept", "kept")
            .options(&RequestOptions::new())
            .build()
            .unwrap();
        assert_eq!(request.uri(), "https://example.com/items");
        assert_eq!(request.headers().len(), 1);
    }

    #[test]
    fn a_base_url_replaces_the_declared_base() {
        let options = RequestOptions::new()
            .base_url("http://localhost:8080/proxy/")
            .unwrap();
        let request = builder("https://api.example.com/v1/items/a%20b?limit=2")
            .base("https://api.example.com/v1")
            .options(&options)
            .build()
            .unwrap();
        assert_eq!(
            request.uri(),
            "http://localhost:8080/proxy/items/a%20b?limit=2"
        );
        // The base itself, and a root base.
        let request = builder("https://api.example.com/v1")
            .base("https://api.example.com/v1/")
            .options(
                &RequestOptions::new()
                    .base_url("https://eu.example.com")
                    .unwrap(),
            )
            .build()
            .unwrap();
        assert_eq!(request.uri(), "https://eu.example.com/");
        let request = builder("https://api.example.com/items")
            .base("https://api.example.com")
            .options(
                &RequestOptions::new()
                    .base_url("https://eu.example.com/v2")
                    .unwrap(),
            )
            .build()
            .unwrap();
        assert_eq!(request.uri(), "https://eu.example.com/v2/items");
    }

    #[test]
    fn without_a_declared_base_the_whole_path_is_kept() {
        let options = RequestOptions::new()
            .base_url("https://eu.example.com/v2")
            .unwrap()
            .query(&[("trace", "1")])
            .unwrap();
        for request in [
            builder("https://api.example.com/v1/items?a=1"),
            // a base the URL is not under
            builder("https://api.example.com/v1/items?a=1").base("https://other.example/v1"),
        ] {
            let request = request.options(&options).build().unwrap();
            assert_eq!(
                request.uri(),
                "https://eu.example.com/v2/v1/items?a=1&trace=1"
            );
        }
    }

    #[test]
    fn options_are_recorded_for_the_provider_layer() {
        let request = builder("https://example.com/items")
            .options(&RequestOptions::new().header("x-a", "1").unwrap())
            .options(
                &RequestOptions::new()
                    .query(&[("q", "1")])
                    .unwrap()
                    .base_url("https://other.example")
                    .unwrap(),
            )
            .build()
            .unwrap();
        let applied = request.extensions().get::<Applied>().unwrap();
        assert!(applied.has_header(&HeaderName::from_static("x-a")));
        assert!(applied.has_query("q"));
        assert!(applied.base);
        let plain = builder("https://example.com/items")
            .options(&RequestOptions::new())
            .build()
            .unwrap();
        assert!(plain.extensions().get::<Applied>().is_none());
    }

    #[test]
    fn errors_surface_at_build() {
        assert!(builder("not a url").build().unwrap_err().is_builder());
        let invalid = builder("https://example.com")
            .header("x-bad", "line\nbreak")
            .build();
        assert!(invalid.unwrap_err().is_builder());
    }
}
