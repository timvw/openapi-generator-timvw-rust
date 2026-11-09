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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Rust examples of README.md and docs/guides/*.md compile. Each {@code ```rust} block of those files, unless the
 * line before it is an {@code <!-- not compiled: reason -->} comment, must appear in src/test/rust/docs_examples.rs,
 * line for line (indentation aside). {@link #theExamplesCompileAndRun} generates
 * src/test/resources/docs_examples.yaml as {@code my_client} and runs that file and clippy on it with cargo.
 */
class DocsExamplesTest {

    private static final Path EXAMPLES = Path.of("src/test/rust/docs_examples.rs");

    @TempDir
    static Path out;

    @BeforeAll
    static void generate() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "my-client");
        props.put(TimvwRustCodegen.RESPONSE_FUNCTIONS, "true");
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/docs_examples.yaml").toAbsolutePath().toString())
                .setOutputDir(out.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    /** A {@code ```rust} block of a documentation file. */
    record Block(Path file, int line, List<String> lines) {
    }

    static List<Path> documents() throws IOException {
        List<Path> files = new ArrayList<>(List.of(Path.of("README.md")));
        try (Stream<Path> guides = Files.list(Path.of("docs/guides"))) {
            guides.filter(p -> p.toString().endsWith(".md")).sorted().forEach(files::add);
        }
        return files;
    }

    /** The {@code ```rust} blocks of a file that are meant to compile. */
    static List<Block> compiledBlocks(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).strip().equals("```rust")) {
                continue;
            }
            int start = i;
            List<String> body = new ArrayList<>();
            for (i++; i < lines.size() && !lines.get(i).strip().equals("```"); i++) {
                body.add(lines.get(i));
            }
            String before = start > 0 ? lines.get(start - 1).strip() : "";
            if (!before.startsWith("<!-- not compiled:")) {
                blocks.add(new Block(file, start + 1, normalized(body)));
            }
        }
        return blocks;
    }

    private static List<String> normalized(List<String> lines) {
        return lines.stream().map(String::strip).filter(l -> !l.isEmpty()).collect(Collectors.toList());
    }

    private static boolean containsRun(List<String> haystack, List<String> needle) {
        outer:
        for (int i = 0; i + needle.size() <= haystack.size(); i++) {
            for (int j = 0; j < needle.size(); j++) {
                if (!haystack.get(i + j).equals(needle.get(j))) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Test
    void everyRustExampleIsInTheCompiledFile() throws IOException {
        List<String> compiled = normalized(Files.readAllLines(EXAMPLES));
        List<String> missing = new ArrayList<>();
        int count = 0;
        for (Path file : documents()) {
            for (Block block : compiledBlocks(file)) {
                count++;
                if (!containsRun(compiled, block.lines())) {
                    missing.add(block.file() + ":" + block.line());
                }
            }
        }
        assertTrue(count > 20, "only " + count + " examples found");
        assertEquals(List.of(), missing, "examples not in " + EXAMPLES + " (copy them there, or mark them "
                + "<!-- not compiled: reason -->)");
    }

    @Test
    void theCheckFindsAnExampleThatIsNotThere(@TempDir Path dir) throws IOException {
        Path doc = dir.resolve("doc.md");
        Files.writeString(doc, "text\n\n```rust\nlet x = 1;\n```\n\n<!-- not compiled: a sketch -->\n```rust\nnope\n```\n");
        List<Block> blocks = compiledBlocks(doc);
        assertEquals(1, blocks.size());
        assertEquals(List.of("let x = 1;"), blocks.get(0).lines());
        assertFalse(containsRun(List.of("let x = 2;"), blocks.get(0).lines()));
    }

    /** Runs src/test/rust/docs_examples.rs and clippy on the generated crate. Needs cargo. */
    @Test
    void theExamplesCompileAndRun() throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(out, 30, "--version") != null, "no cargo");
        Path manifest = out.resolve("Cargo.toml");
        String cargo = Files.readString(manifest);
        if (!cargo.contains("[dev-dependencies]")) {
            Files.writeString(manifest, cargo + """

                    [dev-dependencies]
                    serde_path_to_error = "0.1"
                    tokio = { version = "1", features = ["rt-multi-thread", "macros"] }
                    """);
        }
        Files.createDirectories(out.resolve("tests"));
        Files.copy(EXAMPLES, out.resolve("tests/docs_examples.rs"), StandardCopyOption.REPLACE_EXISTING);
        String test = cargo(out, 900, "test", "--test", "docs_examples");
        assertNotNull(test, "cargo did not finish");
        assertTrue(test.contains("test result: ok.") && !test.contains(" 0 passed"), test);
        String clippy = cargo(out, 900, "clippy", "--all-targets", "--", "-D", "warnings");
        assertNotNull(clippy, "clippy did not finish");
        assertTrue(clippy.contains("Finished") && !clippy.contains("warning:"), clippy);
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
