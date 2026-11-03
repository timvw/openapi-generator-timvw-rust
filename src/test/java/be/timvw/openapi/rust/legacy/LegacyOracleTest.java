package be.timvw.openapi.rust.legacy;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks every ported rewrite against the snapshots of openai-client-base's scripted pipeline: the rewrite applied to
 * snapshot {@code from} must give snapshot {@code to}, byte for byte, in the compared directories.
 *
 * <p>Runs only when {@code ORACLE_DIR} is set: a folder with {@code snaps/NN-step/src} (src after each pipeline step),
 * {@code stainless.yaml} and {@code l2_rust_compatible.json}. Set {@code ORACLE_STEP} to run one step.</p>
 */
class LegacyOracleTest {

    /** Marker for the step that runs {@link LegacyModelRewrites} end to end. */
    static final class FullPipeline implements Rewrite {
        @Override
        public void apply(LegacyContext context) {
            throw new UnsupportedOperationException();
        }
    }

    record Step(String name, String from, String to, List<String> dirs, String rewriteClass) {
    }

    static final List<Step> STEPS = Arrays.asList(
            new Step("patch_generated (models)", "01-raw", "02-patch_generated", List.of("models"), "PatchGeneratedModels"),
            new Step("fix_generated_code #1", "03-fix_file_upload_todos", "04-fix_generated_code", List.of("models"), "FixGeneratedCode"),
            new Step("fix_empty_enums", "04-fix_generated_code", "05-fix_empty_enums", List.of("models"), "FixEmptyEnums"),
            new Step("fix_enum_attributes", "05-fix_empty_enums", "06-fix_enum_attributes", List.of("models"), "FixEnumAttributes"),
            new Step("fix_tool_enum_tagging", "06-fix_enum_attributes", "07-fix_tool_enum_tagging", List.of("models"), "FixToolEnumTagging"),
            new Step("fix_untagged_unions", "07-fix_tool_enum_tagging", "08-fix_untagged_unions", List.of("models"), "FixUntaggedUnions"),
            new Step("fix_helper_impl_mismatches #1", "08-fix_untagged_unions", "09-fix_helper_impl_mismatches", List.of("models"), "FixHelperImplMismatches"),
            new Step("fix_default_issues", "10-fix_simplified_type_references", "11-fix_default_issues", List.of("models"), "FixDefaultIssues"),
            new Step("fix_generated_code #2", "11-fix_default_issues", "12-fix_generated_code", List.of("models"), "FixGeneratedCode"),
            new Step("fix_generator_artifacts", "14-fix_clippy_warnings", "15-fix_generator_artifacts", List.of("models"), "FixGeneratorArtifacts"),
            new Step("fix_helper_impl_mismatches #2", "15-fix_generator_artifacts", "16-fix_helper_impl_mismatches", List.of("models"), "FixHelperImplMismatches"),
            new Step("fix_generated_code #3", "16-fix_helper_impl_mismatches", "17-fix_generated_code", List.of("models"), "FixGeneratedCode"),
            new Step("doc link fix", "18-fix_multipart_serialization", "19-perl", List.of("models", "apis"), "DocLinkFix"),
            new Step("fix_unused_imports", "19-perl", "20-fix_unused_imports", List.of("models", "apis"), "FixUnusedImports"),
            // The whole chain, as the generator runs it: models from the raw output to the scripted pipeline's final
            // (pre-fmt) state. The scripts in between only touch apis, which the templates now render.
            new Step("full pipeline", "01-raw", "20-fix_unused_imports", List.of("models"), "FullPipeline"));

    @TestFactory
    Stream<DynamicTest> rewritesReproduceTheScriptedPipeline() {
        String dir = System.getenv("ORACLE_DIR");
        Assumptions.assumeTrue(dir != null, "ORACLE_DIR not set");
        String only = System.getenv("ORACLE_STEP");
        Path oracle = Path.of(dir);
        return STEPS.stream()
                .filter(s -> only == null || s.rewriteClass().equals(only) || s.name().equals(only))
                .map(s -> DynamicTest.dynamicTest(s.name(), () -> check(oracle, s)));
    }

    private static void check(Path oracle, Step step) throws Exception {
        Class<?> type;
        try {
            type = "FullPipeline".equals(step.rewriteClass()) ? FullPipeline.class
                    : Class.forName("be.timvw.openapi.rust.legacy." + step.rewriteClass());
        } catch (ClassNotFoundException e) {
            Assumptions.abort(step.rewriteClass() + " not ported yet");
            return;
        }
        Rewrite rewrite = (Rewrite) type.getDeclaredConstructor().newInstance();
        if (rewrite instanceof FullPipeline) {
            rewrite = ctx -> LegacyModelRewrites.run(ctx, msg -> { });
        }
        Path work = Files.createTempDirectory("oracle-" + step.rewriteClass());
        copy(oracle.resolve("snaps").resolve(step.from()).resolve("src"), work.resolve("src"));
        Map<String, Object> raw = LegacyContext.load(oracle.resolve("stainless.yaml"));
        Map<String, Object> pre = LegacyContext.load(oracle.resolve("l2_rust_compatible.json"));
        rewrite.apply(new LegacyContext(work, raw, pre));
        List<String> problems = new ArrayList<>();
        for (String sub : step.dirs()) {
            compare(work.resolve("src").resolve(sub), oracle.resolve("snaps").resolve(step.to()).resolve("src").resolve(sub),
                    sub, problems);
        }
        if (!problems.isEmpty()) {
            fail(step.name() + ": " + problems.size() + " differences, first ones:\n"
                    + String.join("\n", problems.subList(0, Math.min(15, problems.size()))));
        }
    }

    private static void compare(Path actual, Path expected, String label, List<String> problems) throws IOException {
        TreeSet<String> names = new TreeSet<>();
        try (Stream<Path> a = Files.list(actual); Stream<Path> e = Files.list(expected)) {
            a.forEach(p -> names.add(p.getFileName().toString()));
            e.forEach(p -> names.add(p.getFileName().toString()));
        }
        for (String name : names) {
            Path x = actual.resolve(name);
            Path y = expected.resolve(name);
            if (!Files.exists(x)) {
                problems.add(label + "/" + name + ": missing");
            } else if (!Files.exists(y)) {
                problems.add(label + "/" + name + ": unexpected");
            } else {
                String sx = Files.readString(x);
                String sy = Files.readString(y);
                if (!sx.equals(sy)) {
                    problems.add(label + "/" + name + ": differs " + firstDifference(sx, sy));
                }
            }
        }
    }

    static String firstDifference(String actual, String expected) {
        String[] a = actual.split("\n", -1);
        String[] e = expected.split("\n", -1);
        for (int i = 0; i < Math.max(a.length, e.length); i++) {
            String x = i < a.length ? a[i] : "<eof>";
            String y = i < e.length ? e[i] : "<eof>";
            if (!x.equals(y)) {
                return "at line " + (i + 1) + "\n    got:      " + x + "\n    expected: " + y;
            }
        }
        return "(line endings)";
    }

    static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
