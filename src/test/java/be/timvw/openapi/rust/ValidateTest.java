package be.timvw.openapi.rust;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decisions 9 and 8: every model implements the generated {@code validation::Validate} trait, which checks the
 * validation keywords of its schema (see {@link ValidateRules}). What it does at run time is in
 * src/test/rust/validate.rs, which {@link #whatItDoesAtRunTime} runs with cargo.
 */
class ValidateTest {

    private static final String SPEC = "src/test/resources/validate.yaml";

    @TempDir
    static Path on;

    @TempDir
    static Path off;

    @TempDir
    static Path v31;

    @BeforeAll
    static void generate() {
        generate(SPEC, on, Map.of());
        generate(SPEC, off, Map.of(TimvwRustCodegen.VALIDATE_METHODS, "false"));
        generate("src/test/resources/validate31.yaml", v31, Map.of());
    }

    private static void generate(String spec, Path output, Map<String, Object> options) {
        Map<String, Object> props = new LinkedHashMap<>(options);
        props.put("packageName", "validate_fixture");
        props.put(TimvwRustCodegen.BON_BUILDERS, "false");
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(spec).toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static String read(Path output, String model) throws IOException {
        return Files.readString(output.resolve("src/models/" + model + ".rs"));
    }

    private static void assertContains(String text, String needle) {
        assertTrue(text.contains(needle), needle + " not in:\n" + text);
    }

    @Test
    void everyModelImplementsTheTrait() throws IOException {
        for (String model : List.of("request", "message", "point", "content", "shape", "circle", "square", "node",
                "holder", "validate", "validation_error")) {
            assertContains(read(on, model), "impl crate::validation::Validate for ");
        }
        String point = read(on, "point");
        assertContains(point, "impl crate::validation::Validate for Point {\n"
                + "    fn validate(&self) -> Result<(), crate::validation::ValidationError> {\n"
                + "        Ok(())\n    }\n}");
        String lib = Files.readString(on.resolve("src/lib.rs"));
        assertContains(lib, "pub mod validation;");
        String validation = Files.readString(on.resolve("src/validation.rs"));
        assertContains(validation, "pub trait Validate {\n");
        assertContains(validation, "pub struct ValidationError {");
        assertContains(validation, "impl std::error::Error for ValidationError {}");
    }

    @Test
    void stringsNumbersListsAndMapsAreChecked() throws IOException {
        String request = read(on, "request");
        assertContains(request, "crate::validation::items(self.messages.len(), Some(1), Some(3))"
                + ".map_err(|e| e.at(\"messages\"))?;");
        assertContains(request, "crate::validation::exclusive_maximum(self.count, 100).map_err(|e| e.at(\"count\"))?;");
        assertContains(request, "if let Some(Some(value0)) = &self.temperature {\n"
                + "            crate::validation::minimum(*value0, 0.0).map_err(|e| e.at(\"temperature\"))?;");
        assertContains(request, "crate::validation::multiple_of(*value0, 0.1)");
        assertContains(request, "crate::validation::unique_items(value0).map_err(|e| e.at(\"tags\"))?;");
        assertContains(request, "crate::validation::length(item1, None, Some(5))"
                + ".map_err(|e| e.at_index(index1).at(\"tags\"))?;");
        assertContains(request, "crate::validation::properties(value0.len(), Some(1), Some(2))");
        assertContains(request, ".map_err(|e| e.at_key(key1).at(\"metadata\"))?;");
        assertContains(read(on, "message"), "crate::validation::length(&self.content, Some(1), Some(10))"
                + ".map_err(|e| e.at(\"content\"))?;");
    }

    @Test
    void nestedModelsAreChecked() throws IOException {
        String request = read(on, "request");
        // holder has no constraints of its own, but holds a message
        assertContains(request, "crate::validation::Validate::validate(&self.holder).map_err(|e| e.at(\"holder\"))?;");
        assertContains(read(on, "holder"), "crate::validation::Validate::validate(&self.inner)");
        // a model whose validate() cannot fail is not called
        assertFalse(read(on, "holder").contains("&self.plain"), read(on, "holder"));
        assertContains(read(on, "node"), "crate::validation::Validate::validate(&self.next)");
    }

    @Test
    void unionsCheckTheActiveVariant() throws IOException {
        String content = read(on, "content");
        assertContains(content, "match self {\n            Self::Text(value) => {\n"
                + "                crate::validation::length(value, None, Some(4))?;");
        assertContains(content, "Self::ArrayOfMessages(value) => {\n"
                + "                crate::validation::Validate::validate(value)?;");
        // only Circle has something to check, and Unknown catches the rest
        assertContains(read(on, "shape"), "if let Self::Circle(value) = self {");
    }

    @Test
    void whatHasNothingToCheckIsSkipped() throws IOException {
        String request = read(on, "request");
        assertFalse(request.contains("self.payload"), request);
        assertFalse(request.contains("self.points.len()"), request);
        assertFalse(request.contains("self.lookahead"), request);
        assertContains(request, "crate::validation::items(value0.len(), Some(1), None).map_err(|e| e.at(\"colors\"))?;");
    }

    @Test
    void aPatternIsMatchedWithARegexCompiledOnce() throws IOException {
        String message = read(on, "message");
        assertContains(message, "crate::validation::pattern(value0, &PATTERN_0, r\"^[a-z]+\\d*$\")");
        assertContains(message, "static PATTERN_0: std::sync::LazyLock<Option<regex::Regex>> = "
                + "std::sync::LazyLock::new(|| regex::Regex::new(r\"^[a-z]+\\d*$\").ok());");
        assertContains(Files.readString(on.resolve("Cargo.toml")), "regex = ");
        assertContains(Files.readString(on.resolve("src/validation.rs")), "pub fn pattern(");
    }

    @Test
    void withoutAPatternThereIsNoRegexDependency() throws IOException {
        assertFalse(Files.readString(v31.resolve("Cargo.toml")).contains("regex"));
        assertFalse(Files.readString(v31.resolve("src/validation.rs")).contains("regex::"));
    }

    @Test
    void openApi31ExclusiveBoundsAreNumbers() throws IOException {
        String range = read(v31, "range");
        assertContains(range, "crate::validation::exclusive_minimum(self.low, 0.0).map_err(|e| e.at(\"low\"))?;");
        assertContains(range, "crate::validation::exclusive_maximum(self.low, 10.0).map_err(|e| e.at(\"low\"))?;");
        assertContains(range, "crate::validation::exclusive_minimum(*value0, 1).map_err(|e| e.at(\"count\"))?;");
        assertContains(range, "if let Some(Some(value0)) = &self.label {\n"
                + "            crate::validation::length(value0, None, Some(3))");
    }

    @Test
    void constraintsAreDocumented() throws IOException {
        String request = read(on, "request");
        assertContains(request, "/// Validation: `minItems: 1`, `maxItems: 3`.\n    #[serde(rename = \"messages\")]");
        assertContains(request, "/// Validation: `minimum: 1`, `exclusiveMaximum: 100`, `multipleOf: 5`.");
        assertContains(request, "/// Validation: `uniqueItems: true`; each item: `maxLength: 5`.");
        assertContains(request, "/// Validation: `minProperties: 1`, `maxProperties: 2`; each value: `maxLength: 3`.");
        assertContains(request, "/// Validation: each item: `minItems: 1`; each item of an item: `minLength: 1`.");
        assertFalse(read(on, "holder").contains("/// Validation"), read(on, "holder"));
        assertContains(read(off, "request"), "/// Validation: `minItems: 1`, `maxItems: 3`.");
    }

    @Test
    void theOptionTurnsTheGenerationOff() throws IOException {
        assertFalse(Files.exists(off.resolve("src/validation.rs")));
        assertFalse(Files.readString(off.resolve("src/lib.rs")).contains("validation"));
        assertFalse(Files.readString(off.resolve("Cargo.toml")).contains("regex"));
        try (Stream<Path> files = Files.list(off.resolve("src/models"))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                assertFalse(Files.readString(file).contains("crate::validation"), file.toString());
            }
        }
    }

    @Test
    void literals() {
        assertEquals("0.0", ValidateRules.floatLiteral(new BigDecimal("0")));
        assertEquals("2.0", ValidateRules.floatLiteral(new BigDecimal("2.00")));
        assertEquals("0.1", ValidateRules.floatLiteral(new BigDecimal("0.10")));
        assertEquals("100.0", ValidateRules.floatLiteral(new BigDecimal("1E+2")));
        assertEquals("r\"a\"", ValidateRules.rawString("a"));
        assertEquals("r#\"a\"b\"#", ValidateRules.rawString("a\"b"));
        assertEquals("\"a\\\"b\"", ValidateRules.quote("a\"b"));
    }

    /** Runs src/test/rust/validate.rs on the generated models. Needs cargo; without it there is nothing to run. */
    @Test
    void whatItDoesAtRunTime(@TempDir Path crate) throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(crate, 30, "--version") != null, "no cargo");
        Files.writeString(crate.resolve("Cargo.toml"), """
                [package]
                name = "validate_fixture"
                version = "0.0.0"
                edition = "2021"

                [dependencies]
                regex = "1"
                serde = { version = "1", features = ["derive"] }
                serde_json = "1"
                serde_repr = "0.1"
                serde_with = { version = "3", default-features = false, features = ["base64", "std", "macros"] }
                """);
        Files.createDirectories(crate.resolve("src/models"));
        Files.createDirectories(crate.resolve("tests"));
        Files.writeString(crate.resolve("src/lib.rs"), "pub mod models;\npub mod validation;\n");
        Files.copy(on.resolve("src/validation.rs"), crate.resolve("src/validation.rs"));
        try (Stream<Path> files = Files.list(on.resolve("src/models"))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Files.copy(file, crate.resolve("src/models").resolve(file.getFileName()));
            }
        }
        Files.copy(Path.of("src/test/rust/validate.rs"), crate.resolve("tests/validate.rs"));
        String output = cargo(crate, 600, "test", "--test", "validate");
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
