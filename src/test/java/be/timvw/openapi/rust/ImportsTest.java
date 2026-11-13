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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Imports}: a generated file imports only what it uses, which replaced the unused-import pruning of the
 * removed {@code legacyModelRewrites}. The goldens hold the {@code use} lines of every file of
 * {@code src/test/resources/imports.yaml}; to update, run with {@code -DargLine=-Dgolden.update=true} and read the
 * diff.
 */
class ImportsTest {

    private static final String SPEC = "src/test/resources/imports.yaml";
    private static final Path GOLDEN = Path.of("src/test/resources/imports_golden");

    @TempDir
    static Path helper;

    @TempDir
    static Path stock;

    @BeforeAll
    static void generate() {
        generate(helper, true);
        generate(stock, false);
    }

    private static void generate(Path output, boolean multipartHelper) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "imports");
        if (multipartHelper) {
            props.put(TimvwRustCodegen.MULTIPART_HELPER, "true");
        }
        props.put(TimvwRustCodegen.TYPED_EVENT_STREAMS, "true");
        ClientOptInput input = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(SPEC).toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props)
                .toClientOptInput();
        new DefaultGenerator().opts(input).generate();
    }

    /** The top-level {@code use} lines of a generated file. */
    private static String uses(Path output, String file) throws IOException {
        return Files.readString(output.resolve(file)).lines()
                .filter(l -> l.startsWith("use "))
                .collect(Collectors.joining("\n", "", "\n"));
    }

    private static void golden(String name, String generated) throws IOException {
        Path golden = GOLDEN.resolve(name + ".txt");
        if (Boolean.getBoolean("golden.update")) {
            Files.createDirectories(GOLDEN);
            Files.writeString(golden, generated);
        }
        assertEquals(Files.readString(golden), generated, name);
    }

    @Test
    void aModelImportsCrateModelsOnlyWhenItRefersToAnotherModel() throws IOException {
        StringBuilder all = new StringBuilder();
        for (String model : List.of("plain", "color", "holder", "text_or_number", "plain_or_text", "cat", "pet")) {
            all.append("== ").append(model).append('\n').append(uses(helper, "src/models/" + model + ".rs"));
        }
        golden("models", all.toString());
    }

    @Test
    void anApiModuleImportsWhatItsOperationsUse() throws IOException {
        StringBuilder all = new StringBuilder();
        for (Path output : List.of(helper, stock)) {
            all.append("== multipartHelper ").append(output == helper).append('\n');
            for (String api : List.of("read_api", "gone_api", "uploads_api", "raw_api")) {
                all.append("-- ").append(api).append('\n').append(uses(output, "src/apis/" + api + ".rs"));
            }
        }
        golden("apis", all.toString());
    }

    @Test
    void withoutAnEventStreamNoSseModuleIsWritten() throws IOException {
        // typedEventStreams is on, but no operation has a text/event-stream response.
        assertFalse(Files.exists(helper.resolve("src/apis/sse.rs")));
        String mod = Files.readString(helper.resolve("src/apis/mod.rs"));
        assertFalse(mod.contains("mod sse"), mod);
        String cargo = Files.readString(helper.resolve("Cargo.toml"));
        assertFalse(cargo.contains("futures-core"), cargo);
    }

    @Test
    void theRemovedLegacyOptionFails() {
        for (String value : List.of("true", "false", "")) {
            TimvwRustCodegen c = new TimvwRustCodegen();
            c.additionalProperties().put(TimvwRustCodegen.LEGACY_MODEL_REWRITES, value);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, c::processOpts);
            assertTrue(e.getMessage().contains("legacyModelRewrites was removed"), e.getMessage());
        }
    }
}
