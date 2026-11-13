package be.timvw.openapi.rust;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code typedEventStreams} and {@code contentTypeMethods} (Milestone 3.6, {@link ContentTypeMethods}) on
 * src/test/resources/event_streams.yaml, which has the shapes of openai-client-base's operations with more than one
 * media type. What the generated methods do on the wire is in src/test/rust/event_streams.rs, which
 * {@link #whatItDoesAtRunTime} runs with cargo against a local server.
 */
class EventStreamsTest {

    private static final String SPEC = "src/test/resources/event_streams.yaml";

    @TempDir
    static Path on;

    @TempDir
    static Path off;

    @TempDir
    static Path streamsOnly;

    /** Both options with {@code supportMultipleResponses}. */
    @TempDir
    static Path multiple;

    @BeforeAll
    static void generate() {
        generate(on, true, true);
        generate(off, false, false);
        generate(streamsOnly, true, false);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(RustCodegenBase.SUPPORT_MULTIPLE_RESPONSES, "true");
        generate(multiple, true, true, props);
    }

    private static void generate(Path output, boolean typedEventStreams, boolean contentTypeMethods) {
        generate(output, typedEventStreams, contentTypeMethods, Map.of());
    }

    private static void generate(Path output, boolean typedEventStreams, boolean contentTypeMethods,
            Map<String, Object> extra) {
        Map<String, Object> props = new LinkedHashMap<>(extra);
        props.put("packageName", "event_streams_fixture");
        props.put(TimvwRustCodegen.MULTIPART_HELPER, "true");
        if (typedEventStreams) {
            props.put(TimvwRustCodegen.TYPED_EVENT_STREAMS, "true");
        }
        if (contentTypeMethods) {
            props.put(TimvwRustCodegen.CONTENT_TYPE_METHODS, "true");
        }
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(SPEC).toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static String api(Path output) throws IOException {
        return Files.readString(output.resolve("src/apis/default_api.rs"));
    }

    private static final Pattern FN = Pattern.compile("(?m)^pub async fn (\\w+)\\(");

    private static List<String> methods(Path output) throws IOException {
        List<String> names = new ArrayList<>();
        Matcher m = FN.matcher(api(output));
        while (m.find()) {
            names.add(m.group(1));
        }
        return names.stream().sorted().collect(Collectors.toList());
    }

    /** The signature of a method, from {@code pub async fn} to its opening brace. */
    private static String signature(Path output, String method) throws IOException {
        String api = api(output);
        int start = api.indexOf("pub async fn " + method + "(");
        assertTrue(start >= 0, method);
        return api.substring(start, api.indexOf(" {\n", start));
    }

    @Test
    void everyMediaTypeHasAMethodNamedWithItsSuffix() throws IOException {
        assertEquals(List.of(
                "create_call", "create_call_sdp",
                "create_chat", "create_chat_stream",
                "create_edit", "create_edit_json", "create_edit_json_stream", "create_edit_stream",
                "create_response", "create_response_stream",
                "create_run",
                "create_speech", "create_speech_stream",
                "create_transcription", "create_transcription_stream", "create_transcription_text",
                "create_voice", "create_voice_multipart",
                "list_events"), methods(on));
    }

    @Test
    void theOperationsOwnMethodKeepsWhatItDid() throws IOException {
        for (String method : List.of("create_response", "create_edit", "create_voice", "create_call", "create_speech",
                "create_transcription", "create_chat", "create_run")) {
            assertEquals(signature(off, method), signature(on, method), method);
        }
        // An operation that only streams: decoding JSON could never succeed, its own method streams.
        assertTrue(signature(off, "list_events").contains("Result<models::ResponseStreamEvent, Error<ListEventsError>>"));
        assertTrue(signature(on, "list_events").contains(
                "Result<super::sse::EventStream<models::ResponseStreamEvent, ListEventsError>, Error<ListEventsError>>"));
    }

    @Test
    void theMethodsOfAnOperationShareItsErrorEnum() throws IOException {
        String api = api(on);
        assertTrue(signature(on, "create_response_stream").endsWith(
                "-> Result<super::sse::EventStream<models::ResponseStreamEvent, CreateResponseError>, "
                        + "Error<CreateResponseError>>"));
        assertTrue(signature(on, "create_edit_json_stream").contains("Error<CreateEditError>>"));
        assertTrue(signature(on, "create_transcription_text").endsWith("-> Result<String, Error<CreateTranscriptionError>>"));
        assertTrue(signature(on, "create_speech_stream").contains("EventStream<models::ResponseStreamEvent, CreateSpeechError>"));
        assertTrue(api.contains("pub enum CreateResponseError {"));
        assertFalse(api.contains("CreateResponseStreamError"), api);
        assertFalse(api.contains("CreateEditJsonError"), api);
    }

    @Test
    void requestBodiesGoOutAsTheirMediaType() throws IOException {
        assertTrue(signature(on, "create_edit_json").contains("create_edit_json: &models::CreateEditJson"));
        assertTrue(signature(on, "create_response_stream").contains("create_response: &models::CreateResponse"));
        assertTrue(signature(on, "create_voice_multipart").contains("#[builder(into)] audio: timvw_rust_runtime::Upload"));
        String api = api(on);
        assertTrue(api.contains("req_builder = req_builder.header(timvw_rust_runtime::header::CONTENT_TYPE, \"application/sdp\")"
                + ".body(p_body_body.to_owned());"), api);
        assertTrue(api.contains("/// Sends the request body as `application/json`; `create_edit` sends it as "
                + "`multipart/form-data`."), api);
    }

    @Test
    void aStreamAsksForItAndChecksTheContentType() throws IOException {
        String api = api(on);
        int start = api.indexOf("pub async fn create_response_stream(");
        String body = api.substring(start, api.indexOf("\n}\n", start));
        assertTrue(body.contains("req_builder = req_builder.header(timvw_rust_runtime::header::ACCEPT, \"text/event-stream\");"));
        assertTrue(body.contains("if !content_type.starts_with(\"text/event-stream\") {"));
        assertTrue(body.contains("Ok(super::sse::EventStream::from_response(resp))"));
        assertTrue(Files.exists(on.resolve("src/apis/sse.rs")));
        assertTrue(Files.readString(on.resolve("src/apis/mod.rs")).contains("pub mod sse;"));
        String cargo = Files.readString(on.resolve("Cargo.toml"));
        assertTrue(cargo.contains("futures-core = \"^0.3\""), cargo);
        // The runtime always streams response bodies.
        assertTrue(cargo.contains("timvw-rust-runtime = "), cargo);
    }

    @Test
    void theSseEnvelopeGetsNoStream() throws IOException {
        assertFalse(methods(on).contains("create_run_stream"));
        Operation run = parse().getPaths().get("/runs").getPost();
        assertTrue(ContentTypeMethods.isEnvelope(parse(), run.getResponses().get("200").getContent()
                .get("text/event-stream").getSchema()));
        Operation response = parse().getPaths().get("/responses").getPost();
        assertFalse(ContentTypeMethods.isEnvelope(parse(), response.getResponses().get("200").getContent()
                .get("text/event-stream").getSchema()));
    }

    @Test
    void streamsWithoutContentTypeMethods() throws IOException {
        assertEquals(List.of("create_call", "create_chat", "create_chat_stream", "create_edit", "create_edit_stream",
                "create_response", "create_response_stream", "create_run", "create_speech", "create_speech_stream",
                "create_transcription", "create_transcription_stream", "create_voice", "list_events"),
                methods(streamsOnly));
    }

    @Test
    void withoutTheOptionsNothingChanges() throws IOException {
        assertEquals(List.of("create_call", "create_chat", "create_edit", "create_response", "create_run",
                "create_speech", "create_transcription", "create_voice", "list_events"), methods(off));
        assertFalse(Files.exists(off.resolve("src/apis/sse.rs")));
        assertFalse(Files.readString(off.resolve("src/apis/mod.rs")).contains("sse"));
        assertFalse(Files.readString(off.resolve("Cargo.toml")).contains("futures-core"));
        assertFalse(api(off).contains("EventStream"));
    }

    @Test
    void suffixes() {
        assertEquals("json", ContentTypeMethods.requestSuffix("application/json"));
        assertEquals("json", ContentTypeMethods.requestSuffix("application/merge-patch+json; charset=utf-8"));
        assertEquals("multipart", ContentTypeMethods.requestSuffix("multipart/form-data"));
        assertEquals("form", ContentTypeMethods.requestSuffix("application/x-www-form-urlencoded"));
        assertEquals("text", ContentTypeMethods.requestSuffix("text/plain"));
        assertEquals("bytes", ContentTypeMethods.requestSuffix("application/octet-stream"));
        assertEquals("sdp", ContentTypeMethods.requestSuffix("application/sdp"));
        assertEquals(ContentTypeMethods.Shape.STREAM, ContentTypeMethods.shape("text/event-stream; charset=utf-8"));
        assertEquals(ContentTypeMethods.Shape.TEXT, ContentTypeMethods.shape("text/plain"));
        assertEquals(ContentTypeMethods.Shape.JSON, ContentTypeMethods.shape("application/json"));
        assertEquals(ContentTypeMethods.Shape.BYTES, ContentTypeMethods.shape("audio/mpeg"));
    }

    private static OpenAPI parse() {
        return new OpenAPIParser().readLocation(Path.of(SPEC).toAbsolutePath().toString(), null, null).getOpenAPI();
    }

    /**
     * With supportMultipleResponses the stream method still returns the stream (a stream response has no other
     * success to choose from), while the other methods return {@code ResponseContent<..Success>}; the crate passes
     * clippy.
     */
    @Test
    void aStreamMethodReturnsTheStreamWithSupportMultipleResponses() throws IOException, InterruptedException {
        String api = Files.readString(multiple.resolve("src/apis/default_api.rs"));
        assertTrue(api.contains("-> Result<super::sse::EventStream<models::ResponseStreamEvent, CreateResponseError>, "
                + "Error<CreateResponseError>>"), api);
        assertTrue(api.contains("-> Result<ResponseContent<CreateResponseSuccess>, Error<CreateResponseError>>"), api);
        Assumptions.assumeTrue(cargo(multiple, 30, "--version") != null, "no cargo");
        String clippy = cargo(multiple, 900, "clippy", "--all-targets", "--", "-D", "warnings");
        assertNotNull(clippy, "clippy did not finish");
        assertTrue(clippy.contains("Finished") && !clippy.contains("error:") && !clippy.contains("error[")
                && !clippy.contains("warning:"), clippy);
    }

    /** Runs src/test/rust/event_streams.rs on the generated crate. Needs cargo; without it there is nothing to run. */
    @Test
    void whatItDoesAtRunTime() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(on, 30, "--version") != null, "no cargo");
        Path manifest = on.resolve("Cargo.toml");
        String cargo = Files.readString(manifest);
        if (!cargo.contains("[dev-dependencies]")) {
            Files.writeString(manifest, cargo + """

                    [dev-dependencies]
                    futures-util = "0.3"
                    tokio = { version = "1", features = ["rt-multi-thread", "macros", "net", "io-util", "time"] }
                    """);
        }
        Files.createDirectories(on.resolve("tests"));
        Files.copy(Path.of("src/test/rust/event_streams.rs"), on.resolve("tests/event_streams.rs"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        String output = cargo(on, 900, "test", "--test", "event_streams");
        assertNotNull(output, "cargo did not finish");
        assertTrue(output.contains("test result: ok.") && !output.contains(" 0 passed"), output);
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
