package be.timvw.openapi.rust;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every relative link of the Markdown files (README.md, docs/**) points at a file that exists, and an anchor into a
 * Markdown file at one of its headings, as GitHub names them. Line anchors into source files ({@code #L12}) are
 * {@link MappingDocsTest}'s.
 */
class DocsLinksTest {

    /** {@code [text](target)}; images and reference-style links are not used in these docs. */
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");

    static List<Path> markdownFiles() throws IOException {
        List<Path> files = new ArrayList<>(List.of(Path.of("README.md")));
        try (Stream<Path> docs = Files.walk(Path.of("docs"))) {
            docs.filter(p -> p.toString().endsWith(".md")).sorted().forEach(files::add);
        }
        return files;
    }

    /** GitHub's anchor for a heading: lower case, punctuation dropped, spaces to {@code -}, numbered repeats. */
    static String slug(String heading) {
        String text = heading;
        StringBuilder slug = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            i += Character.charCount(c);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                slug.appendCodePoint(Character.toLowerCase(c));
            } else if (c == ' ') {
                slug.append('-');
            }
        }
        return slug.toString();
    }

    static Set<String> anchors(Path markdown) throws IOException {
        Set<String> anchors = new HashSet<>();
        Map<String, Integer> seen = new HashMap<>();
        boolean fenced = false;
        for (String line : Files.readAllLines(markdown)) {
            if (line.strip().startsWith("```")) {
                fenced = !fenced;
                continue;
            }
            Matcher m = HEADING.matcher(line);
            if (fenced || !m.matches()) {
                continue;
            }
            String base = slug(m.group(2));
            int n = seen.merge(base, 1, Integer::sum) - 1;
            anchors.add(n == 0 ? base : base + "-" + n);
        }
        return anchors;
    }

    static List<String> brokenLinks(Path markdown) throws IOException {
        List<String> broken = new ArrayList<>();
        List<String> lines = Files.readAllLines(markdown);
        boolean fenced = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.strip().startsWith("```")) {
                fenced = !fenced;
                continue;
            }
            if (fenced) {
                continue;
            }
            Matcher m = LINK.matcher(line.replaceAll("`[^`]*`", ""));
            while (m.find()) {
                String target = m.group(1);
                if (target.matches("^[a-z][a-z0-9+.-]*:.*")) {
                    continue; // https:, mailto:
                }
                String path = target.contains("#") ? target.substring(0, target.indexOf('#')) : target;
                String anchor = target.contains("#") ? target.substring(target.indexOf('#') + 1) : null;
                Path file = path.isEmpty() ? markdown : markdown.getParent() == null ? Path.of(path)
                        : markdown.getParent().resolve(path).normalize();
                String where = markdown + ":" + (i + 1) + ": " + target;
                if (!Files.exists(file)) {
                    broken.add(where + " (no such file)");
                } else if (anchor != null && file.toString().endsWith(".md") && !anchors(file).contains(anchor)) {
                    broken.add(where + " (no such heading)");
                }
            }
        }
        return broken;
    }

    @Test
    void everyRelativeLinkAndAnchorResolves() throws IOException {
        List<String> broken = new ArrayList<>();
        for (Path file : markdownFiles()) {
            broken.addAll(brokenLinks(file));
        }
        assertEquals(List.of(), broken);
    }

    @Test
    void anchorsAreNamedAsGitHubNamesThem() {
        assertEquals("from-020-to-021", slug("From 0.20 to 0.21"));
        assertEquals("from-stock--g-rust", slug("From stock `-g rust`"));
        assertEquals("responsecontentjson", slug("`response.content.json`"));
        assertEquals("1-response-functions-returning-apiresponset", slug("1. Response functions returning `ApiResponse<T>`"));
    }

    @Test
    void aBrokenLinkIsFound(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.md"), "# Title\n\n[ok](#title) [bad](#nope) [file](b.md) [gone](c.md)\n"
                + "```\n[ignored](x.md)\n```\n");
        Files.writeString(dir.resolve("b.md"), "# B\n");
        List<String> broken = brokenLinks(dir.resolve("a.md")).stream()
                .map(s -> s.substring(s.indexOf(": ") + 2)).collect(Collectors.toList());
        assertEquals(List.of("#nope (no such heading)", "c.md (no such file)"), broken);
        assertTrue(anchors(dir.resolve("b.md")).contains("b"));
    }
}
