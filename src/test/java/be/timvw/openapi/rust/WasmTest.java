package be.timvw.openapi.rust;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebAssembly ({@code wasm32-unknown-unknown}): a tower client builds there with its default features, with and
 * without {@code apiFeatures}: the runtime drops {@code Send} there ({@code MaybeSend}), leaves out tokio and files,
 * and its timers are the JavaScript host's. Typed event streams take a {@code MaybeSend} body and have an idle timeout,
 * file parameters are {@code Upload}s (bytes and streams; a path fails when sent), and uuid draws randomness from the
 * host. Building needs cargo and the {@code wasm32-unknown-unknown} target; without them only the files are checked.
 */
class WasmTest {

    @TempDir
    Path out;

    private void generate(String spec, String packageName, boolean apiFeatures) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", packageName);
        props.put(TimvwRustCodegen.MULTIPART_HELPER, "true");
        props.put(TimvwRustCodegen.TYPED_EVENT_STREAMS, "true");
        props.put(TimvwRustCodegen.CONTENT_TYPE_METHODS, "true");
        if (apiFeatures) {
            props.put(TimvwRustCodegen.API_FEATURES, "true");
        }
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(spec).toAbsolutePath().toString())
                .setOutputDir(out.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    @ParameterizedTest
    @CsvSource({
        "test-specs/petstore.yaml, wasm_petstore, false",
        "test-specs/petstore.yaml, wasm_petstore_features, true",
        "src/test/resources/event_streams.yaml, wasm_event_streams, false",
        "src/test/resources/event_streams.yaml, wasm_event_streams_features, true",
        "src/test/resources/uploads.yaml, wasm_uploads, false",
        "src/test/resources/uploads.yaml, wasm_uploads_features, true",
        "src/test/resources/multipart.yaml, wasm_multipart, false",
        "src/test/resources/multipart.yaml, wasm_multipart_features, true",
    })
    void buildsForWasm32(String spec, String packageName, boolean apiFeatures) throws IOException, InterruptedException {
        generate(spec, packageName, apiFeatures);
        String cargoToml = Files.readString(out.resolve("Cargo.toml"));
        if (Files.exists(out.resolve("src/apis/sse.rs"))) {
            String sse = Files.readString(out.resolve("src/apis/sse.rs"));
            assertTrue(sse.contains("S: Stream<Item = Result<B, timvw_rust_runtime::Error>> + MaybeSend + 'static,"), sse);
            assertTrue(sse.contains("pub fn idle_timeout(self, limit: std::time::Duration) -> Self {"), sse);
            assertTrue(cargoToml.contains("\"time\"]"), cargoToml);
        }
        if (cargoToml.contains("uuid = ")) {
            assertTrue(cargoToml.contains("[target.'cfg(all(target_family = \"wasm\", target_os = \"unknown\"))'"
                    + ".dependencies]\nuuid = { version = \"^1.8\", features = [\"js\"] }"), cargoToml);
        }

        Assumptions.assumeTrue(cargo(30, "--version") != null, "no cargo");
        String sysroot = command(30, "rustc", "--print", "sysroot");
        Assumptions.assumeTrue(sysroot != null
                && Files.isDirectory(Path.of(sysroot.trim(), "lib", "rustlib", "wasm32-unknown-unknown")),
                "no wasm32-unknown-unknown target (rustup target add wasm32-unknown-unknown)");
        String output = cargo(900, "check", "--target", "wasm32-unknown-unknown");
        assertNotNull(output, "cargo did not finish");
        assertTrue(output.contains("Finished"), output);
        assertFalse(output.contains("warning:"), output);
    }

    private String cargo(long seconds, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("cargo"));
        command.addAll(List.of(arguments));
        return command(seconds, command.toArray(String[]::new));
    }

    private String command(long seconds, String... command) throws IOException, InterruptedException {
        Path log = Files.createTempFile("cargo", ".log");
        Process process;
        try {
            process = new ProcessBuilder(command).directory(out.toFile()).redirectErrorStream(true)
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
