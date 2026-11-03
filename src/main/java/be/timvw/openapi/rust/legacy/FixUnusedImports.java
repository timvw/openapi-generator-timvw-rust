package be.timvw.openapi.rust.legacy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Port of {@code scripts/fix_unused_imports.py}: removes imports the generator emits unconditionally but a file never
 * uses ({@code crate::models}, the multipart helpers, {@code de::Error as _}), in the apis and then the models.
 */
public class FixUnusedImports implements Rewrite {

    private static final int PY = Pattern.UNIX_LINES | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern USE_MODELS = Pattern.compile("^use crate::models;\\n", PY | Pattern.MULTILINE);
    private static final Pattern EMPTY_CODEC = Pattern.compile("^use tokio_util::codec::\\{\\s*\\};", PY | Pattern.MULTILINE);
    private static final Pattern EMPTY_CODEC_LINE =
            Pattern.compile("^use tokio_util::codec::\\{\\s*\\};\\n", PY | Pattern.MULTILINE);
    private static final Pattern SERDE_USE =
            Pattern.compile("^use serde::\\{([^}]*\\bde::Error as _\\b[^}]*)\\};\\n", PY | Pattern.MULTILINE);
    private static final Pattern ERROR_CALL =
            Pattern.compile("\\.custom\\(|::custom\\(|\\.invalid_|\\.missing_field\\(|\\.unknown_variant\\(", PY);

    public FixUnusedImports() {
    }

    /** {@code main}: every {@code .rs} file of {@code src/apis}, then of {@code src/models}, in sorted order. */
    @Override
    public void apply(LegacyContext context) throws IOException {
        for (Path dir : List.of(context.apis(), context.models())) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path path : (Iterable<Path>) files.sorted()::iterator) {
                    if (path.getFileName().toString().endsWith(".rs")) {
                        fixFile(path);
                    }
                }
            }
        }
    }

    /** {@code code_only}: the content without comment lines (doc comments included). */
    static String codeOnly(String content) {
        List<String> kept = new ArrayList<>();
        for (String l : content.split("\n", -1)) {
            if (!pyLstrip(l).startsWith("//")) {
                kept.add(l);
            }
        }
        return String.join("\n", kept);
    }

    /** {@code used_elsewhere}: whether {@code name} appears in the code once {@code removed} is taken out. */
    static boolean usedElsewhere(String content, String removed, String name) {
        String code = codeOnly(replaceOnce(content, removed, ""));
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\b", PY).matcher(code).find();
    }

    /** {@code drop_line}: removes {@code line} unless one of {@code names} is used elsewhere. */
    static String dropLine(String content, String line, List<String> names) {
        if (!content.contains(line)) {
            return content;
        }
        for (String n : names) {
            if (usedElsewhere(content, line, n)) {
                return content;
            }
        }
        return replaceOnce(content, line + "\n", "");
    }

    /** {@code prune_braced}: drops unused {@code items} from {@code use <prefix>::{a, b, c};}. */
    static String pruneBraced(String content, String prefix, List<String> items) {
        Pattern pattern = Pattern.compile("^use " + Pattern.quote(prefix) + "::\\{([^}]*)\\};\\n", PY | Pattern.MULTILINE);
        Matcher match = pattern.matcher(content);
        if (!match.find()) {
            return content;
        }
        String stmt = match.group(0);
        String rest = codeOnly(replaceOnce(content, stmt, ""));
        List<String> all = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (String i : match.group(1).split(",", -1)) {
            String s = pyStrip(i);
            if (s.isEmpty()) {
                continue;
            }
            all.add(s);
            if (!items.contains(s) || Pattern.compile("\\b" + Pattern.quote(s) + "\\b", PY).matcher(rest).find()) {
                kept.add(s);
            }
        }
        if (kept.equals(all)) {
            return content;
        }
        String neu = kept.isEmpty() ? "" : "use " + prefix + "::{" + String.join(", ", kept) + "};\n";
        return replaceOnce(content, stmt, neu);
    }

    /** {@code fix_file}. */
    static boolean fixFile(Path path) throws IOException {
        String original = Files.readString(path);
        String content = original;

        if (USE_MODELS.matcher(content).find()) {
            content = dropLine(content, "use crate::models;", List.of("models"));
        }

        content = dropLine(content, "use tokio::fs::File as TokioFile;", List.of("TokioFile"));
        content = pruneBraced(content, "tokio_util::codec", List.of("BytesCodec", "FramedRead"));
        if (EMPTY_CODEC.matcher(content).find()) {
            content = EMPTY_CODEC_LINE.matcher(content).replaceAll("");
        }
        content = pruneBraced(content, "super", List.of("ContentType", "multipart_helper"));

        Matcher serdeUse = SERDE_USE.matcher(content);
        if (serdeUse.find()) {
            String stripped = replaceOnce(content, serdeUse.group(0), "");
            if (!ERROR_CALL.matcher(stripped).find()) {
                List<String> kept = new ArrayList<>();
                for (String i : serdeUse.group(1).split(",", -1)) {
                    String s = pyStrip(i);
                    if (!s.isEmpty() && !s.equals("de::Error as _")) {
                        kept.add(s);
                    }
                }
                content = replaceOnce(content, serdeUse.group(0), "use serde::{" + String.join(", ", kept) + "};\n");
            }
        }

        if (!content.equals(original)) {
            Files.writeString(path, content);
            return true;
        }
        return false;
    }

    /** Python {@code str.replace(old, new, 1)}. */
    static String replaceOnce(String s, String old, String neu) {
        int at = s.indexOf(old);
        return at < 0 ? s : s.substring(0, at) + neu + s.substring(at + old.length());
    }

    /** Python {@code str.strip()}. */
    static String pyStrip(String s) {
        int b = 0;
        int e = s.length();
        while (b < e && isSpace(s.charAt(b))) {
            b++;
        }
        while (e > b && isSpace(s.charAt(e - 1))) {
            e--;
        }
        return s.substring(b, e);
    }

    /** Python {@code str.lstrip()}. */
    static String pyLstrip(String s) {
        int b = 0;
        while (b < s.length() && isSpace(s.charAt(b))) {
            b++;
        }
        return s.substring(b);
    }

    /** Python {@code str.isspace} for one character. */
    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || c == 0x85 || c == 0xA0 || c == 0x2007 || c == 0x202F;
    }
}
