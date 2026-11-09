package be.timvw.openapi.rust;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Milestone 3.2: gives every closed value enum {@code FromStr} and {@code TryFrom<&str>},
 * {@code TryFrom<&String>} and {@code TryFrom<String>}, accepting exactly the strings its {@code Display} prints.
 *
 * <p>This runs over the written crate rather than in the model template: the final text says which enums exist and
 * what they print, and nothing that runs later can disturb what is written here. An enum is closed when every variant
 * is a unit variant and it is neither untagged nor internally tagged, so unions stay out.</p>
 *
 * <p>The strings come from the enum's own {@code Display} arms. An enum without {@code Display} (a property enum
 * rendered inside its struct's file) gets one that prints its serde names, so parsing and printing stay a pair.</p>
 *
 * <p>Every path in the emitted code is absolute, and no signature says {@code Self::Err} or {@code Self::Error}: a
 * variant called {@code Err} or {@code Error} would make those ambiguous, and a schema called {@code Result} or
 * {@code String} would shadow the prelude in its own file.</p>
 */
final class EnumFromStr {

    static final String ERROR_TYPE = "ParseEnumError";
    static final String ERROR_MODULE = "parse_enum_error";
    private static final String ERROR_PATH = "crate::models::" + ERROR_TYPE;

    private static final Pattern ENUM = Pattern.compile("^pub enum (\\w+)\\s*\\{", Pattern.MULTILINE);
    private static final String STRING_LITERAL = "\"((?:[^\"\\\\]|\\\\.)*)\"";
    private static final Pattern SERDE_RENAME = Pattern.compile("serde\\(.*\\brename\\s*=\\s*" + STRING_LITERAL);
    private static final Pattern SERDE_RENAME_ALL = Pattern.compile("serde\\(.*\\brename_all\\s*=\\s*" + STRING_LITERAL);
    private static final Pattern NOT_CLOSED = Pattern.compile("serde\\(.*\\b(untagged|tag)\\b");
    private static final Pattern SERDE_OTHER = Pattern.compile("serde\\(.*\\bother\\b");

    /**
     * One unit variant: its identifier and the string that names it. The catch-all of {@code openEnums} has a
     * {@code Display} like the others, but no string parses to it, since parsing is a caller's strict check.
     */
    record Variant(String name, String wire, boolean catchAll) {
        Variant(String name, String wire) {
            this(name, wire, false);
        }
    }

    /** A closed enum as it stands in the file; {@code hasDisplay} tells whether its {@code Display} is there. */
    record ClosedEnum(String name, List<Variant> variants, boolean hasDisplay) {
    }

    /** What a run did, for the log and the tests. */
    record Result(int enums, int displaysAdded) {
    }

    private EnumFromStr() {
    }

    /**
     * Adds the impls to every model file under {@code models} and declares the error type in its {@code mod.rs}.
     *
     * @throws IllegalStateException when a schema already takes the error type's name
     */
    static Result run(Path models, Consumer<String> warn) throws IOException {
        if (!Files.isDirectory(models)) {
            return new Result(0, 0);
        }
        Path mod = models.resolve("mod.rs");
        // mod.rs is rendered afresh, so a declaration here is a schema; a file alone is left from an earlier run.
        String declarations = Files.exists(mod) ? Files.readString(mod) : "";
        if (Pattern.compile("\\b" + ERROR_MODULE + "\\b|\\b" + ERROR_TYPE + "\\b").matcher(declarations).find()) {
            throw new IllegalStateException(TimvwRustCodegen.GENERATOR_NAME + ": FromStr"
                    + " needs the name " + ERROR_TYPE + ", which a schema already has");
        }
        int enums = 0;
        int displays = 0;
        List<Path> files;
        try (Stream<Path> listed = Files.list(models)) {
            files = listed.filter(p -> p.getFileName().toString().endsWith(".rs"))
                    .filter(p -> !p.getFileName().toString().equals("mod.rs")).sorted().toList();
        }
        for (Path file : files) {
            String content = Files.readString(file);
            List<ClosedEnum> closed = closedEnums(content, what -> warn.accept(file.getFileName() + ": " + what));
            if (closed.isEmpty()) {
                continue;
            }
            StringBuilder added = new StringBuilder(content);
            for (ClosedEnum e : closed) {
                added.append(impls(e));
                enums++;
                displays += e.hasDisplay() ? 0 : 1;
            }
            Files.writeString(file, added.toString());
        }
        if (enums > 0) {
            Files.writeString(models.resolve(ERROR_MODULE + ".rs"), errorModule());
            Files.writeString(mod, declarations + "pub mod " + ERROR_MODULE + ";\npub use self::" + ERROR_MODULE + "::"
                    + ERROR_TYPE + ";\n");
        }
        return new Result(enums, displays);
    }

