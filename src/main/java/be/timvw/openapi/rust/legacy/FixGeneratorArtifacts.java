package be.timvw.openapi.rust.legacy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Port of {@code scripts/fix_generator_artifacts.py}: normalizes enum names the generator left with underscores,
 * drops repeated renames and duplicated variant/display lines, and replaces the nonexistent
 * {@code models::AnyOfLessThanGreaterThan} with {@code serde_json::Value}.
 */
public class FixGeneratorArtifacts implements Rewrite {

    private static final int PY = Pattern.UNIX_LINES | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern PUB_ENUM = Pattern.compile("^pub enum (\\w+)\\s*\\{", PY | Pattern.MULTILINE);
    private static final Pattern VARIANT_LINE = Pattern.compile("\\w+\\([^)]*\\),", PY);

    public FixGeneratorArtifacts() {
    }

    /** {@code main}: normalizes all model files together, then fixes and writes each changed file. */
    @Override
    public void apply(LegacyContext context) throws IOException {
        Path models = context.models();
        Map<Path, String> files = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.list(models)) {
            for (Path path : (Iterable<Path>) paths.sorted()::iterator) {
                if (path.getFileName().toString().endsWith(".rs")) {
                    files.put(path, Files.readString(path));
                }
            }
        }
        boolean anyOfExists = Files.exists(models.resolve("any_of_less_than_greater_than.rs"));
        Map<Path, String> normalized = normalizeIdentifiers(files);
        for (Map.Entry<Path, String> e : normalized.entrySet()) {
            String fixed = fixContent(e.getValue(), anyOfExists);
            if (!fixed.equals(files.get(e.getKey()))) {
                Files.writeString(e.getKey(), fixed);
            }
        }
    }

    /** {@code normalized_name}: strips outer underscores and joins the parts with their first letter upper-cased. */
    static String normalizedName(String name) {
        StringBuilder out = new StringBuilder();
        for (String part : pyStrip(name, "_").split("_", -1)) {
            if (!part.isEmpty()) {
                int first = part.offsetByCodePoints(0, 1);
                out.append(part.substring(0, first).toUpperCase(Locale.ROOT)).append(part.substring(first));
            }
        }
        return out.toString();
    }

    /**
     * {@code normalize_identifiers}. Python walks a set of names here; the order only matters for names without an
     * underscore that prefix one another, so a sorted order is used.
     */
    static Map<Path, String> normalizeIdentifiers(Map<Path, String> files) {
        TreeSet<String> names = new TreeSet<>();
        for (String content : files.values()) {
            Matcher m = PUB_ENUM.matcher(content);
            while (m.find()) {
                names.add(m.group(1));
            }
        }
        Map<String, String> replacements = new LinkedHashMap<>();
        for (String name : names) {
            if (!name.equals(normalizedName(name))) {
                replacements.put(name, normalizedName(name));
            }
        }
        if (replacements.isEmpty()) {
            return files;
        }
        Map<String, String> distinct = new LinkedHashMap<>();
        replacements.forEach((old, neu) -> {
            if (old.contains("_")) {
                distinct.put(old, neu);
            }
        });
        Pattern pattern = distinct.isEmpty() ? null : Pattern.compile("\\b(" + distinct.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .collect(Collectors.joining("|")) + ")\\b", PY);
        Map<Path, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<Path, String> e : files.entrySet()) {
            Path path = e.getKey();
            String content = e.getValue();
            if (pattern != null) {
                content = pattern.matcher(content).replaceAll(m -> Matcher.quoteReplacement(distinct.get(m.group())));
            }
            for (Map.Entry<String, String> r : replacements.entrySet()) {
                String old = r.getKey();
                String neu = r.getValue();
                if (!old.contains("_")) {
                    if (stem(path).equals(old)) {
                        content = Pattern.compile("\\b" + Pattern.quote(old) + "\\b", PY).matcher(content)
                                .replaceAll(Matcher.quoteReplacement(neu));
                    } else {
                        content = content.replace("models::" + old, "models::" + neu);
                    }
                }
            }
            normalized.put(path, content);
        }
        return normalized;
    }

    /** {@code fix_content}: line-level duplicate cleanup and the {@code AnyOfLessThanGreaterThan} replacement. */
    static String fixContent(String content, boolean anyOfExists) {
        List<String> result = new ArrayList<>();
        boolean previousRename = false;
        for (String line : splitLinesKeepEnds(content)) {
            String stripped = pyStrip(line, null);
            boolean isRename = stripped.startsWith("#[serde(rename = ");
            if (isRename && previousRename) {
                continue;
            }
            boolean isVariant = VARIANT_LINE.matcher(stripped).matches();
            boolean isDisplayArm = stripped.contains("::") && stripped.contains("=> write!(f,");
            if (!result.isEmpty() && line.equals(result.get(result.size() - 1)) && (isVariant || isDisplayArm)) {
                continue;
            }
            result.add(line);
            previousRename = isRename;
        }
        String joined = String.join("", result);
        if (!anyOfExists) {
            joined = joined.replace("models::AnyOfLessThanGreaterThan", "serde_json::Value");
        }
        return joined;
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Python {@code str.splitlines(keepends=True)}, with all of its line boundaries. */
    static List<String> splitLinesKeepEnds(String s) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                i += 2;
                lines.add(s.substring(start, i));
                start = i;
            } else if (c == '\n' || c == '\r' || c == 0x0B || c == 0x0C || c == 0x1C || c == 0x1D || c == 0x1E
                    || c == 0x85 || c == 0x2028 || c == 0x2029) {
                i++;
                lines.add(s.substring(start, i));
                start = i;
            } else {
                i++;
            }
        }
        if (start < s.length()) {
            lines.add(s.substring(start));
        }
        return lines;
    }

    /** Python {@code str.strip(chars)}; {@code chars == null} strips what {@code str.isspace} calls whitespace. */
    static String pyStrip(String s, String chars) {
        int b = 0;
        int e = s.length();
        while (b < e && stripped(s.charAt(b), chars)) {
            b++;
        }
        while (e > b && stripped(s.charAt(e - 1), chars)) {
            e--;
        }
        return s.substring(b, e);
    }

    private static boolean stripped(char c, String chars) {
        if (chars != null) {
            return chars.indexOf(c) >= 0;
        }
        return Character.isWhitespace(c) || c == 0x85 || c == 0xA0 || c == 0x2007 || c == 0x202F;
    }
}
