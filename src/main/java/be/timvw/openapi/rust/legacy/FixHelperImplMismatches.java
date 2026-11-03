package be.timvw.openapi.rust.legacy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Port of {@code scripts/fix_helper_impl_mismatches.py}: removes helper impls of an enum ({@code impl E}, {@code impl
 * Default for E}, {@code impl From<..> for E}) that construct {@code Self::Variant(..)} for a variant the enum does not
 * have, plus any impl block mentioning {@code Self::TextContent(} or {@code Self::ArrayOfContentParts(}.
 */
public class FixHelperImplMismatches implements Rewrite {

    private static final int PY = Pattern.UNIX_LINES | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern ENUM = Pattern.compile("pub\\s+enum\\s+(\\w+)\\s*\\{([\\s\\S]*?)\\}", PY);
    private static final Pattern VARIANT = Pattern.compile("^\\s*([A-Za-z0-9_]+)\\s*\\(", PY | Pattern.MULTILINE);
    private static final Pattern SELF_CALL = Pattern.compile("Self::([A-Za-z0-9_]+)\\s*\\(", PY);
    private static final Pattern ANY_IMPL = Pattern.compile("impl\\s+[A-Za-z0-9_<>:&\\s]+\\{", PY);

    public FixHelperImplMismatches() {
    }

    /** {@code main}: rewrites every model file whose helpers were removed. */
    @Override
    public void apply(LegacyContext context) throws IOException {
        try (Stream<Path> files = Files.list(context.models())) {
            for (Path path : (Iterable<Path>) files.sorted()::iterator) {
                if (!path.getFileName().toString().endsWith(".rs")) {
                    continue;
                }
                String src = Files.readString(path);
                String newSrc = removeMismatchedImpls(src);
                if (newSrc != null) {
                    Files.writeString(path, newSrc);
                }
            }
        }
    }

    /** {@code collect_enums}: enum name to its variant identifiers, in first-seen order. */
    static Map<String, Set<String>> collectEnums(String src) {
        Map<String, Set<String>> enums = new LinkedHashMap<>();
        Matcher m = ENUM.matcher(src);
        while (m.find()) {
            Set<String> variants = new HashSet<>();
            Matcher v = VARIANT.matcher(m.group(2));
            while (v.find()) {
                variants.add(v.group(1));
            }
            enums.put(m.group(1), variants);
        }
        return enums;
    }

    /** {@code remove_mismatched_impls}: the new source, or {@code null} when nothing changed. */
    static String removeMismatchedImpls(String src) {
        Map<String, Set<String>> enums = collectEnums(src);
        if (enums.isEmpty()) {
            return null;
        }
        boolean[] changed = {false};
        String newSrc = src;
        for (Map.Entry<String, Set<String>> e : enums.entrySet()) {
            newSrc = findAndStripImpls(e.getKey(), e.getValue(), newSrc, changed);
        }
        if (newSrc.contains("Self::TextContent(") || newSrc.contains("Self::ArrayOfContentParts(")) {
            newSrc = dropBlocksWith("Self::TextContent(", newSrc, changed);
            newSrc = dropBlocksWith("Self::ArrayOfContentParts(", newSrc, changed);
        }
        return changed[0] ? newSrc : null;
    }

    /** {@code find_and_strip_impls}: the four strip passes for one enum. */
    static String findAndStripImpls(String enumName, Set<String> variants, String text, boolean[] changed) {
        text = stripHelpers(enumName, variants, text, changed);
        text = stripBlocks(Pattern.compile("impl\\s+" + enumName + "\\s*\\{", PY), variants, text, changed);
        text = stripBlocks(Pattern.compile("impl\\s+Default\\s+for\\s+" + enumName + "\\s*\\{", PY), variants, text, changed);
        text = stripBlocks(Pattern.compile("impl\\s+From\\s*<[^>]+>\\s+for\\s+" + enumName + "\\s*\\{", PY), variants, text,
                changed);
        return text;
    }

    /** {@code strip_blocks}: drops each block opened by {@code prefix} whose body calls a missing {@code Self::} variant. */
    static String stripBlocks(Pattern prefix, Set<String> variants, String s, boolean[] changed) {
        return strip(prefix, s, (block, body) -> {
            Matcher u = SELF_CALL.matcher(body);
            while (u.find()) {
                if (!variants.contains(u.group(1))) {
                    return true;
                }
            }
            return false;
        }, changed);
    }

    /** {@code strip_helpers}: drops {@code impl E} blocks using {@code TextContent}/{@code ArrayOfContentParts} that E lacks. */
    static String stripHelpers(String enumName, Set<String> variants, String s, boolean[] changed) {
        Pattern patt = Pattern.compile("impl\\s+" + enumName + "\\s*\\{", PY);
        return strip(patt, s, (block, body) ->
                (body.contains("Self::TextContent(") && !variants.contains("TextContent"))
                        || (body.contains("Self::ArrayOfContentParts(") && !variants.contains("ArrayOfContentParts")),
                changed);
    }

    /** {@code drop_blocks_with}: drops every impl block that contains {@code mark}. */
    static String dropBlocksWith(String mark, String s, boolean[] changed) {
        return strip(ANY_IMPL, s, (block, body) -> block.contains(mark), changed);
    }

    interface BlockTest {
        boolean drop(String block, String body);
    }

    /**
     * The brace-matching loop the three Python functions share: from each match of {@code patt} (ending at its
     * {@code '{'}) up to the matching {@code '}'} (or the end of the text), dropping the block when {@code test} says so.
     */
    private static String strip(Pattern patt, String s, BlockTest test, boolean[] changed) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        Matcher m = patt.matcher(s);
        while (i < s.length()) {
            if (!m.find(i)) {
                out.append(s, i, s.length());
                break;
            }
            out.append(s, i, m.start());
            int braceStart = m.end() - 1;
            int depth = 0;
            int j = braceStart;
            while (j < s.length()) {
                char c = s.charAt(j);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
                j++;
            }
            String block = s.substring(m.start(), Math.min(j + 1, s.length()));
            String body = s.substring(braceStart + 1, j);
            if (test.drop(block, body)) {
                changed[0] = true;
            } else {
                out.append(block);
            }
            i = j + 1;
        }
        return out.toString();
    }
}
