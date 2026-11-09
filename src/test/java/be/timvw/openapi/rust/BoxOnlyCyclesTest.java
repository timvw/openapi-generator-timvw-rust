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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Milestone 3.1: a model reference is boxed only where it closes a cycle. */
class BoxOnlyCyclesTest {

    @TempDir
    static Path cycles;

    @BeforeAll
    static void generate() {
        generate(cycles);
    }

    private static void generate(Path output) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "cycles");
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/cycles.yaml").toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props);
        ClientOptInput input = configurator.toClientOptInput();
        new DefaultGenerator().opts(input).generate();
    }

    private static String read(Path output, String model) throws IOException {
        return Files.readString(output.resolve("src/models/" + model + ".rs"));
    }

    @Test
    void acyclicReferencesAreHeldByValue() throws IOException {
        String tree = read(cycles, "tree");
        assertTrue(tree.contains("pub root: models::Node,"), tree);
        assertTrue(tree.contains("pub leaf: models::Leaf,"), tree);
        assertTrue(tree.contains("pub maybe_leaf: Option<Option<models::Leaf>>,"), tree);
        assertTrue(tree.contains("pub expr: Option<models::Expr>,"), tree);
        assertFalse(tree.contains("Box"), tree);
        String pong = read(cycles, "pong");
        assertTrue(pong.contains("pub leaf: Option<models::Leaf>,"), pong);
    }

    @Test
    void selfReferenceIsBoxedButNotInsideVec() throws IOException {
        String node = read(cycles, "node");
        assertTrue(node.contains("pub parent: Option<Box<models::Node>>,"), node);
        assertTrue(node.contains("pub children: Option<Vec<models::Node>>,"), node);
        assertTrue(node.contains("pub next: Option<Box<models::Node>>,"), node);
        assertTrue(node.contains("next: next.map(Box::new),"), node);
        assertTrue(node.contains("pub leaf: Option<models::Leaf>,"), node);
    }

    @Test
    void mutualRecursionIsBoxedBothWays() throws IOException {
        assertTrue(read(cycles, "ping").contains("pub pong: Option<Box<models::Pong>>,"));
        assertTrue(read(cycles, "pong").contains("pub ping: Option<Box<models::Ping>>,"));
    }

    @Test
    void cycleThroughUnionVariantIsBoxed() throws IOException {
        String expr = read(cycles, "expr");
        assertTrue(expr.contains("Neg(Box<models::Neg>),"), expr);
        assertTrue(expr.contains("Literal(models::Literal),"), expr);
        String neg = read(cycles, "neg");
        assertTrue(neg.contains("pub inner: Box<models::Expr>,"), neg);
        assertTrue(neg.contains("inner: Box::new(inner),"), neg);
    }

    @Test
    void discriminatedUnionVariantsAreHeldByValue() throws IOException {
        String shape = read(cycles, "shape");
        assertTrue(shape.contains("Circle(models::Circle),"), shape);
        assertTrue(shape.contains("Square(models::Square),"), shape);
    }
}
