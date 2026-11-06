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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unions openai-client-base rendered as {@code serde_json::Value} through the removed {@code jsonValueSchemas}
 * (the response output items, computer actions, and the streaming and realtime event unions) render as typed enums with
 * {@code modelUnions}, {@code unionDispatch} and {@code openEnums}. What the generated code does at run time is in
 * src/test/rust/event_unions.rs, which {@link #whatItDoesAtRunTime} runs with cargo.
 */
class EventUnionsTest {

    private static final String SPEC = "src/test/resources/event_unions.yaml";

    @TempDir
    static Path on;

    @BeforeAll
    static void generate() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "event_unions_fixture");
        props.put(TimvwRustCodegen.BON_BUILDERS, "false");
        props.put(TimvwRustCodegen.BOX_ONLY_CYCLES, "true");
        props.put(TimvwRustCodegen.DEFAULT_ONLY_WHERE_VALID, "true");
        props.put(TimvwRustCodegen.INTEGERS_AS_I64, "true");
        props.put(TimvwRustCodegen.QUALIFIED_INLINE_NAMES, "true");
        props.put(TimvwRustCodegen.ENUM_FROM_STR, "true");
        props.put(TimvwRustCodegen.MODEL_UNIONS, "true");
        props.put(TimvwRustCodegen.UNION_DISPATCH, "true");
        props.put(TimvwRustCodegen.OPEN_ENUMS, "true");
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(SPEC).toAbsolutePath().toString())
                .setOutputDir(on.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static String read(String model) throws IOException {
        return Files.readString(on.resolve("src/models/" + model + ".rs"));
    }

    @Test
    void theFieldsHoldingThemAreTyped() throws IOException {
        String response = read("response");
        assertTrue(response.contains("pub output: Vec<models::OutputItem>,"), response);
        String call = read("computer_call");
        assertTrue(call.contains("pub action: Option<models::ComputerAction>,"), call);
        assertTrue(call.contains("pub actions: Option<Vec<models::ComputerAction>>,"), call);
        String added = read("response_output_item_added_event");
        assertTrue(added.contains("pub item: models::OutputItem,"), added);
        for (String model : List.of("response", "computer_call", "response_output_item_added_event")) {
            assertFalse(read(model).contains("serde_json::Value"), model);
        }
    }

    @Test
    void eachDecodesByItsTag() throws IOException {
        for (String union : List.of("output_item", "computer_action", "response_stream_event",
                "transcription_stream_event", "realtime_server_event")) {
            String source = read(union);
            assertTrue(source.contains("#[serde(untagged)]"), union);
            assertTrue(source.contains("const TAG: &str = \"type\";"), union);
        }
        String items = read("output_item");
        assertTrue(items.contains("Tag::Value(\"function_call_output\") => "), items);
        assertTrue(items.contains("FunctionCallOutputResource(models::FunctionCallOutputResource)"), items);
        String events = read("response_stream_event");
        assertTrue(events.contains("Tag::Value(\"response.output_item.added\") => "), events);
    }

    @Test
    void whatAResponseHoldsGetsACatchAllAndAWebSocketEventDoesNot() throws IOException {
        for (String union : List.of("output_item", "computer_action", "response_stream_event",
                "transcription_stream_event")) {
            assertTrue(read(union).contains("    Unknown(serde_json::Value),\n"), union);
        }
        String realtime = read("realtime_server_event");
        assertFalse(realtime.contains("Unknown(serde_json::Value)"), realtime);
        assertTrue(realtime.contains("Tag::Value(other) => Err(dispatch::unknown_tag(UNION, TAG, other)),"), realtime);
    }

    /** Runs src/test/rust/event_unions.rs on the generated models. Needs cargo; without it there is nothing to run. */
    @Test
    void whatItDoesAtRunTime(@TempDir Path crate) throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(crate, 30, "--version") != null, "no cargo");
        Files.writeString(crate.resolve("Cargo.toml"), """
                [package]
                name = "event_unions_fixture"
                version = "0.0.0"
                edition = "2021"

                [dependencies]
                serde = { version = "1", features = ["derive"] }
                serde_json = "1"
                serde_repr = "0.1"
                serde_with = { version = "3", default-features = false, features = ["base64", "std", "macros"] }
                """);
        Files.createDirectories(crate.resolve("src/models"));
        Files.createDirectories(crate.resolve("tests"));
        Files.writeString(crate.resolve("src/lib.rs"), "#![allow(clippy::all)]\npub mod models;\n");
        try (Stream<Path> files = Files.list(on.resolve("src/models"))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Files.copy(file, crate.resolve("src/models").resolve(file.getFileName()));
            }
        }
        Files.copy(Path.of("src/test/rust/event_unions.rs"), crate.resolve("tests/event_unions.rs"));
        String output = cargo(crate, 600, "test", "--test", "event_unions");
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
