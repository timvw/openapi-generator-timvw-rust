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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code responseFunctions} ({@code response.content.json}, decision 1) on src/test/resources/response_functions.yaml,
 * and on src/test/resources/event_streams.yaml for the methods of {@code contentTypeMethods} and
 * {@code typedEventStreams}. src/test/rust/response_functions.rs calls the functions against a local server;
 * {@link #whatItDoesAtRunTime} runs it, the {@code ApiResponse} doctest and clippy with cargo.
 */
class ResponseFunctionsTest {

    @TempDir
    static Path on;

    @TempDir
    static Path off;

    @TempDir
    static Path variants;

    @BeforeAll
    static void generate() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(TimvwRustCodegen.RESPONSE_FUNCTIONS, "true");
        generate("src/test/resources/response_functions.yaml", on, props);
        generate("src/test/resources/response_functions.yaml", off, new LinkedHashMap<>());
        Map<String, Object> all = new LinkedHashMap<>(props);
        all.put(TimvwRustCodegen.TYPED_EVENT_STREAMS, "true");
        all.put(TimvwRustCodegen.CONTENT_TYPE_METHODS, "true");
        all.put(TimvwRustCodegen.MULTIPART_HELPER, "true");
        generate("src/test/resources/event_streams.yaml", variants, all);
    }

    private static void generate(String spec, Path output, Map<String, Object> options) {
        Map<String, Object> props = new LinkedHashMap<>(options);
        props.put("packageName", "response_functions");
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(spec).toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static String read(Path output, String relative) throws IOException {
        return Files.readString(output.resolve(relative));
    }

    /** The signature of a method, from {@code pub async fn} to its opening brace. */
    private static String signature(Path output, String api, String method) throws IOException {
        String text = read(output, "src/apis/" + api + ".rs");
        int start = text.indexOf("pub async fn " + method + "(");
        assertTrue(start >= 0, method + " not in:\n" + text);
        return text.substring(start, text.indexOf(" {\n", start));
    }

    /** The text of one generated function, from its doc comment to the next function or the end of the file. */
    private static String function(Path output, String api, String method) throws IOException {
        String text = read(output, "src/apis/" + api + ".rs");
        int start = text.indexOf("pub async fn " + method + "(");
        assertTrue(start >= 0, method + " not in:\n" + text);
        int end = text.indexOf("pub async fn ", start + 1);
        return text.substring(start, end < 0 ? text.length() : end);
    }

    private static void assertContains(String text, String needle) {
        assertTrue(text.contains(needle), needle + " not in:\n" + text);
    }

    @Test
    void aJsonOperationGetsAResponseTwinReturningApiResponse() throws IOException {
        assertContains(signature(on, "items_api", "get_item_response"), "get_item_response(configuration: "
                + "&configuration::Configuration, id: &str) -> Result<super::ApiResponse<models::Item>, "
                + "Error<GetItemError>>");
        assertContains(signature(on, "items_api", "list_items_response"),
                "-> Result<super::ApiResponse<Vec<models::Item>>, Error<ListItemsError>>");
        String twin = function(on, "items_api", "get_item_response");
        assertContains(twin, "let headers = resp.headers().clone();\n        let content = resp.text().await?;\n"
                + "        Ok(super::ApiResponse::new(status, headers, content))");
        assertFalse(twin.contains("ContentType::"), twin);
    }

    @Test
    void theOperationItselfIsUnchanged() throws IOException {
        String own = function(off, "items_api", "get_item");
        own = own.substring(0, own.indexOf("\n}\n") + 3);
        assertContains(read(on, "src/apis/items_api.rs"), own);
    }

    @Test
    void theErrorBranchIsTheOperations() throws IOException {
        String own = function(on, "items_api", "get_item");
        String twin = function(on, "items_api", "get_item_response");
        String branch = own.substring(own.indexOf("    } else {"));
        assertContains(twin, branch.substring(0, branch.indexOf("\n}\n")));
    }

    @Test
    void theTwinIsDocumentedAndHasABuilder() throws IOException {
        String text = read(on, "src/apis/items_api.rs");
        assertContains(text, "/// Sends the same request as [`get_item`] and fails the same way, error status included, "
                + "but returns the\n/// success undecoded, with its status and headers: an "
                + "[`ApiResponse`](super::ApiResponse) that decodes the body as\n/// `models::Item` (`json()`)");
        assertContains(text, "#[bon::builder]\npub async fn get_item_response(");
    }

    @Test
    void aUnitOrTextSuccessHasOneAndAFileDoesNot() throws IOException {
        assertContains(signature(on, "items_api", "delete_item_response"),
                "-> Result<super::ApiResponse<()>, Error<DeleteItemError>>");
        assertContains(signature(on, "items_api", "get_version_response"),
                "-> Result<super::ApiResponse<String>, Error<GetVersionError>>");
        assertFalse(read(on, "src/apis/items_api.rs").contains("pub async fn download_response("));
    }

    @Test
    void apiResponseIsWrittenOnlyWithTheOption() throws IOException {
        assertContains(read(on, "src/apis/mod.rs"), "pub mod api_response;\npub use api_response::ApiResponse;\n");
        assertContains(read(on, "src/apis/api_response.rs"), "pub struct ApiResponse<T> {");
        assertContains(read(on, "src/apis/api_response.rs"), "use response_functions::apis::ApiResponse;");
        assertFalse(Files.exists(off.resolve("src/apis/api_response.rs")));
        assertFalse(read(off, "src/apis/mod.rs").contains("api_response"));
        assertFalse(read(off, "src/apis/items_api.rs").contains("_response("));
    }

    @Test
    void contentTypeMethodsGetOneEventStreamsAndBytesDoNot() throws IOException {
        String api = read(variants, "src/apis/default_api.rs");
        assertContains(signature(variants, "default_api", "create_response_response"),
                "-> Result<super::ApiResponse<models::Response>, Error<CreateResponseError>>");
        assertContains(signature(variants, "default_api", "create_edit_json_response"),
                "-> Result<super::ApiResponse<models::Transcription>, Error<CreateEditError>>");
        assertContains(signature(variants, "default_api", "create_transcription_text_response"),
                "-> Result<super::ApiResponse<String>, Error<CreateTranscriptionError>>");
        assertFalse(api.contains("pub async fn create_response_stream_response("), api);
        assertFalse(api.contains("pub async fn create_speech_response("), api);
    }

    @Test
    void aNameThatIsTakenFailsGeneration(@TempDir Path dir) throws IOException {
        Path spec = dir.resolve("taken.yaml");
        Files.writeString(spec, """
                openapi: 3.0.3
                info: {title: taken, version: 1.0.0}
                paths:
                  /a:
                    get:
                      operationId: getItem
                      responses:
                        "200": {description: ok, content: {application/json: {schema: {type: string}}}}
                  /b:
                    get:
                      operationId: getItemResponse
                      responses:
                        "204": {description: ok}
                """);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(TimvwRustCodegen.RESPONSE_FUNCTIONS, "true");
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> generate(spec.toString(), dir.resolve("out"), props));
        String messages = "";
        for (Throwable t = e; t != null; t = t.getCause()) {
            messages += t.getMessage() + "\n";
        }
        assertTrue(messages.contains("responseFunctions: get_item would get the method get_item_response, but "
                + "another operation of its API module is named get_item_response"), messages);
    }

    /**
     * Runs src/test/rust/response_functions.rs, the {@code ApiResponse} doctest and clippy on the generated crate.
     * Needs cargo; without it there is nothing to run.
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
        Files.copy(Path.of("src/test/rust/response_functions.rs"), on.resolve("tests/response_functions.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        String test = cargo(on, 900, "test", "--test", "response_functions");
        assertNotNull(test, "cargo did not finish");
        assertTrue(test.contains("test result: ok.") && !test.contains(" 0 passed"), test);
        // The README's examples are not Rust; only the doctest of ApiResponse runs.
        String doc = cargo(on, 900, "test", "--doc", "ApiResponse");
        assertNotNull(doc, "cargo did not finish");
        assertTrue(doc.contains("test result: ok. 1 passed"), doc);
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