    /** The closed value enums of a model file, in file order. */
    static List<ClosedEnum> closedEnums(String content, Consumer<String> warn) {
        List<ClosedEnum> found = new ArrayList<>();
        Matcher m = ENUM.matcher(content);
        while (m.find()) {
            String attributes = attributesBefore(content, m.start());
            if (NOT_CLOSED.matcher(attributes).find()) {
                continue;
            }
            int close = closingBrace(content, m.end() - 1);
            List<RawVariant> raw = close < 0 ? null : unitVariants(content.substring(m.end(), close));
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            String name = m.group(1);
            String renameAll = group(SERDE_RENAME_ALL, attributes);
            Map<String, String> display = displayArms(content, name);
            boolean hasDisplay = display != null;
            Map<String, Variant> byWire = new LinkedHashMap<>();
            List<Variant> variants = new ArrayList<>();
            for (RawVariant v : raw) {
                String wire = hasDisplay ? display.get(v.name()) : null;
                if (wire == null) {
                    if (hasDisplay) {
                        warn.accept(name + "::" + v.name() + " has no literal Display arm, using its serde name");
                    }
                    wire = v.rename() != null ? v.rename()
                            : v.discriminant() != null ? v.discriminant()
                            : applyRenameAll(renameAll, v.name());
                }
                Variant variant = new Variant(v.name(), wire, v.catchAll());
                Variant first = byWire.putIfAbsent(wire, variant);
                if (first != null) {
                    warn.accept(name + "::" + v.name() + " prints the same string as " + first.name()
                            + "; \"" + wire + "\" parses to the latter");
                }
                variants.add(variant);
            }
            found.add(new ClosedEnum(name, variants, hasDisplay));
        }
        return found;
    }

    private record RawVariant(String name, String rename, String discriminant, boolean catchAll) {
    }

    /** The attribute and doc lines directly above an item. */
    private static String attributesBefore(String content, int itemStart) {
        int end = itemStart;
        int start = itemStart;
        while (start > 0) {
            int lineStart = content.lastIndexOf('\n', start - 2) + 1;
            String line = content.substring(lineStart, start).trim();
            if (!line.startsWith("#[") && !line.startsWith("//")) {
                break;
            }
            start = lineStart;
        }
        return content.substring(start, end);
    }

