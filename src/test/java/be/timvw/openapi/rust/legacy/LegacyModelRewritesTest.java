package be.timvw.openapi.rust.legacy;

import be.timvw.openapi.rust.TimvwRustCodegen;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * legacyModelRewrites runs the ported pipeline after generation. The byte-for-byte equivalence with the scripts is
 * checked by {@link LegacyOracleTest} against the client's snapshots; this checks the wiring on a small spec.
 */
class LegacyModelRewritesTest {

    @TempDir
    Path output;

    private Path generate(String name, boolean legacy) {
        Path dir = output.resolve(name);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "legacy");
        props.put(TimvwRustCodegen.LEGACY_MODEL_REWRITES, String.valueOf(legacy));
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/task20.yaml").toAbsolutePath().toString())
                .setOutputDir(dir.toString())
                .setAdditionalProperties(props);
        ClientOptInput input = configurator.toClientOptInput();
        new DefaultGenerator().opts(input).generate();
        return dir;
    }

    @Test
    void modelUnionsLeavesOutTheUnionRewrites() {
        List<String> scripts = LegacyModelRewrites.pipeline(true, true).stream().map(LegacyModelRewrites.Named::script)
                .toList();
        for (String script : List.of("fix_empty_enums.py", "fix_enum_attributes.py", "fix_tool_enum_tagging.py",
                "fix_untagged_unions.py", "fix_helper_impl_mismatches.py", "fix_generator_artifacts.py")) {
            assertFalse(scripts.contains(script), script);
            assertTrue(LegacyModelRewrites.pipeline(true, false).stream().anyMatch(s -> s.script().equals(script)), script);
        }
        assertTrue(scripts.contains("fix_generated_code.py"));
        assertTrue(scripts.contains("fix_default_issues.py"));
        assertTrue(scripts.contains("fix_unused_imports.py"));
    }

    @Test
    void rewritesRunOnlyWithTheOption() throws IOException {
        String stock = Files.readString(generate("stock", false).resolve("src/models/text_part.rs"));
        assertFalse(stock.contains("impl std::fmt::Display for TextPart"), stock);
        String legacy = Files.readString(generate("legacy", true).resolve("src/models/text_part.rs"));
        // fix_generated_code: structs get a JSON Display impl.
        assertTrue(legacy.contains("impl std::fmt::Display for TextPart"), legacy);
    }
}
