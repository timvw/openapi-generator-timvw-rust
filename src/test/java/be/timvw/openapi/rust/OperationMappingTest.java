package be.timvw.openapi.rust;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.ClientOptInput;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The operation-side rules of docs/mapping (Operation, Parameter, Request Body, Responses, Server, Security Scheme)
 * that no option test covers, rendered without options from
 * {@code src/test/resources/operation_mapping.yaml}.
 */
class OperationMappingTest {

    @TempDir
    static Path out;

    @BeforeAll
    static void generate() {
        Map<String, Object> props = new LinkedHashMap<>(SchemaMappingTest.BASELINE);
        props.put("packageName", "operation_mapping");
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/operation_mapping.yaml").toAbsolutePath().toString())
                .setOutputDir(out.toString())
                .setAdditionalProperties(props);
        ClientOptInput input = configurator.toClientOptInput();
        new DefaultGenerator().opts(input).generate();
    }

    private static String read(String relative) throws IOException {
        return Files.readString(out.resolve(relative));
    }

    /** The text of one generated function, from its signature to the next function or the end of the file. */
    private static String function(String api, String name) throws IOException {
        String text = read("src/apis/" + api + ".rs");
        int start = text.indexOf("pub async fn " + name + "(");
        assertTrue(start >= 0, name + " not in:\n" + text);
        int end = text.indexOf("pub async fn ", start + 1);
        return text.substring(start, end < 0 ? text.length() : end);
    }

    private static void assertContains(String text, String needle) {
        assertTrue(text.contains(needle), needle + " not in:\n" + text);
    }

    @Test
    void aTagIsAModuleAndUntaggedOperationsAreInDefaultApi() throws IOException {
        String mod = read("src/apis/mod.rs");
        assertContains(mod, "pub mod items_api;");
        assertContains(mod, "pub mod default_api;");
        assertContains(read("src/apis/default_api.rs"), "pub async fn login(");
    }

    @Test
    void anOperationIsAnAsyncFunctionWithABuilder() throws IOException {
        String items = read("src/apis/items_api.rs");
        assertContains(items, "#[bon::builder]\npub async fn create_item(configuration: &configuration::Configuration, "
                + "item: &models::Item) -> Result<models::Item, Error<CreateItemError>> {");
    }

    @Test
    void parametersAreArgumentsInSpecOrder() throws IOException {
        assertContains(read("src/apis/items_api.rs"), "pub async fn get_item(configuration: &configuration::Configuration, "
                + "item_id: &str, limit: Option<i32>, tag: Option<Vec<String>>, ids: Option<Vec<String>>, "
                + "filter: Option<&str>, x_request_id: Option<&str>, x_flags: Option<Vec<String>>, "
                + "session: Option<&str>, order: Option<&str>)");
    }

    @Test
    void aDeprecatedOperationIsDeprecated() throws IOException {
        assertContains(read("src/apis/items_api.rs"), "#[deprecated]\n#[bon::builder]\npub async fn delete_item(");
    }

    @Test
    void aPathParameterIsUrlEncodedIntoThePath() throws IOException {
        assertContains(function("items_api", "get_item"),
                "format!(\"{}/items/{item_id}\", configuration.base_path, item_id=crate::apis::urlencode(p_path_item_id))");
    }

    @Test
    void queryParametersFollowTheirStyle() throws IOException {
        String get = function("items_api", "get_item");
        assertContains(get, "req_builder = req_builder.query(&[(\"limit\", &param_value.to_string())]);");
        assertContains(get, "req_builder = req_builder.query(&[(\"tag\", item)]);");
        assertContains(get, "items.join(\",\"))]);");
        assertContains(get, "req_builder = req_builder.query(&[(\"filter\", &param_value.to_string())]);");
    }

    @Test
    void headersAreSentAndArraysJoined() throws IOException {
        String get = function("items_api", "get_item");
        assertContains(get, "req_builder = req_builder.header(\"X-Request-Id\", param_value.to_string());");
        assertContains(get, "req_builder = req_builder.header(\"X-Flags\", param_value.join(\",\").to_string());");
    }

    @Test
    void aCookieParameterIsSentInTheCookieHeader() throws IOException {
        String get = function("items_api", "get_item");
        assertContains(get, "if let Some(ref param_value) = p_cookie_session {\n"
                + "        cookies.push(format!(\"session={}\", crate::apis::cookie_encode(param_value)));");
        assertContains(get, "req_builder = req_builder.header(reqwest::header::COOKIE, cookies.join(\"; \"));");
        assertFalse(function("items_api", "delete_item").contains("COOKIE"));
    }

