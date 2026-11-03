package be.timvw.openapi.rust.legacy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Port of {@code scripts/fix_tool_enum_tagging.py}: an enum with {@code #[serde(tag = "type")]} that wraps a struct
 * which has its own {@code type} field becomes {@code #[serde(untagged)]}, and its variant renames are dropped.
 */
public class FixToolEnumTagging implements Rewrite {

    private static final int PY = Pattern.UNIX_LINES | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern UPPER_BOUNDARY = Pattern.compile("(?<!^)(?=[A-Z])", PY);
    private static final Pattern TYPE_FIELD = Pattern.compile("pub\\s+(r#)?type\\s*:", PY);
    private static final Pattern VARIANT = Pattern.compile("(\\w+)\\(Box<models::(\\w+)>\\)", PY);
    private static final Pattern TAG = Pattern.compile("#\\[serde\\(tag = \"type\"\\)\\]", PY);
    private static final Pattern VARIANT_RENAME = Pattern.compile("    #\\[serde\\(rename = \"[^\"]+\"\\)\\]\\n", PY);

    public FixToolEnumTagging() {
    }

    /** {@code main}: finds the files to fix first, then fixes them. */
    @Override
    public void apply(LegacyContext context) throws IOException {
        Path srcDir = context.models();
        List<Path> filesToFix = new ArrayList<>();
        try (Stream<Path> files = Files.list(srcDir)) {
            for (Path filepath : (Iterable<Path>) files.sorted()::iterator) {
                if (filepath.getFileName().toString().endsWith(".rs") && shouldFixEnum(filepath, srcDir)) {
                    filesToFix.add(filepath);
                }
            }
        }
        for (Path filepath : filesToFix) {
            fixEnumTagging(filepath);
        }
    }

    /** {@code has_type_field}: whether the struct's own file declares a {@code type} field. */
    static boolean hasTypeField(String structName, Path srcDir) throws IOException {
        String filename = UPPER_BOUNDARY.matcher(structName).replaceAll("_").toLowerCase(Locale.ROOT) + ".rs";
        Path filepath = srcDir.resolve(filename);
        if (!Files.exists(filepath)) {
            return false;
        }
        return TYPE_FIELD.matcher(Files.readString(filepath)).find();
    }

    /** {@code should_fix_enum}: internally tagged on {@code type} and wrapping a type that has a {@code type} field. */
    static boolean shouldFixEnum(Path filepath, Path srcDir) throws IOException {
        String content = Files.readString(filepath);
        if (!content.contains("#[serde(tag = \"type\")]")) {
            return false;
        }
        Matcher m = VARIANT.matcher(content);
        boolean conflicting = false;
        while (m.find()) {
            if (hasTypeField(m.group(2), srcDir)) {
                conflicting = true;
            }
        }
        return conflicting;
    }

    /** {@code fix_enum_tagging}: switches to {@code untagged} and removes the variant renames. */
    static boolean fixEnumTagging(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        String original = content;
        content = TAG.matcher(content).replaceAll(Matcher.quoteReplacement("#[serde(untagged)]"));
        content = VARIANT_RENAME.matcher(content).replaceAll("");
        if (!content.equals(original)) {
            Files.writeString(filePath, content);
            return true;
        }
        return false;
    }
}