    /** The variants of an enum body, or {@code null} when one of them carries data. */
    private static List<RawVariant> unitVariants(String body) {
        List<RawVariant> variants = new ArrayList<>();
        String rename = null;
        boolean catchAll = false;
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (Character.isWhitespace(c) || c == ',') {
                i++;
            } else if (body.startsWith("//", i)) {
                int eol = body.indexOf('\n', i);
                i = eol < 0 ? body.length() : eol + 1;
            } else if (body.startsWith("/*", i)) {
                int end = body.indexOf("*/", i + 2);
                i = end < 0 ? body.length() : end + 2;
            } else if (body.startsWith("#[", i)) {
                int end = closing(body, i + 1, '[', ']');
                if (end < 0) {
                    return null;
                }
                String attribute = body.substring(i, end + 1);
                String found = group(SERDE_RENAME, attribute);
                rename = found != null ? found : rename;
                // A variant renamed to "other" is no catch-all.
                catchAll |= SERDE_OTHER.matcher(attribute.replaceAll(STRING_LITERAL, "\"\"")).find();
                i = end + 1;
            } else {
                int start = i;
                while (i < body.length() && (Character.isLetterOrDigit(body.charAt(i)) || body.charAt(i) == '_'
                        || body.charAt(i) == '#')) {
                    i++;
                }
                if (start == i) {
                    return null;
                }
                String name = body.substring(start, i);
                while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
                    i++;
                }
                String discriminant = null;
                if (i < body.length() && body.charAt(i) == '=') {
                    int comma = body.indexOf(',', i);
                    int end = comma < 0 ? body.length() : comma;
                    discriminant = body.substring(i + 1, end).trim();
                    i = end;
                } else if (i < body.length() && body.charAt(i) != ',') {
                    return null;
                }
                variants.add(new RawVariant(name, rename, discriminant, catchAll));
                rename = null;
                catchAll = false;
            }
        }
        return variants;
    }

    /**
     * The literal arms of an enum's {@code Display}, as variant name to string literal contents, or {@code null}
     * when the enum has no {@code Display}.
     */
    private static Map<String, String> displayArms(String content, String name) {
        Matcher impl = Pattern.compile("impl\\s+(?:::)?(?:std::)?(?:fmt::)?Display\\s+for\\s+" + Pattern.quote(name)
                + "\\s*\\{").matcher(content);
        if (!impl.find()) {
            return null;
        }
        int close = closingBrace(content, impl.end() - 1);
        String body = close < 0 ? "" : content.substring(impl.end(), close);
        Matcher arm = Pattern.compile("\\b(?:Self|" + Pattern.quote(name) + ")::(\\w+)\\s*=>\\s*(write!\\(\\s*f\\s*,\\s*)?"
                + STRING_LITERAL + "\\s*(\\)?)").matcher(body);
        Map<String, String> arms = new LinkedHashMap<>();
        while (arm.find()) {
            boolean formatted = arm.group(2) != null;
            if (formatted && arm.group(4).isEmpty()) {
                continue; // write!(f, "{}", ...) is not a literal
            }
            String literal = arm.group(3);
            arms.putIfAbsent(arm.group(1), formatted ? literal.replace("{{", "{").replace("}}", "}") : literal);
        }
        return arms;
    }

    private static int closingBrace(String text, int open) {
        return closing(text, open, '{', '}');
    }

    /** The index closing the bracket at {@code open}; string literals and comments do not count. */
    private static int closing(String text, int open, char opening, char closing) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                i++;
                while (i < text.length() && text.charAt(i) != '"') {
                    i += text.charAt(i) == '\\' ? 2 : 1;
                }
            } else if (text.startsWith("//", i)) {
                int eol = text.indexOf('\n', i);
                if (eol < 0) {
                    return -1;
                }
                i = eol;
            } else if (c == opening) {
                depth++;
            } else if (c == closing && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String group(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** serde's {@code rename_all} applied to a variant name written in PascalCase. */
    static String applyRenameAll(String rule, String variant) {
        if (rule == null) {
            return variant;
        }
        String snake = variant.replaceAll("(?<=.)([A-Z])", "_$1").toLowerCase(Locale.ROOT);
        return switch (rule) {
            case "lowercase" -> variant.toLowerCase(Locale.ROOT);
            case "UPPERCASE" -> variant.toUpperCase(Locale.ROOT);
            case "PascalCase" -> variant;
            case "camelCase" -> Character.toLowerCase(variant.charAt(0)) + variant.substring(1);
            case "snake_case" -> snake;
            case "SCREAMING_SNAKE_CASE" -> snake.toUpperCase(Locale.ROOT);
            case "kebab-case" -> snake.replace('_', '-');
            case "SCREAMING-KEBAB-CASE" -> snake.replace('_', '-').toUpperCase(Locale.ROOT);
            default -> throw new IllegalStateException(TimvwRustCodegen.GENERATOR_NAME + ": unknown serde rename_all \""
                    + rule + "\" on " + variant);
        };
    }

    /** The impls of one enum; {@code Display} only when it has none. */
    static String impls(ClosedEnum e) {
        String name = e.name();
        StringBuilder out = new StringBuilder("\n");
        if (!e.hasDisplay()) {
            out.append("impl ::std::fmt::Display for ").append(name).append(" {\n")
                    .append("    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {\n")
                    .append("        f.write_str(match self {\n");
            for (Variant v : e.variants()) {
                out.append("            Self::").append(v.name()).append(" => \"").append(v.wire()).append("\",\n");
            }
            out.append("        })\n    }\n}\n\n");
        }
        out.append("impl ::std::str::FromStr for ").append(name).append(" {\n")
                .append("    type Err = ").append(ERROR_PATH).append(";\n\n")
                .append("    fn from_str(value: &str) -> ::std::result::Result<Self, ").append(ERROR_PATH).append("> {\n")
                .append("        match value {\n");
        List<String> seen = new ArrayList<>();
        for (Variant v : e.variants()) {
            if (!v.catchAll() && !seen.contains(v.wire())) {
                seen.add(v.wire());
                out.append("            \"").append(v.wire()).append("\" => Ok(Self::").append(v.name()).append("),\n");
            }
        }
        out.append("            _ => Err(").append(ERROR_PATH).append("::new(\"").append(name).append("\", value)),\n")
                .append("        }\n    }\n}\n");
        for (String source : List.of("&str", "&::std::string::String", "::std::string::String")) {
            out.append("\nimpl ::std::convert::TryFrom<").append(source).append("> for ").append(name).append(" {\n")
                    .append("    type Error = ").append(ERROR_PATH).append(";\n\n")
                    .append("    fn try_from(value: ").append(source).append(") -> ::std::result::Result<Self, ")
                    .append(ERROR_PATH).append("> {\n")
                    .append("        value.parse()\n")
                    .append("    }\n}\n");
        }
        return out.toString();
    }

    /** {@code src/models/parse_enum_error.rs}: written once, and only for a crate with a closed enum. */
    static String errorModule() {
        return """
                /// The error of parsing a string into a generated enum: the string names none of its variants.
                #[derive(Clone, Debug, Eq, PartialEq)]
                pub struct ParseEnumError {
                    type_name: &'static str,
                    value: String,
                }

                impl ParseEnumError {
                    pub(crate) fn new(type_name: &'static str, value: &str) -> Self {
                        Self {
                            type_name,
                            value: value.to_owned(),
                        }
                    }

                    /// The enum that was parsed into.
                    pub fn type_name(&self) -> &'static str {
                        self.type_name
                    }

                    /// The string that was rejected.
                    pub fn value(&self) -> &str {
                        &self.value
                    }
                }

                impl std::fmt::Display for ParseEnumError {
                    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                        write!(f, "invalid value {:?} for enum {}", self.value, self.type_name)
                    }
                }

                impl std::error::Error for ParseEnumError {}
                """;
    }
}
