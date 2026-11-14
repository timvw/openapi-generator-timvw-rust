package be.timvw.openapi.rust;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code Schema.property} options {@code stringProperties} and {@code fileProperties}: an entry that names no
 * property fails generation (as {@code optionalProperties} does), and a CLI value keeps the underscores of
 * snake_case property names.
 */
class PropertyEntriesTest {

    private static final String SPEC = "src/test/resources/optional_properties.yaml";

    private static void generate(Path output, String option, Object value) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "property_entries");
        props.put(option, value);
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(SPEC).toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && !(cause instanceof IllegalArgumentException)) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Reply.nope", "Nope.id"})
    void anUnknownStringPropertiesEntryFailsGeneration(String entry, @TempDir Path out) {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> generate(out, TimvwRustCodegen.STRING_PROPERTIES, entry));
        assertEquals("timvw-rust: stringProperties: " + entry + " names no property of a component schema "
                + "(nor of its inline allOf parts); remove it or correct its name", rootMessage(e));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Reply.nope", "Nope.id"})
    void anUnknownFilePropertiesEntryFailsGeneration(String entry, @TempDir Path out) {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> generate(out, TimvwRustCodegen.FILE_PROPERTIES, entry));
        assertEquals("timvw-rust: fileProperties: " + entry + " names no property of a component schema; "
                + "remove it or correct its name", rootMessage(e));
    }

    @Test
    void aCliStringPropertiesValueKeepsUnderscores(@TempDir Path out) throws IOException {
        // A CLI value is one string; cache_write_tokens is a property of an inline allOf part of Usage.
        generate(out, TimvwRustCodegen.STRING_PROPERTIES, "Usage.cache_write_tokens");
        String usage = Files.readString(out.resolve("src/models/usage.rs"));
        assertTrue(usage.contains("pub cache_write_tokens: String"), usage);
    }
}
