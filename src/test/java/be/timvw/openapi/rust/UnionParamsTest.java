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

    @TempDir
    static Path out31;

    @BeforeAll
    static void generate() {
        generate(SPEC, out);
        generate("src/test/resources/union_params_31.yaml", out31);
    }

    private static void generate(String spec, Path output) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "union_params_fixture");
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(spec).toAbsolutePath().toString())
                .setOutputDir(output.toString())
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

    @Test
    void uuidAndDateVariantsPrintTheirText() throws IOException {
        String ref = Files.readString(out.resolve("src/models/model_ref.rs"));
        assertTrue(ref.contains("Self::Text(value) => write!(f, \"{}\", value),"), ref);
        assertTrue(ref.contains("Self::Text2(value) => write!(f, \"{}\", value.to_rfc3339()),"), ref);
        assertTrue(ref.contains("Self::Text3(value) => write!(f, \"{}\", value),"), ref);
    }

    /** Only a union holds a uuid and a date-time here: the crate depends on uuid and chrono all the same. */
    @Test
    void aUnionVariantMakesTheCrateDependOnItsCrate() throws IOException {
        String cargo = Files.readString(out.resolve("Cargo.toml"));
        assertTrue(cargo.contains("\nuuid = ") && cargo.contains("\nchrono = "), cargo);
    }

    /** A required parameter of a nullable union (nullable: true, or a null branch in 3.1) takes an Option. */
    @Test
    void aRequiredNullableUnionParameterIsAnOption() throws IOException {
        assertTrue(api().contains("maybe: Option<models::NullableMixed>"), api());
        assertTrue(api().contains("if let Some(ref param_value) = p_query_maybe {"), api());
        String api31 = Files.readString(out31.resolve("src/apis/default_api.rs"));
        assertTrue(api31.contains("maybe: Option<models::MaybeMixed>"), api31);
        assertTrue(api31.contains("req_builder = req_builder.query(&[(\"maybe\", &param_value.to_string())]);"), api31);
    }

    /** Two properties that become the same Rust field fail generation, naming both. */
    @Test
    void twoPropertiesWithTheSameRustNameFail(@TempDir Path collision) {
        RuntimeException e = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> generate("src/test/resources/field_collision.yaml", collision));
        StringBuilder messages = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        assertTrue(messages.toString().contains("model Thing: properties 'foo-bar' and 'foo_bar' both become the Rust "
                + "field foo_bar"), messages.toString());
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
        String check = cargo(out31, 900, "check");
        assertNotNull(check, "cargo did not finish");
        assertTrue(check.contains("Finished") && !check.contains("error:") && !check.contains("error["), check);
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
