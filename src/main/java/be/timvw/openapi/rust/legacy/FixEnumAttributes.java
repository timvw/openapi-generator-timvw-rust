package be.timvw.openapi.rust.legacy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of openai-client-base {@code scripts/fix_enum_attributes.py}: moves a {@code #[derive(...)]} that got glued to
 * a doc comment line onto its own line, and adds the serde derives to enums that carry a {@code #[serde(...)]}
 * attribute without a derive.
 */
public final class FixEnumAttributes implements Rewrite {

    /** Python's {@code re}: Unicode {@code \s}, and {@code .}/{@code ^} only care about {@code \n}. */
    private static final int PY = Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES;

    private static final Pattern DOC_WITH_DERIVE =
            Pattern.compile("^(?<prefix>\\s*///.*?)(?<attr>\\#\\[derive\\([^\\)]*\\)\\])", PY | Pattern.MULTILINE);
    private static final Pattern SERDE_LINE = Pattern.compile("^\\s*#\\[serde\\(", PY);
    private static final Pattern ENUM_LINE = Pattern.compile("^\\s*pub\\s+enum\\s+\\w+", PY);
    private static final String DERIVE = "#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]";

    public FixEnumAttributes() {
    }

    /** {@code main}. */
    @Override
    public void apply(LegacyContext context) throws IOException {
        Path models = context.models();
        if (!Files.exists(models)) {
            return;
        }
        for (Path path : FixEmptyEnums.rustFiles(models)) {
            String original = Files.readString(path);
            String src = splitDocAndAttribute(original);
            String src2 = ensureEnumDerives(src);
            if (!src2.equals(original)) {
                Files.writeString(path, src2);
            }
        }
    }

    /** {@code split_doc_and_attribute}. */
    static String splitDocAndAttribute(String content) {
        return DOC_WITH_DERIVE.matcher(content)
                .replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "\n" + m.group(2)));
    }

    /**
     * {@code ensure_enum_derives}. Faithful to the script, including its quirks: blank lines between the serde
     * attribute and the enum are appended twice (once by the look-ahead, again when {@code i} reaches them), and the
     * derive check looks at {@code len(out) - 2}, which is the line before the serde attribute only without blanks.
     */
    static String ensureEnumDerives(String content) {
        List<String> lines = Arrays.asList(content.split("\n", -1));
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            out.add(lines.get(i));
            if (SERDE_LINE.matcher(lines.get(i)).lookingAt()) {
                int j = i + 1;
                while (j < lines.size() && isBlank(lines.get(j))) {
                    out.add(lines.get(j));
                    j++;
                }
                if (j < lines.size() && ENUM_LINE.matcher(lines.get(j)).lookingAt()) {
                    int k = out.size() - 2;
                    boolean hasDerive = false;
                    while (k >= 0 && isBlank(out.get(k))) {
                        k--;
                    }
                    if (k >= 0 && lstrip(out.get(k)).startsWith("#[derive(")) {
                        hasDerive = true;
                    }
                    if (!hasDerive) {
                        out.add(k + 1, DERIVE);
                    }
                }
            }
            i++;
        }
        return String.join("\n", out);
    }

    /** Python {@code s.strip() == ''}. */
    private static boolean isBlank(String s) {
        return s.chars().allMatch(FixEnumAttributes::isPySpace);
    }

    /** Python {@code s.lstrip()}. */
    private static String lstrip(String s) {
        int i = 0;
        while (i < s.length() && isPySpace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    /** Python {@code str.isspace()} on one character. */
    private static boolean isPySpace(int c) {
        return (c >= 0x09 && c <= 0x0d) || (c >= 0x1c && c <= 0x20) || c == 0x85 || c == 0xa0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200a) || c == 0x2028 || c == 0x2029 || c == 0x202f || c == 0x205f
                || c == 0x3000;
    }
}
