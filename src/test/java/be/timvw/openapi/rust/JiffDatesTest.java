package be.timvw.openapi.rust;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code dateLibrary=jiff} on every test spec with a {@code date} or {@code date-time}: in models, parameters,
 * typed response headers, union variants, error responses and the server. The crate depends on jiff, not chrono, and
 * builds (with every feature, so the server and the client both). What goes on the wire is in
 * {@link DateParamsTest#whatItDoesAtRunTimeWithJiff}.
 */
class JiffDatesTest {

    @TempDir
    Path out;

    /** One target directory for every case, so the dependencies build once. */
    @TempDir
    static Path target;

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "cookie_params.yaml,''",
            "date_time_error_only.yaml,''",
            "null_branch_params.yaml,''",
            "request_bodies.yaml,''",
            "response_headers.yaml,responseFunctions=true",
            "schema_mapping.yaml,''",
            "union_params.yaml,''",
            "server.yaml,targets=client;server",
            "server.yaml,targets=server",
    })
    void buildsWithJiff(String spec, String options) throws IOException, InterruptedException {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "jiff_dates_fixture");
        props.put(RustCodegenBase.DATE_LIBRARY, "jiff");
        for (String option : options.isEmpty() ? new String[0] : options.split(",")) {
            String key = option.substring(0, option.indexOf('='));
            String value = option.substring(option.indexOf('=') + 1);
            props.put(key, key.equals(TimvwRustCodegen.TARGETS) ? List.of(value.split(";")) : value);
        }
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources", spec).toAbsolutePath().toString())
                .setOutputDir(out.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();

        String cargoToml = Files.readString(out.resolve("Cargo.toml"));
        assertTrue(cargoToml.contains("jiff = "), cargoToml);
        assertFalse(cargoToml.contains("chrono"), cargoToml);

        Assumptions.assumeTrue(cargo(30, "--version") != null, "no cargo");
        String check = cargo(900, "check", "--all-features");
        assertNotNull(check, "cargo did not finish");
        assertTrue(check.contains("Finished") && !check.contains("error:") && !check.contains("error["), check);
        assertFalse(check.contains("warning:"), check);
    }

    /** What cargo printed, or {@code null} when it cannot run or does not finish in time. */
    private String cargo(long seconds, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("cargo"));
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile("cargo", ".log");
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(out.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            builder.environment().put("CARGO_TARGET_DIR", target.toString());
            process = builder.start();
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