    @Test
    void anInlineEnumParameterIsAString() throws IOException {
        assertContains(function("items_api", "get_item"), "order: Option<&str>)");
    }

    @Test
    void aJsonBodyIsSentWithJson() throws IOException {
        assertContains(function("items_api", "create_item"), "req_builder = req_builder.json(p_body_item);");
    }

    @Test
    void aFormBodyIsAnArgumentPerPropertySentWithForm() throws IOException {
        String login = function("default_api", "login");
        assertContains(login, "pub async fn login(configuration: &configuration::Configuration, user: &str, "
                + "remember: Option<bool>) -> Result<String, Error<LoginError>> {");
        assertContains(login, "req_builder = req_builder.form(&multipart_form_params);");
    }

    @Test
    void aJsonResponseIsDecodedAndATextResponseIsAString() throws IOException {
        assertContains(function("items_api", "get_item"),
                "ContentType::Json => serde_json::from_str(&content).map_err(Error::from),");
        assertContains(function("default_api", "login"), "ContentType::Text => Ok(content),");
    }

    @Test
    void aResponseWithoutABodyIsUnit() throws IOException {
        assertContains(function("items_api", "delete_item"), "-> Result<(), Error<DeleteItemError>> {");
    }

    @Test
    void errorResponsesAreAnUntaggedEnumPerOperation() throws IOException {
        String items = read("src/apis/items_api.rs");
        assertContains(items, "#[serde(untagged)]\npub enum GetItemError {\n    Status404(models::Problem),\n"
                + "    DefaultResponse(models::Problem),\n    UnknownValue(serde_json::Value),\n}");
        assertContains(function("items_api", "get_item"),
                "let entity: Option<GetItemError> = match status.as_u16() {\n"
                        + "            404 => serde_json::from_str(&content).ok().map(GetItemError::Status404),\n"
                        + "            _ => serde_json::from_str(&content).ok().map(GetItemError::DefaultResponse),\n"
                        + "        }\n"
                        + "        .or_else(|| serde_json::from_str(&content).ok().map(GetItemError::UnknownValue));\n"
                        + "        Err(Error::ResponseError(Box::new(ResponseContent { status, content, entity, headers })))");
        String mod = read("src/apis/mod.rs");
        assertContains(mod, "pub struct ResponseContent<T> {\n    pub status: reqwest::StatusCode,\n"
                + "    pub content: String,\n    pub entity: Option<T>,\n"
                + "    /// The response's headers (`retry-after`, `x-request-id`, rate limits, ...).\n"
                + "    pub headers: reqwest::header::HeaderMap,\n}");
        assertContains(mod, "    Reqwest(reqwest::Error),\n    ReqwestMiddleware(reqwest_middleware::Error),\n"
                + "    Serde(serde_json::Error),\n    Io(std::io::Error),\n    ResponseError(Box<ResponseContent<T>>),");
    }

    @Test
    void theFirstServerIsTheBasePathAndTheVersionNamesTheUserAgent() throws IOException {
        String configuration = read("src/apis/configuration.rs");
        assertContains(configuration, "base_path: \"https://api.example.com/v1\".to_owned(),");
        assertContains(configuration, "user_agent: Some(\"OpenAPI-Generator/2.1.0/rust\".to_owned()),");
        assertContains(configuration, "pub client: reqwest_middleware::ClientWithMiddleware,");
    }

    @Test
    void eachOperationSendsTheCredentialsOfItsSecurity() throws IOException {
        assertContains(function("items_api", "get_item"), "req_builder = req_builder.bearer_auth(token.to_owned());");
        String delete = function("items_api", "delete_item");
        assertContains(delete, "req_builder.basic_auth(auth_conf.0.to_owned(), auth_conf.1.to_owned());");
        assertFalse(delete.contains("bearer_auth"), delete);
        assertContains(function("items_api", "create_item"), "req_builder = req_builder.header(\"X-Api-Key\", value);");
        assertContains(function("default_api", "login"), "if let Some(ref token) = configuration.oauth_access_token {");
        String configuration = read("src/apis/configuration.rs");
        assertContains(configuration, "pub basic_auth: Option<BasicAuth>,");
        assertContains(configuration, "pub type BasicAuth = (String, Option<String>);");
        assertContains(configuration, "pub api_key: Option<ApiKey>,");
    }
}
