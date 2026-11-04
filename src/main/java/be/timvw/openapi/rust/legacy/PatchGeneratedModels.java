package be.timvw.openapi.rust.legacy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Port of the parts of {@code scripts/patch_generated.rs.sh} that touch {@code src/models}, in the script's order:
 * the {@code models::models::} fix over {@code src}, the {@code content} edits on {@code create_message_request.rs}
 * and {@code prediction_content.rs}, {@code add_bon_builders.py}, {@code fix_tool_enum_tagging.py} and the doc link
 * fix. The user agent, multipart, {@code Cargo.toml} and {@code #[bon::builder]} steps only touch apis or the
 * manifest and are not part of this rewrite.
 */
public class PatchGeneratedModels implements Rewrite {

    private static final Pattern PUB_STRUCT = Pattern.compile("^pub struct \\w+ \\{",
            Pattern.UNIX_LINES | Pattern.UNICODE_CHARACTER_CLASS);

    private final boolean toolEnumTagging;

    public PatchGeneratedModels() {
        this(true);
    }

    /** @param toolEnumTagging run {@code fix_tool_enum_tagging.py}; without it no enum is retagged */
    public PatchGeneratedModels(boolean toolEnumTagging) {
        this.toolEnumTagging = toolEnumTagging;
    }

    @Override
    public void apply(LegacyContext context) throws IOException {
        fixDoubleModulePath(context.src());
        for (String name : List.of("create_message_request.rs", "prediction_content.rs")) {
            loosenContentTypes(context.models().resolve(name));
        }
        sedFile(context.models().resolve("create_message_request.rs"),
                "pub fn new(role: Role, content: String)", "pub fn new(role: Role, content: serde_json::Value)");
        sedFile(context.models().resolve("prediction_content.rs"),
                "pub fn new(r#type: Type, content: String)", "pub fn new(r#type: Type, content: serde_json::Value)");
        addBonBuilderToAllStructs(context.models());
        if (toolEnumTagging) {
            new FixToolEnumTagging().apply(context);
        }
        new DocLinkFix().apply(context);
    }

    /**
     * Step 1, {@code rg -l "models::models::" src | sed 's/models::models::/models::/g'}: every non-hidden file under
     * {@code src} that mentions the double path.
     */
    static void fixDoubleModulePath(Path src) throws IOException {
        try (Stream<Path> files = Files.walk(src)) {
            for (Path path : (Iterable<Path>) files.sorted()::iterator) {
                if (!Files.isRegularFile(path) || isHidden(src, path)) {
                    continue;
                }
                String content = Files.readString(path);
                if (content.contains("models::models::")) {
                    Files.writeString(path, content.replace("models::models::", "models::"));
                }
            }
        }
    }

    private static boolean isHidden(Path root, Path path) {
        for (Path part : root.relativize(path)) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Step 2, the four {@code sed} calls per file. The last two put {@code \n} in their pattern; {@code sed} matches
     * one line at a time, so they never match and are left out.
     */
    static void loosenContentTypes(Path f) throws IOException {
        if (!Files.exists(f)) {
            return;
        }
        sedFile(f, "pub content: String,", "pub content: serde_json::Value,");
        sedFile(f, "new(content: String,", "new(content: serde_json::Value,");
    }

    /** {@code sed -i 's/<literal>/<literal>/'}: the first occurrence on each line. */
    static void sedFile(Path f, String literal, String replacement) throws IOException {
        if (!Files.exists(f)) {
            return;
        }
        String content = Files.readString(f);
        StringBuilder out = new StringBuilder(content.length() + 64);
        int start = 0;
        while (start < content.length()) {
            int nl = content.indexOf('\n', start);
            int end = nl < 0 ? content.length() : nl;
            String line = content.substring(start, end);
            int at = line.indexOf(literal);
            if (at >= 0) {
                line = line.substring(0, at) + replacement + line.substring(at + literal.length());
            }
            out.append(line);
            if (nl >= 0) {
                out.append('\n');
            }
            start = nl < 0 ? content.length() : nl + 1;
        }
        String result = out.toString();
        if (!result.equals(content)) {
            Files.writeString(f, result);
        }
    }

    /**
     * {@code add_bon_builders.py}'s {@code add_bon_builder_to_all_structs} (the script's only entry point;
     * {@code add_bon_builder_to_other_structs} is never called): appends {@code , bon::Builder} to the derive line
     * right above each <code>pub struct X {</code>.
     */
    static void addBonBuilderToAllStructs(Path modelsDir) throws IOException {
        try (Stream<Path> files = Files.list(modelsDir)) {
            for (Path filePath : (Iterable<Path>) files.sorted()::iterator) {
                String name = filePath.getFileName().toString();
                if (!name.endsWith(".rs") || name.equals("mod.rs")) {
                    continue;
                }
                String content = Files.readString(filePath);
                if (content.contains("bon::Builder")) {
                    continue;
                }
                String[] lines = content.split("\n", -1);
                boolean modified = false;
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];
                    if (line.startsWith("#[derive(") && i + 1 < lines.length) {
                        String nextLine = lines[i + 1];
                        if (PUB_STRUCT.matcher(nextLine).lookingAt()) {
                            int closingParen = line.lastIndexOf(')');
                            if (closingParen != -1) {
                                lines[i] = line.substring(0, closingParen) + ", bon::Builder" + line.substring(closingParen);
                                modified = true;
                            }
                        }
                    }
                }
                if (modified) {
                    Files.writeString(filePath, String.join("\n", lines));
                }
            }
        }
    }
}
