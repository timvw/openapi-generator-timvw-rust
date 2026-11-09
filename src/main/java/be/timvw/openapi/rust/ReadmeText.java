package be.timvw.openapi.rust;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The spec's {@code info.description} as the generated README shows it. {@code lib.rs} includes the README as the
 * crate's documentation, so rustdoc compiles every Rust code block in it as a doctest: a code block of the description
 * (fenced without a language, fenced as {@code rust}, or indented) is rendered as a {@code text} block instead. Code
 * blocks in another language are not compiled and stay as they are.
 */
final class ReadmeText {

    /** Key of the README description in the template data. */
    static final String DESCRIPTION = "x-rust-readme-description";

    private static final Pattern FENCE = Pattern.compile("^( {0,3})(`{3,}|~{3,})\\s*([^`\\s]*)(.*)$");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*([-*+]|\\d+[.)])\\s");

    private ReadmeText() {
    }

    /** The description with every code block rustdoc would compile marked {@code text}. */
    static String withoutDoctests(String description) {
        List<String> out = new ArrayList<>();
        String[] lines = description.replace("\r\n", "\n").split("\n", -1);
        String fence = null;
        boolean indented = false;
        String previous = "";
        for (String line : lines) {
            if (fence != null) {
                out.add(line);
                if (line.trim().startsWith(fence) && line.trim().replace(fence.substring(0, 1), "").isEmpty()) {
                    fence = null;
                }
                previous = line;
                continue;
            }
            Matcher m = FENCE.matcher(line);
            if (m.matches()) {
                fence = m.group(2);
                String language = m.group(3);
                boolean rust = language.isEmpty() || language.toLowerCase(java.util.Locale.ROOT).startsWith("rust")
                        || language.contains(",");
                out.add(rust ? m.group(1) + fence + "text" : line);
                previous = line;
                continue;
            }
            boolean code = (line.startsWith("    ") || line.startsWith("\t")) && !line.isBlank();
            if (code && !indented && previous.isBlank() && !afterList(out)) {
                out.add("```text");
                indented = true;
            } else if (indented && !code && !line.isBlank()) {
                closeIndented(out);
                indented = false;
            }
            out.add(indented ? stripIndent(line) : line);
            previous = line;
        }
        if (indented) {
            closeIndented(out);
        }
        return String.join("\n", out);
    }

    /** Ends an indented block that became fenced, before the blank lines that followed it. */
    private static void closeIndented(List<String> out) {
        int blanks = 0;
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) {
            out.remove(out.size() - 1);
            blanks++;
        }
        out.add("```");
        for (int i = 0; i < blanks; i++) {
            out.add("");
        }
    }

    private static String stripIndent(String line) {
        if (line.startsWith("\t")) {
            return line.substring(1);
        }
        return line.startsWith("    ") ? line.substring(4) : line.stripLeading();
    }

    /** Whether the last text before the blank lines is a list item: indented lines then continue the item. */
    private static boolean afterList(List<String> out) {
        for (int i = out.size() - 1; i >= 0; i--) {
            String line = out.get(i);
            if (!line.isBlank()) {
                return LIST_ITEM.matcher(line).find() || line.startsWith("    ") || line.startsWith("\t");
            }
        }
        return false;
    }
}
