package be.timvw.openapi.rust;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The typed success and error headers of {@code responseFunctions} ({@code header.schema.typed},
 * {@code header.schema.typed-error}) on
 * src/test/resources/response_headers.yaml. src/test/rust/response_headers.rs reads them from header maps and from a
 * call against a local server; {@link #whatItDoesAtRunTime} runs it and clippy with cargo.
 */
class ResponseHeadersTest {

    @TempDir
    static Path on;

    @TempDir
    static Path off;

    @BeforeAll
    static void generate() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(TimvwRustCodegen.RESPONSE_FUNCTIONS, "true");
        generate("src/test/resources/response_headers.yaml", on, props);
        generate("src/test/resources/response_headers.yaml", off, new LinkedHashMap<>());
    }

    private static void generate(String spec, Path output, Map<String, Object> options) {
        Map<String, Object> props = new LinkedHashMap<>(options);
        props.put("packageName", "response_headers");
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(spec).toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static String api(Path output) throws IOException {
        return Files.readString(output.resolve("src/apis/images_api.rs"));
    }

    /** The struct declaration, from {@code pub struct} to its closing brace. */
    private static String struct(String text, String name) {
        int start = text.indexOf("pub struct " + name + " {");
        assertTrue(start >= 0, name + " not in:\n" + text);
        return text.substring(start, text.indexOf("\n}\n", start) + 2);
    }

    private static void assertContains(String text, String needle) {
        assertTrue(text.contains(needle), needle + " not in:\n" + text);
    }

    @Test
    void eachDeclaredHeaderIsAnOptionOfItsParameterType() throws IOException {
        String s = struct(api(on), "CreateImageResponseHeaders");
        for (String field : new String[] {
                "/// `X-RateLimit-Remaining-Images`: The number of images remaining in the rate-limit window.\n"
                        + "    pub x_ratelimit_remaining_images: Option<i32>,",
                "pub x_ratelimit_reset_images: Option<String>,",
                "pub x_cost: Option<f64>,",
                "pub x_cached: Option<bool>,",
                "pub x_request_id: Option<uuid::Uuid>,",
                "pub last_modified_at: Option<chrono::DateTime<chrono::FixedOffset>>,",
                "pub x_day: Option<chrono::NaiveDate>,",
                "pub x_status: Option<models::Status>,",
                "pub x_level: Option<models::Level>,",
                "pub x_mode: Option<String>,",
                "/// `X-Ids`, a comma-separated list\n    pub x_ids: Option<Vec<i64>>,",
                "pub x_tags: Option<Vec<String>>,",
                "pub x_statuses: Option<Vec<models::Status>>,",
                "pub x_object: Option<String>,",
                "pub x_content: Option<i64>,",
                "pub r#type: Option<String>,",
                "/// `X-Shared`: A header from the components.\n    pub x_shared: Option<i64>,"}) {
            assertContains(s, field);
        }
        assertContains(api(on), "#[derive(Clone, Debug, Default, PartialEq)]\npub struct CreateImageResponseHeaders {");
    }

    @Test
    void theFieldsAreReadLeniently() throws IOException {
        String text = api(on);
        assertContains(text, "x_ratelimit_remaining_images: super::api_response::header_value(headers, "
                + "\"x-ratelimit-remaining-images\", |v| v.parse::<i32>().ok()),");
        assertContains(text, "last_modified_at: super::api_response::header_value(headers, \"last-modified-at\", "
                + "|v| chrono::DateTime::parse_from_rfc3339(v).ok()),");
        assertContains(text, "x_status: super::api_response::header_value(headers, \"x-status\", |v| "
                + "serde_json::from_value::<models::Status>(serde_json::Value::String(v.to_owned())).ok()),");
        assertContains(text, "x_ids: super::api_response::header_list(headers, \"x-ids\", |v| v.parse::<i64>().ok()),");
        assertContains(text, "impl From<&reqwest::header::HeaderMap> for CreateImageResponseHeaders {");
        String helpers = Files.readString(on.resolve("src/apis/api_response.rs"));
        assertContains(helpers, "pub fn typed_headers<H>(&self) -> H");
        assertContains(helpers, "pub(crate) fn header_value<T>(");
        assertContains(helpers, "pub(crate) fn header_list<T>(");
    }

    @Test
    void theStructHoldsTheHeadersOfEverySuccessAndAConflictIsText() throws IOException {
        String s = struct(api(on), "CreateImageResponseHeaders");
        // 201 only
        assertContains(s, "pub location: Option<String>,");
        // an integer in the 200, a string in the 201
        assertContains(s, "pub retry_after: Option<String>,");
        assertContains(api(on), "/// The headers the success responses of [`create_image`] declare (`200`, `201`), "
                + "typed.");
    }

    @Test
    void hopByHopHeadersAndContentTypeGetNoField() throws IOException {
        String s = struct(api(on), "CreateImageResponseHeaders");
        assertFalse(s.contains("transfer_encoding"), s);
        assertFalse(s.contains("content_type"), s);
        // an operation whose successes declare only those has no struct
        assertFalse(api(on).contains("HopOnlyResponseHeaders"));
    }

    @Test
    void referencedResponsesAndHeadersAreResolved() throws IOException {
        assertContains(struct(api(on), "GetImageResponseHeaders"), "/// `ETag`\n    pub etag: Option<String>,");
        assertContains(struct(api(on), "DeleteImageResponseHeaders"), "pub x_deleted_count: Option<i64>,");
    }

    @Test
    void theTwinNamesItsStruct() throws IOException {
        assertContains(api(on), "/// The headers the spec declares are typed: "
                + "`typed_headers::<CreateImageResponseHeaders>()` ([`CreateImageResponseHeaders`]).\n");
    }

    @Test
    void noStructForErrorHeadersAFileResponseOrWithoutTheOption() throws IOException {
        String text = api(on);
        // listImages declares headers only on its 429
        assertFalse(text.contains("ListImagesResponseHeaders"), text);
        // a file response has no twin: its function returns the reqwest::Response
        assertFalse(text.contains("DownloadImageResponseHeaders"), text);
        assertFalse(api(off).contains("ResponseHeaders"));
        assertFalse(api(off).contains("header_value"));
    }

    @Test
    void withoutDeclaredSuccessHeadersApiResponseIsUnchanged(@TempDir Path dir) throws IOException {
        Path spec = dir.resolve("plain.yaml");
        Files.writeString(spec, """
                openapi: 3.0.3
                info: {title: plain, version: 1.0.0}
                paths:
                  /a:
                    get:
                      operationId: getA
                      responses:
                        "200": {description: ok, content: {application/json: {schema: {type: string}}}}
                        "429":
                          description: slow down
                          headers:
                            Retry-After: {schema: {type: integer}}
                """);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(TimvwRustCodegen.RESPONSE_FUNCTIONS, "true");
        generate(spec.toString(), dir.resolve("out"), props);
        String apiResponse = Files.readString(dir.resolve("out/src/apis/api_response.rs"));
        assertFalse(apiResponse.contains("pub fn typed_headers"), apiResponse);
        String text = Files.readString(dir.resolve("out/src/apis/default_api.rs"));
        assertFalse(text.contains("ResponseHeaders"), text);
        // the 429's header is an error header, which the helper reads
        assertContains(text, "pub struct GetAErrorHeaders {");
        assertContains(apiResponse, "pub(crate) fn header_value<T>(");
    }

    @Test
    void withoutDeclaredHeadersApiResponseAndResponseContentAreUnchanged(@TempDir Path dir) throws IOException {
        String text = generated(dir, """
                openapi: 3.0.3
                info: {title: plain, version: 1.0.0}
                paths:
                  /a:
                    get:
                      operationId: getA
                      responses:
                        "200": {description: ok, content: {application/json: {schema: {type: string}}}}
                        "429": {description: slow down}
                """);
        String apiResponse = Files.readString(dir.resolve("out/src/apis/api_response.rs"));
        assertFalse(apiResponse.contains("typed_headers"), apiResponse);
        assertFalse(apiResponse.contains("header_value"), apiResponse);
        assertTrue(apiResponse.endsWith("            .finish()\n    }\n}\n"), apiResponse);
        String mod = Files.readString(dir.resolve("out/src/apis/mod.rs"));
        assertFalse(mod.contains("typed_headers"), mod);
        assertFalse(text.contains("Headers"), text);
    }

    @Test
    void errorHeadersAreTheUnionOfTheErrorStatuses() throws IOException {
        String text = api(on);
        String s = struct(text, "CreateImageErrorHeaders");
        assertContains(s, "/// `Retry-After`\n    pub retry_after: Option<i64>,");
        assertContains(s, "/// `X-Maintenance-Until`: When the service is back.\n"
                + "    pub x_maintenance_until: Option<chrono::DateTime<chrono::FixedOffset>>,");
        // declared by the 503 and the default, one field
        assertContains(s, "pub x_request_id: Option<uuid::Uuid>,");
        assertEquals(1, s.split("x_request_id", -1).length - 1, s);
        assertContains(s, "pub x_trace_id: Option<String>,");
        assertContains(text, "/// The headers the error responses of [`create_image`] declare (`429`, `503`, "
                + "`default`), typed.");
        assertContains(text, "#[derive(Clone, Debug, Default, PartialEq)]\npub struct CreateImageErrorHeaders {");
        assertContains(text, "x_request_id: super::api_response::header_value(headers, \"x-request-id\", "
                + "|v| v.parse::<uuid::Uuid>().ok()),");
        assertContains(text, "impl From<&reqwest::header::HeaderMap> for CreateImageErrorHeaders {");
        // the success headers are a struct of their own
        assertFalse(struct(text, "CreateImageResponseHeaders").contains("x_trace_id"));
    }

    @Test
    void errorHeadersDeclaredWithDifferentTypesAreText() throws IOException {
        // an integer in the 429, a string in the 404
        assertContains(struct(api(on), "GetImageErrorHeaders"), "pub retry_after: Option<String>,");
    }

    @Test
    void errorHeadersSkipHopByHopContentTypeAndInvalidNames() throws IOException {
        String s = struct(api(on), "CreateImageErrorHeaders");
        assertFalse(s.contains("connection"), s);
        assertFalse(s.contains("content_type"), s);
        assertFalse(s.contains("bad"), s);
    }

    @Test
    void aFileOperationsErrorHeadersAreTyped() throws IOException {
        String text = api(on);
        String s = struct(text, "DownloadImageErrorHeaders");
        assertContains(s, "/// `X-Missing-Ids`, a comma-separated list\n    pub x_missing_ids: Option<Vec<i64>>,");
        assertContains(s, "pub x_status: Option<models::Status>,");
        assertFalse(text.contains("DownloadImageResponseHeaders"), text);
    }

    @Test
    void theErrorEnumNamesItsStructAndResponseContentReadsIt() throws IOException {
        assertContains(api(on), "/// struct for typed errors of method [`create_image`]\n///\n"
                + "/// The headers its error responses declare are typed: [`CreateImageErrorHeaders`], read from the\n"
                + "/// [`ResponseContent`] with `content.typed_headers::<CreateImageErrorHeaders>()`.\n"
                + "#[derive(Debug, Clone, Serialize, Deserialize)]\n#[serde(untagged)]\npub enum CreateImageError {");
        // no declared error headers, no line
        assertContains(api(on), "/// struct for typed errors of method [`delete_image`]\n#[derive(");
        String mod = Files.readString(on.resolve("src/apis/mod.rs"));
        assertContains(mod, "impl<T> ResponseContent<T> {");
        assertContains(mod, "    pub fn typed_headers<H>(&self) -> H\n    where\n"
                + "        H: for<'h> From<&'h reqwest::header::HeaderMap>,\n    {\n        H::from(&self.headers)\n");
    }

    @Test
    void noErrorHeadersWithoutTheOption() throws IOException {
        assertFalse(api(off).contains("ErrorHeaders"));
        assertFalse(api(off).contains("typed_headers"));
        String mod = Files.readString(off.resolve("src/apis/mod.rs"));
        assertFalse(mod.contains("impl<T> ResponseContent<T>"), mod);
        assertFalse(mod.contains("typed_headers"), mod);
    }

    @Test
    void theMethodsOfAnOperationShareItsStruct(@TempDir Path dir) throws IOException {
        Path spec = dir.resolve("variants.yaml");
        Files.writeString(spec, """
                openapi: 3.0.3
                info: {title: variants, version: 1.0.0}
                paths:
                  /t:
                    post:
                      operationId: transcribe
                      responses:
                        "200":
                          description: ok
                          headers:
                            X-Request-Id: {schema: {type: string}}
                          content:
                            application/json: {schema: {$ref: '#/components/schemas/T'}}
                            text/plain: {schema: {type: string}}
                        "429":
                          description: slow down
                          headers:
                            Retry-After: {schema: {type: integer}}
                components:
                  schemas:
                    T: {type: object, properties: {text: {type: string}}}
                """);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(TimvwRustCodegen.RESPONSE_FUNCTIONS, "true");
        props.put(TimvwRustCodegen.CONTENT_TYPE_METHODS, "true");
        generate(spec.toString(), dir.resolve("out"), props);
        String text = Files.readString(dir.resolve("out/src/apis/default_api.rs"));
        assertEquals(1, text.split("pub struct TranscribeResponseHeaders \\{", -1).length - 1, text);
        assertEquals(2, text.split("`typed_headers::<TranscribeResponseHeaders>\\(\\)`", -1).length - 1, text);
        assertTrue(text.contains("pub async fn transcribe_text_response("), text);
        assertEquals(1, text.split("pub struct TranscribeErrorHeaders \\{", -1).length - 1, text);
    }

    /** Generates {@code spec} with {@code responseFunctions} (and {@code options}) and returns default_api.rs. */
    private static String generated(Path dir, String spec, String... options) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("spec.yaml");
        Files.writeString(file, spec);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(TimvwRustCodegen.RESPONSE_FUNCTIONS, "true");
        for (String option : options) {
            props.put(option.substring(0, option.indexOf('=')), option.substring(option.indexOf('=') + 1));
        }
        generate(file.toString(), dir.resolve("out"), props);
        return Files.readString(dir.resolve("out/src/apis/default_api.rs"));
    }

    @Test
    void twoHeadersWithTheSameFieldAreNumbered(@TempDir Path dir) throws IOException {
        String text = generated(dir, """
                openapi: 3.0.3
                info: {title: clash, version: 1.0.0}
                paths:
                  /a:
                    get:
                      operationId: getA
                      responses:
                        "204":
                          description: ok
                          headers:
                            x-foo: {schema: {type: string}}
                            x_foo: {schema: {type: integer}}
                        "429":
                          description: slow down
                          headers:
                            x-foo: {schema: {type: string}}
                            x_foo: {schema: {type: integer}}
                  /b:
                    get:
                      operationId: getB
                      responses:
                        "200":
                          description: a file, no struct
                          headers:
                            x-foo: {schema: {type: string}}
                            x_foo: {schema: {type: string}}
                          content:
                            application/octet-stream: {schema: {type: string, format: binary}}
                """);
        String s = struct(text, "GetAResponseHeaders");
        assertContains(s, "/// `x-foo`\n    pub x_foo: Option<String>,");
        assertContains(s, "/// `x_foo`\n    pub x_foo_2: Option<i64>,");
        assertContains(text, "x_foo_2: super::api_response::header_value(headers, \"x_foo\",");
        String errors = struct(text, "GetAErrorHeaders");
        assertContains(errors, "/// `x-foo`\n    pub x_foo: Option<String>,");
        assertContains(errors, "/// `x_foo`\n    pub x_foo_2: Option<i64>,");
        assertFalse(text.contains("GetBResponseHeaders"), text);
    }

    @Test
    void aNameThatIsNotAnHttpTokenGetsNoField(@TempDir Path dir) throws IOException {
        String text = generated(dir, """
                openapi: 3.0.3
                info: {title: names, version: 1.0.0}
                paths:
                  /a:
                    get:
                      operationId: getA
                      responses:
                        "204":
                          description: ok
                          headers:
                            "x bad": {schema: {type: string}}
                            "x-caf\u00e9": {schema: {type: string}}
                            "x-good": {schema: {type: string}}
                """);
        String s = struct(text, "GetAResponseHeaders");
        assertContains(s, "pub x_good: Option<String>,");
        assertFalse(s.contains("x_bad"), s);
        assertFalse(s.contains("caf"), s);
    }

    @Test
    void withoutChronoDatesAreText(@TempDir Path dir) throws IOException {
        String text = generated(dir, """
                openapi: 3.0.3
                info: {title: dates, version: 1.0.0}
                paths:
                  /a:
                    get:
                      operationId: getA
                      responses:
                        "204":
                          description: ok
                          headers:
                            X-At: {schema: {type: string, format: date-time}}
                            X-Day: {schema: {type: string, format: date}}
                """, "useChrono=false");
        String s = struct(text, "GetAResponseHeaders");
        assertContains(s, "pub x_at: Option<String>,");
        assertContains(s, "pub x_day: Option<String>,");
        assertFalse(Files.readString(dir.resolve("out/Cargo.toml")).contains("chrono"));
    }

    @Test
    void openApi31TypeArraysTakeTheirNonNullType(@TempDir Path dir) throws IOException {
        String text = generated(dir, """
                openapi: 3.1.0
                info: {title: types, version: 1.0.0}
                paths:
                  /a:
                    get:
                      operationId: getA
                      responses:
                        "204":
                          description: ok
                          headers:
                            X-Count: {schema: {type: [integer, "null"]}}
                            X-Ids: {schema: {type: [array, "null"], items: {type: integer}}}
                """);
        String s = struct(text, "GetAResponseHeaders");
        assertContains(s, "pub x_count: Option<i64>,");
        assertContains(s, "pub x_ids: Option<Vec<i64>>,");
    }

    @Test
    void onlyTheHelpersInUseAreWritten(@TempDir Path dir) throws IOException {
        generated(dir.resolve("list"), """
                openapi: 3.0.3
                info: {title: lists, version: 1.0.0}
                paths:
                  /a:
                    get:
                      operationId: getA
                      responses:
                        "204":
                          description: ok
                          headers:
                            X-Ids: {schema: {type: array, items: {type: integer}}}
                """);
        String lists = Files.readString(dir.resolve("list/out/src/apis/api_response.rs"));
        assertContains(lists, "pub(crate) fn header_list<T>(");
        assertFalse(lists.contains("fn header_value"), lists);
        generated(dir.resolve("value"), """
                openapi: 3.0.3
                info: {title: values, version: 1.0.0}
                paths:
                  /a:
                    get:
                      operationId: getA
                      responses:
                        "204":
                          description: ok
                          headers:
                            X-Id: {schema: {type: integer}}
                """);
        String values = Files.readString(dir.resolve("value/out/src/apis/api_response.rs"));
        assertContains(values, "pub(crate) fn header_value<T>(");
        assertFalse(values.contains("fn header_list"), values);
    }

    /**
     * Runs src/test/rust/response_headers.rs and clippy on the generated crate. Needs cargo; without it there is
     * nothing to run.
     */
    @Test
    void whatItDoesAtRunTime() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(on, 30, "--version") != null, "no cargo");
        Path manifest = on.resolve("Cargo.toml");
        String cargo = Files.readString(manifest);
        if (!cargo.contains("[dev-dependencies]")) {
            Files.writeString(manifest, cargo + """

                    [dev-dependencies]
                    tokio = { version = "1", features = ["rt-multi-thread", "macros", "net", "io-util"] }
                    """);
        }
        Files.createDirectories(on.resolve("tests"));
        Files.copy(Path.of("src/test/rust/response_headers.rs"), on.resolve("tests/response_headers.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        String test = cargo(on, 900, "test", "--test", "response_headers");
        assertNotNull(test, "cargo did not finish");
        assertTrue(test.contains("test result: ok.") && !test.contains(" 0 passed"), test);
        // needless_return: ContentType::from in src/apis/mod.rs, which this option does not touch
        String clippy = cargo(on, 900, "clippy", "--all-targets", "--", "-D", "warnings",
                "-A", "clippy::needless_return");
        assertNotNull(clippy, "clippy did not finish");
        assertTrue(clippy.contains("Finished") && !clippy.contains("warning:"), clippy);
    }

    /** What cargo printed, or {@code null} when it cannot run or does not finish in time. */
    private static String cargo(Path dir, long seconds, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("cargo"));
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile("cargo", ".log");
        Process process;
        try {
            process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
        } catch (IOException e) {
            return null;
        }
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return null;
        }
        String output = Files.readString(log);
        Files.delete(log);
        return output;
    }
}
