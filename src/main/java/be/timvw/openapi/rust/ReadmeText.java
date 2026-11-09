package be.timvw.openapi.rust;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The spec's {@code info.description} as the generated README shows it. {@code lib.rs} includes the README as the
 * crate's documentation, so rustdoc compiles every Rust code block in it as a doctest: a code block of the description
 * that rustdoc would compile is rendered as a {@code text} block instead. That is a fenced block whose info string is
 * empty or only {@code rust} and rustdoc's attributes ({@code ignore}, {@code no_run}, {@code should_panic},
 * {@code compile_fail}, {@code edition2021}, ...), and an indented block, also one inside a list item (indented four
 * columns past the item's text). Code blocks in another language are not compiled and stay as they are.
 */
final class ReadmeText {

    /** Key of the README description in the template data. */
    static final String DESCRIPTION = "x-rust-readme-description";

    private static final Pattern FENCE = Pattern.compile("^( {0,3})(`{3,}|~{3,})(.*)$");
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)([-*+]|\\d+[.)])(\\s+)\\S");
    private static final Set<String> RUSTDOC_ATTRIBUTES = Set.of("rust", "ignore", "should_panic", "no_run",
            "compile_fail", "test_harness", "allow_fail", "standalone_crate");

    private ReadmeText() {
    }

    /** The description with every code block rustdoc would compile marked {@code text}. */
    static String withoutDoctests(String description) {
        List<String> out = new ArrayList<>();
        String[] lines = description.replace("\r\n", "\n").split("\n", -1);
        String fence = null;
        int codeBase = -1;
        int listContent = -1;
        boolean previousBlank = true;
        for (String raw : lines) {
            String line = expandTabs(raw);
            if (fence != null) {
                out.add(raw);
                String t = line.trim();
                char mark = fence.charAt(0);
                if (t.startsWith(fence) && t.chars().allMatch(c -> c == mark)) {
                    fence = null;
                }
                previousBlank = line.isBlank();
                continue;
            }
            int indent = indent(line);
            if (codeBase >= 0) {
                if (line.isBlank() || indent >= codeBase + 4) {
                    out.add(line.isBlank() ? "" : " ".repeat(codeBase) + line.substring(codeBase + 4));
                    previousBlank = line.isBlank();
                    continue;
                }
                closeIndented(out, codeBase);
                codeBase = -1;
            }
            Matcher f = FENCE.matcher(line);
            if (f.matches()) {
                fence = f.group(2);
                out.add(rust(f.group(3)) ? f.group(1) + fence + "text" : raw);
                previousBlank = false;
                continue;
            }
            if (!line.isBlank() && previousBlank) {
                int base = listContent >= 0 ? listContent : 0;
                if (indent >= base + 4) {
                    out.add(" ".repeat(base) + "```text");
                    out.add(" ".repeat(base) + line.substring(base + 4));
                    codeBase = base;
                    previousBlank = false;
                    continue;
                }
            }
            Matcher item = LIST_ITEM.matcher(line);
            if (item.find()) {
                listContent = item.group(1).length() + item.group(2).length() + item.group(3).length();
            } else if (!line.isBlank() && indent == 0) {
                listContent = -1;
            }
            out.add(raw);
            previousBlank = line.isBlank();
        }
        if (codeBase >= 0) {
            closeIndented(out, codeBase);
        }
        return String.join("\n", out);
    }

    /** Whether rustdoc compiles a fenced block with this info string: none, or only rust and its attributes. */
    static boolean rust(String info) {
        for (String token : info.trim().toLowerCase(Locale.ROOT).split("[,\\s]+")) {
            if (token.isEmpty() || RUSTDOC_ATTRIBUTES.contains(token) || token.matches("edition\\d+")
                    || token.startsWith("ignore-")) {
                continue;
            }
            return false;
        }
        return true;
    }

    /** Ends an indented block that became fenced, before the blank lines that followed it. */
    private static void closeIndented(List<String> out, int base) {
        int blanks = 0;
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) {
            out.remove(out.size() - 1);
            blanks++;
        }
        out.add(" ".repeat(base) + "```");
        for (int i = 0; i < blanks; i++) {
            out.add("");
        }
    }

    private static int indent(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static String expandTabs(String line) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        for (; i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t'); i++) {
            if (line.charAt(i) == '\t') {
                b.append(" ".repeat(4 - b.length() % 4));
            } else {
                b.append(' ');
            }
        }
        return b.append(line.substring(i)).toString();
    }
}
