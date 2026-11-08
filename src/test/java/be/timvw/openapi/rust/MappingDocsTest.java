package be.timvw.openapi.rust;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Keeps the rules of {@code docs/mapping/} tied to the tests. A rule is a level-3 heading holding its ID,
 * {@code <object>.<field>[.<case>]}, followed by a table. Each ID must be well formed and unique, each rule must have a
 * Tests row, and every test it names ({@code Class#method}, or {@code #method} for the class named before it) must
 * exist.
 */
class MappingDocsTest {

    private static final Path MAPPING = Path.of("docs/mapping");
    private static final Path TESTS = Path.of("src/test/java/be/timvw/openapi/rust");
    private static final Pattern RULE = Pattern.compile("^### `([^`]+)`\\s*$");
    private static final Pattern RULE_ID =
            Pattern.compile("^[a-z][A-Za-z]*\\.[A-Za-z$][A-Za-z0-9$]*(\\.[a-z0-9]+(-[a-z0-9]+)*)?$");
    private static final Pattern TEST_REF = Pattern.compile("`([A-Za-z0-9]*)#(\\w+)`");

    @Test
    void everyRuleNamesTestsThatExist() throws IOException {
        Map<String, String> sources = new HashMap<>();
        try (Stream<Path> files = Files.list(TESTS)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith("Test.java"))::iterator) {
                String name = file.getFileName().toString();
                sources.put(name.substring(0, name.length() - ".java".length()), Files.readString(file));
            }
        }
        List<Path> docs;
        try (Stream<Path> files = Files.list(MAPPING)) {
            docs = files.filter(f -> f.toString().endsWith(".md")).sorted().toList();
        }
        Map<String, String> seen = new HashMap<>();
        List<String> problems = new ArrayList<>();
        for (Path doc : docs) {
            String file = doc.getFileName().toString();
            String rule = null;
            boolean hasTests = false;
            List<String> lines = Files.readAllLines(doc);
            for (int i = 0; i <= lines.size(); i++) {
                String line = i < lines.size() ? lines.get(i) : null;
                Matcher heading = line == null ? null : RULE.matcher(line);
                if (line == null || heading.matches()) {
                    if (rule != null && !hasTests) {
                        problems.add(file + ": " + rule + " has no Tests row");
                    }
                    if (line == null) {
                        break;
                    }
                    rule = heading.group(1);
                    hasTests = false;
                    if (!RULE_ID.matcher(rule).matches()) {
                        problems.add(file + ":" + (i + 1) + ": malformed rule ID " + rule);
                    }
                    String other = seen.put(rule, file);
                    if (other != null) {
                        problems.add(file + ":" + (i + 1) + ": " + rule + " also in " + other);
                    }
                } else if (rule != null && line.startsWith("| Tests |")) {
                    hasTests = true;
                    String cls = null;
                    Matcher ref = TEST_REF.matcher(line);
                    while (ref.find()) {
                        cls = ref.group(1).isEmpty() ? cls : ref.group(1);
                        String method = ref.group(2);
                        if (cls == null) {
                            problems.add(file + ":" + (i + 1) + ": #" + method + " names no class");
                        } else if (!Pattern.compile("\\bvoid " + Pattern.quote(method) + "\\(")
                                .matcher(sources.getOrDefault(cls, "")).find()) {
                            problems.add(file + ":" + (i + 1) + ": " + rule + ": no test " + cls + "#" + method);
                        }
                    }
                }
            }
        }
        assertFalse(seen.isEmpty(), "no rules found in " + MAPPING.toAbsolutePath());
        assertEquals(List.of(), problems);
    }
}
