use http::header::{HeaderName, HeaderValue};
use http::HeaderMap;
use serde::Serialize;

use crate::Error;

/// Extra headers and query parameters for one call, applied after everything the generated code sets.
///
/// A generated operation takes them with `.options(&options)`; [`RequestBuilder::options`](crate::RequestBuilder::options)
/// applies them to a request of your own. One value can be reused for many calls.
///
/// - A header replaces every value the request has under the same name: generated headers, authentication and
///   `Content-Type` included. A name set twice on the options keeps both values (see [`RequestOptions::headers`]).
/// - Query parameters are appended after the generated ones; a name the request already has is sent twice.
///
/// ```
/// use timvw_rust_runtime::RequestOptions;
///
/// let options = RequestOptions::new()
///     .header("x-request-source", "batch")?
///     .query(&[("trace", "1")])?;
/// assert_eq!(options.header_map()["x-request-source"], "batch");
/// assert_eq!(options.query_pairs(), [("trace".to_owned(), "1".to_owned())]);
/// # Ok::<(), timvw_rust_runtime::Error>(())
/// ```
#[derive(Clone, Debug, Default)]
pub struct RequestOptions {
    headers: HeaderMap,
    query: Vec<(String, String)>,
}

impl RequestOptions {
    /// No extra headers or query parameters.
    pub fn new() -> Self {
        Self::default()
    }

    /// Sets a header, replacing a value set before under the same name. Fails, with an error whose
    /// [`is_builder()`](Error::is_builder) is true, on an invalid name or value.
    pub fn header<K, V>(mut self, name: K, value: V) -> Result<Self, Error>
    where
        HeaderName: TryFrom<K>,
        <HeaderName as TryFrom<K>>::Error: Into<http::Error>,
        HeaderValue: TryFrom<V>,
        <HeaderValue as TryFrom<V>>::Error: Into<http::Error>,
    {
        let name = HeaderName::try_from(name).map_err(|e| Error::builder(e.into()))?;
        let value = HeaderValue::try_from(value).map_err(|e| Error::builder(e.into()))?;
        self.headers.insert(name, value);
        Ok(self)
    }

    /// Adds every header of a map, replacing the values set before under its names. A name with several values in
    /// `headers` keeps them all, and so does the request they are applied to.
    pub fn headers(mut self, headers: HeaderMap) -> Self {
        self.headers.extend(headers);
        self
    }

    /// Appends query parameters, serialized as `application/x-www-form-urlencoded`: a sequence of pairs, a map or a
    /// struct, as [`RequestBuilder::query`](crate::RequestBuilder::query) takes them. Fails, with an error whose
    /// [`is_builder()`](Error::is_builder) is true, on a value that is not a flat list of pairs.
    pub fn query<T: Serialize + ?Sized>(mut self, query: &T) -> Result<Self, Error> {
        let encoded = serde_urlencoded::to_string(query).map_err(Error::builder)?;
        self.query
            .extend(url::form_urlencoded::parse(encoded.as_bytes()).into_owned());
        Ok(self)
    }

    /// The headers.
    pub fn header_map(&self) -> &HeaderMap {
        &self.headers
    }

    /// The query parameters, decoded, in order.
    pub fn query_pairs(&self) -> &[(String, String)] {
        &self.query
    }

    /// Whether there are no headers and no query parameters.
    pub fn is_empty(&self) -> bool {
        self.headers.is_empty() && self.query.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use http::header::{AUTHORIZATION, CONTENT_TYPE};

    #[test]
    fn header_replaces_and_headers_keeps_every_value() {
        let mut extra = HeaderMap::new();
        extra.append("x-tag", HeaderValue::from_static("a"));
        extra.append("x-tag", HeaderValue::from_static("b"));
        let options = RequestOptions::new()
            .header("x-one", "1")
            .unwrap()
            .header("x-one", "2")
            .unwrap()
            .header(CONTENT_TYPE, HeaderValue::from_static("text/plain"))
            .unwrap()
            .headers(extra);
        let headers = options.header_map();
        assert_eq!(headers.get_all("x-one").iter().collect::<Vec<_>>(), ["2"]);
        assert_eq!(
            headers.get_all("x-tag").iter().collect::<Vec<_>>(),
            ["a", "b"]
        );
        assert_eq!(headers[CONTENT_TYPE], "text/plain");
    }

    #[test]
    fn query_keeps_order_and_decodes() {
        let options = RequestOptions::new()
            .query(&[("a", "x y"), ("b", "&")])
            .unwrap()
            .query(&[("a", 2)])
            .unwrap();
        let pairs: Vec<(&str, &str)> = options
            .query_pairs()
            .iter()
            .map(|(k, v)| (k.as_str(), v.as_str()))
            .collect();
        assert_eq!(pairs, [("a", "x y"), ("b", "&"), ("a", "2")]);
    }

    #[test]
    fn invalid_input_is_a_builder_error() {
        assert!(RequestOptions::new()
            .header("x bad", "v")
            .unwrap_err()
            .is_builder());
        assert!(RequestOptions::new()
            .header("x-bad", "line\nbreak")
            .unwrap_err()
            .is_builder());
        // a nested value is not a pair
        assert!(RequestOptions::new()
            .query(&[("a", [1, 2])])
            .unwrap_err()
            .is_builder());
    }

    #[test]
    fn empty() {
        assert!(RequestOptions::new().is_empty());
        assert!(RequestOptions::default().is_empty());
        assert!(!RequestOptions::new()
            .header(AUTHORIZATION, "Bearer x")
            .unwrap()
            .is_empty());
        assert!(!RequestOptions::new()
            .query(&[("a", "")])
            .unwrap()
            .is_empty());
    }
}
