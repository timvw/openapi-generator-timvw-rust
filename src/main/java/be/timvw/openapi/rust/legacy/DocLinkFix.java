package be.timvw.openapi.rust.legacy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Port of the perl one-liner in {@code scripts/generate.sh} and at the end of {@code scripts/patch_generated.rs.sh}:
 *
 * <pre>perl -pi -e 'if (m{^\s&#42;//[/!]}) { s{(\]\(https?://[^)\s]&#42;)$}{$1)} }'</pre>
 *
 * <p>run over every {@code .rs} file under {@code src}: closes a markdown link that a doc comment line leaves
 * unterminated (the spec truncates a description mid-link). Perl {@code -p} reads one line at a time, newline
 * included, and its {@code $} matches before that newline; Java's {@code $} without {@code MULTILINE} does the same.
 * Perl's {@code \s} on bytes is ASCII whitespace, as is Java's default {@code \s}.</p>
 */
public class DocLinkFix implements Rewrite {

    private static final Pattern DOC_LINE = Pattern.compile("^\\s*//[/!]", Pattern.UNIX_LINES);
    private static final Pattern OPEN_LINK = Pattern.compile("(\\]\\(https?://[^)\\s]*)$", Pattern.UNIX_LINES);

    public DocLinkFix() {
    }

    @Override
    public void apply(LegacyContext context) throws IOException {
        try (Stream<Path> files = Files.walk(context.src())) {
            for (Path path : (Iterable<Path>) files.sorted()::iterator) {
                if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".rs")) {
                    String content = Files.readString(path);
                    String fixed = fixLines(content);
                    if (!fixed.equals(content)) {
                        Files.writeString(path, fixed);
                    }
                }
            }
        }
    }

    /** The {@code -p} loop: applies {@link #fixLine} to each line, its trailing newline included. */
    static String fixLines(String content) {
        StringBuilder out = new StringBuilder(content.length() + 16);
        int start = 0;
        while (start < content.length()) {
            int nl = content.indexOf('\n', start);
            int end = nl < 0 ? content.length() : nl + 1;
            out.append(fixLine(content.substring(start, end)));
            start = end;
        }
        return out.toString();
    }

    /** The {@code -e} body for one line. */
    static String fixLine(String line) {
        if (DOC_LINE.matcher(line).find()) {
            return OPEN_LINK.matcher(line).replaceFirst("$1)");
        }
        return line;
    }
}
