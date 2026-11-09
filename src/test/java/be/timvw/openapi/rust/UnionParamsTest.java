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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parameters whose type is a oneOf union, on src/test/resources/union_params.yaml: they go out as the union's
 * {@code Display}, so a scalar variant is its plain text (a string unquoted) and any other variant its JSON. What
 * goes on the wire is in src/test/rust/union_params.rs, which {@link #whatItDoesAtRunTime} runs with cargo.
 */
class UnionParamsTest {

    private static final String SPEC = "src/test/resources/union_params.yaml";

    @TempDir
    static Path out;

    @BeforeAll
    static void generate() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "union_params_fixture");
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(SPEC).toAbsolutePath().toString())
                .setOutputDir(out.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static String api() throws IOException {
        return Files.readString(out.resolve("src/apis/default_api.rs"));
    }

    @Test
    void aUnionIsSentAsItsDisplayNotItsJson() throws IOException {
        String api = api();
        assertTrue(api.contains("req_builder = req_builder.query(&[(\"filter\", &param_value.to_string())]);"), api);
        assertTrue(api.contains("req_builder = req_builder.query(&[(\"mixed\", &p_query_mixed.to_string())]);"), api);
        assertTrue(api.contains("status=crate::apis::urlencode(p_path_status.to_string())"), api);
        assertTrue(api.contains("req_builder.header(\"X-Status\", param_value.to_string());"), api);
        assertFalse(api.contains("serde_json::to_string(param_value)"), api);
    }

    /** Runs src/test/rust/union_params.rs on the generated crate. Needs cargo; without it there is nothing to run. */
    @Test
    void whatItDoesAtRunTime() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(out, 30, "--version") != null, "no cargo");
        Path manifest = out.resolve("Cargo.toml");
        String cargo = Files.readString(manifest);
        if (!cargo.contains("[dev-dependencies]")) {
            Files.writeString(manifest, cargo + """

                    [dev-dependencies]
                    tokio = { version = "1", features = ["rt-multi-thread", "macros", "net", "io-util"] }
                    """);
        }
        Files.createDirectories(out.resolve("tests"));
        Files.copy(Path.of("src/test/rust/union_params.rs"), out.resolve("tests/union_params.rs"),
                StandardCopyOption.REPLACE_EXISTING);
        String output = cargo(out, 900, "test", "--test", "union_params");
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
