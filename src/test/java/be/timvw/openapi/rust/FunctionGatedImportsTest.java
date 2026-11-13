package be.timvw.openapi.rust;

import org.junit.jupiter.api.Assumptions;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code apiFeatures} with a group that takes part of a tag: the tag's module gates its functions one by one, so a
 * feature set can compile none of the functions that use an import. The module allows unused imports itself, so a crate that keeps a lib.rs of its own, without {@code #![allow(unused_imports)]}, builds without
 * warnings.
 */
class FunctionGatedImportsTest {

    private static final String SPEC = "src/test/resources/api_features_gated.yaml";
    private static final String ALLOW = "#![allow(unused_imports)]";

    @TempDir
    Path out;

    private void generate() throws IOException {
        Path groups = Files.writeString(Files.createTempFile("groups", ".yaml"), "admin: { paths: [/admin] }\n");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "function_gated_fixture");
        props.put(TimvwRustCodegen.API_FEATURES_FILE, groups.toString());
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(SPEC).toAbsolutePath().toString())
                .setOutputDir(out.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
        Files.delete(groups);
    }

    @Test
    void aHandWrittenLibRsNeedsNoAllow() throws IOException, InterruptedException {
        generate();
        String items = Files.readString(out.resolve("src/apis/items_api.rs"));
        assertTrue(items.contains("#[cfg(feature = \"admin\")]"), items);
        assertTrue(items.contains("\n// Functions are gated one by one (apiFeatures): a feature set may use none of an "
                + "import.\n" + ALLOW + "\n"), items);
        // Only modules whose functions are gated one by one.
        assertFalse(Files.readString(out.resolve("src/apis/configuration.rs")).contains(ALLOW));

        Assumptions.assumeTrue(cargo(30, "--version") != null, "no cargo");
        Path lib = out.resolve("src/lib.rs");
        String handWritten = Files.readString(lib).replace(ALLOW + "\n", "");
        assertFalse(handWritten.contains("unused_imports"), handWritten);
        Files.writeString(lib, handWritten);
        for (String features : List.of("rustls,admin", "rustls,items", "rustls,full")) {
            String output = cargo(900, "check", "--no-default-features", "--features", features);
            assertNotNull(output, "cargo did not finish");
            assertTrue(output.contains("Finished"), features + ":\n" + output);
            assertFalse(output.contains("warning:"), features + ":\n" + output);
        }
    }

    private String cargo(long seconds, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("cargo"));
        command.addAll(List.of(arguments));
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
