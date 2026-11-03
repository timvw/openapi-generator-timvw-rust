package be.timvw.openapi.rust.legacy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Port of openai-client-base {@code scripts/fix_generated_code.py}: fixes common issues in the generated models
 * (invalid enum variant names, recursive types, JSON {@code Display} impls, {@code Default} on empty enums and on
 * types holding non-{@code Default} fields).
 *
 * <p>All patterns are compiled with {@link #FLAGS} so that {@code \w}, {@code \s}, {@code \d} and {@code \b} are
 * Unicode-aware like Python's {@code str} patterns, and only {@code \n} terminates a line for {@code ^}, {@code $} and
 * {@code .}, as in Python. Replacements are built as literal text, the way Python expands {@code \1} templates.</p>
 */
public class FixGeneratedCode implements Rewrite {

    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES;

    public FixGeneratedCode() {
    }

    /** Port of {@code main()}: applies the fixes in the script's order. */
    @Override
    public void apply(LegacyContext context) throws IOException {
        Path modelsDir = context.models();
        if (!Files.exists(modelsDir)) {
            return;
        }
        fixInvalidEnumVariants(modelsDir);
        fixRecursiveTypes(modelsDir);
        addDisplayImplForStructs(modelsDir);
        removeDefaultFromEmptyEnums(modelsDir);
        removeDefaultFromProblematicStructs(modelsDir);
    }

    /** Port of {@code fix_recursive_types}: wraps self-references in {@code Box<>}. */
    void fixRecursiveTypes(Path modelsDir) throws IOException {
        for (Path filePath : rsFiles(modelsDir)) {
            String content = read(filePath);
            String originalContent = content;

            // finditer runs over the content as it was when the loop started.
            List<MatchResult> typeMatches = findAll(re("pub (enum|struct) (\\w+)"), content);
            for (MatchResult typeMatch : typeMatches) {
                String typeName = typeMatch.group(2);
                String q = Pattern.quote(typeName);

                content = sub(re("(\\w+)\\(models::" + q + "\\)"), content,
                        m -> m.group(1) + "(Box<models::" + typeName + ">)");

                content = sub(re("(pub \\w+: )models::" + q + ","), content,
                        m -> m.group(1) + "Box<models::" + typeName + ">,");

                List<MatchResult> fieldMatches = findAll(re("pub (\\w+): Box<models::" + q + ">"), content);
                for (MatchResult fieldMatch : fieldMatches) {
                    String fieldName = fieldMatch.group(1);
                    String rawName = fieldName.startsWith("r#") ? fieldName.substring(2) : fieldName;
                    content = sub(re("(\\s+)" + Pattern.quote(fieldName) + ":\\s+" + Pattern.quote(rawName) + ","),
                            content, m -> m.group(1) + fieldName + ": Box::new(" + rawName + "),");
                    content = sub(re("(?<=[{,])\\s*" + Pattern.quote(rawName) + "\\s*(?=[},])"), content,
                            m -> " " + fieldName + ": Box::new(" + rawName + ")");
                }
            }

            if (!content.equals(originalContent)) {
                write(filePath, content);
            }
        }
    }

    /** Port of {@code fix_invalid_enum_variants}: fixes dots/hyphens and leading digits in variant names. */
    void fixInvalidEnumVariants(Path modelsDir) throws IOException {
        Pattern numericVariantPattern = re("(?m)^(\\s*)(\\d[\\w]*)\\s*,\\s*$");
        for (Path filePath : rsFiles(modelsDir)) {
            String content = read(filePath);
            String originalContent = content;

            content = sub(re("\\bGpt4\\.1\\b"), content, m -> "Gpt4_1");
            content = sub(re("\\bGpt4\\.5\\b"), content, m -> "Gpt4_5");
            content = sub(re("\\bGpt3\\.5\\b"), content, m -> "Gpt3_5");

            content = sub(re("(models::)([A-Z]\\w+)-(\\d+)"), content, m -> m.group(1) + m.group(2) + m.group(3));
            content = sub(re("(Box<models::)([A-Z]\\w+)-(\\d+)"), content,
                    m -> m.group(1) + m.group(2) + m.group(3));

            // Offsets come from this snapshot and are reused on the mutated content, as in the script.
            List<MatchResult> matches = findAll(numericVariantPattern, content);
            for (int i = matches.size() - 1; i >= 0; i--) {
                MatchResult match = matches.get(i);
                String indent = match.group(1);
                String rawName = match.group(2);
                String sanitized = "Variant" + rawName.replaceAll("[^A-Za-z0-9_]", "_");

                // The script splices the indent into the pattern unescaped (it is whitespace only).
                Pattern existingPattern = Pattern.compile("^" + indent + "(?:#\\[serde\\(rename = \""
                        + Pattern.quote(rawName) + "\"\\)\\]\\n" + indent + ")?" + Pattern.quote(sanitized) + "\\s*,",
                        FLAGS | Pattern.MULTILINE);
                if (existingPattern.matcher(content).find()) {
                    continue;
                }

                String replacement = indent + "#[serde(rename = \"" + rawName + "\")]\n" + indent + sanitized + ",";
                content = content.substring(0, match.start()) + replacement + content.substring(match.end());

                content = sub(re("(::|Self::)" + Pattern.quote(rawName) + "\\b"), content,
                        m -> m.group(1) + sanitized);
            }

            if (!content.equals(originalContent)) {
                write(filePath, content);
            }
        }
    }

    /** Port of {@code add_display_impl_for_structs}: adds a JSON {@code Display} impl to serializable structs. */
    void addDisplayImplForStructs(Path modelsDir) throws IOException {
        Pattern structPattern = re("pub struct (\\w+)");
        for (Path filePath : rsFiles(modelsDir)) {
            String content = read(filePath);
            if (!content.contains("pub struct ")) {
                continue;
            }
            List<String> structs = findAllGroup(structPattern, content, 1);
            if (structs.isEmpty()) {
                continue;
            }

            StringBuilder added = new StringBuilder();
            for (String structName : structs) {
                // The script splices the name into the pattern unescaped (it is \w+ only).
                Pattern deriveRegex = Pattern.compile(
                        "#\\[derive\\(([^)]*?)\\)\\]\\s*pub struct " + structName + "\\b", FLAGS | Pattern.DOTALL);
                Matcher deriveMatch = deriveRegex.matcher(content);
                if (!deriveMatch.find() || !deriveMatch.group(1).contains("Serialize")) {
                    continue;
                }
                if (re("impl\\s+std::fmt::Display\\s+for\\s+" + Pattern.quote(structName) + "\\b").matcher(content)
                        .find()) {
                    continue;
                }
                added.append("\n\nimpl std::fmt::Display for ").append(structName).append(" {\n")
                        .append("    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {\n")
                        .append("        match serde_json::to_string(self) {\n")
                        .append("            Ok(s) => write!(f, \"{}\", s),\n")
                        .append("            Err(_) => Err(std::fmt::Error),\n")
                        .append("        }\n")
                        .append("    }\n")
                        .append("}\n");
            }

            if (added.length() > 0) {
                write(filePath, content + added);
            }
        }
    }

    /** Port of {@code remove_default_from_empty_enums}: drops {@code Default} impls of empty enums. */
    void removeDefaultFromEmptyEnums(Path modelsDir) throws IOException {
        for (Path filePath : rsFiles(modelsDir)) {
            String content = read(filePath);
            if (re("pub enum \\w+ \\{\\s*\\}").matcher(content).find()) {
                String newContent = sub(Pattern.compile("impl Default for (\\w+) \\{[^}]*\\}\\s*\\}\\s*",
                        FLAGS | Pattern.DOTALL), content, m -> "");
                newContent = sub(re("(pub enum \\w+ \\{\\s*\\})\\s*\\n\\s*\\}"), newContent, m -> m.group(1));
                if (!newContent.equals(content)) {
                    write(filePath, newContent);
                }
            }
        }
    }

    /** Port of {@code remove_default_from_problematic_structs}: drops {@code Default} from types with non-Default fields. */
    void removeDefaultFromProblematicStructs(Path modelsDir) throws IOException {
        Set<String> nonDefaultTypes = new HashSet<>();

        for (Path filePath : rsFiles(modelsDir)) {
            String content = read(filePath);
            for (MatchResult enumMatch : findAll(re("pub enum (\\w+)"), content)) {
                String name = enumMatch.group(1);
                String derivePattern = "#\\[derive\\([^)]*Default[^)]*\\)\\]\\s*(?:#\\[[^\\]]*\\]\\s*)*pub enum "
                        + name + "\\b";
                boolean hasDeriveDefault = re(derivePattern).matcher(content).find();
                boolean hasImplDefault = re("impl Default for " + name + "\\b").matcher(content).find();
                if (!hasDeriveDefault && !hasImplDefault) {
                    nonDefaultTypes.add(name);
                }
            }
        }

        for (Path filePath : rsFiles(modelsDir)) {
            Matcher match = re("pub enum (\\w+) \\{\\s*\\}").matcher(read(filePath));
            if (match.find()) {
                nonDefaultTypes.add(match.group(1));
            }
        }

        boolean changes = true;
        int iterations = 0;
        while (changes && iterations < 10) {
            changes = false;
            iterations++;

            for (Path filePath : rsFiles(modelsDir)) {
                String content = read(filePath);
                Matcher structMatch = re("pub struct (\\w+)").matcher(content);
                if (!structMatch.find()) {
                    continue;
                }
                String structName = structMatch.group(1);

                if (!re("#\\[derive\\([^)]*Default[^)]*\\)\\]").matcher(content).find()) {
                    nonDefaultTypes.add(structName);
                    continue;
                }

                Set<String> fieldTypes = new HashSet<>(findAllGroup(
                        re("pub \\w+: (?:Option<)?(?:Box<)?(?:models::)?(\\w+)\\b"), content, 1));
                fieldTypes.retainAll(nonDefaultTypes);
                if (!fieldTypes.isEmpty()) {
                    String newContent = sub(re("#\\[derive\\(([^)]*?)Default,\\s*([^)]*)\\)\\]"), content,
                            m -> "#[derive(" + m.group(1) + m.group(2) + ")]");
                    newContent = sub(re("#\\[derive\\(([^)]*?),\\s*Default([^)]*)\\)\\]"), newContent,
                            m -> "#[derive(" + m.group(1) + m.group(2) + ")]");
                    newContent = sub(re(",\\s*,"), newContent, m -> ",");
                    newContent = sub(re("\\(\\s*,"), newContent, m -> "(");
                    newContent = sub(re(",\\s*\\)"), newContent, m -> ")");

                    if (!newContent.equals(content)) {
                        write(filePath, newContent);
                        nonDefaultTypes.add(structName);
                        changes = true;
                    }
                }
            }

            for (Path filePath : rsFiles(modelsDir)) {
                String content = read(filePath);
                Matcher implMatch = re("impl Default for (\\w+)\\s*\\{").matcher(content);
                if (!implMatch.find()) {
                    continue;
                }
                String enumName = implMatch.group(1);
                if (nonDefaultTypes.contains(enumName)) {
                    continue;
                }

                Matcher enumMatch = Pattern.compile("^pub enum " + Pattern.quote(enumName) + "\\s*\\{(.*?)^\\}",
                        FLAGS | Pattern.MULTILINE | Pattern.DOTALL).matcher(content);
                if (!enumMatch.find()) {
                    continue;
                }

                Set<String> variantTypes = new HashSet<>(findAllGroup(
                        re("\\w+\\((?:Box<)?(?:models::)?(\\w+)>?\\)"), enumMatch.group(1), 1));
                variantTypes.retainAll(nonDefaultTypes);
                if (!variantTypes.isEmpty()) {
                    String newContent = sub(Pattern.compile("impl Default for " + Pattern.quote(enumName)
                            + "\\s*\\{[^}]*\\{[^}]*\\}[^}]*\\}", FLAGS | Pattern.DOTALL), content, m -> "");
                    if (!newContent.equals(content)) {
                        write(filePath, newContent);
                        nonDefaultTypes.add(enumName);
                        changes = true;
                    }
                }
            }
        }
    }

    // --- helpers emulating Python's pathlib/re behaviour ---

    private static Pattern re(String regex) {
        return Pattern.compile(regex, FLAGS);
    }

    /** {@code re.sub(pattern, repl, s)} with a replacement computed as literal text; unmatched groups give "". */
    static String sub(Pattern pattern, String input, Function<MatchResult, String> replacement) {
        Matcher m = pattern.matcher(input);
        return m.replaceAll(r -> Matcher.quoteReplacement(replacement.apply(new PyMatch(r))));
    }

    /** {@code list(re.finditer(pattern, s))}. */
    static List<MatchResult> findAll(Pattern pattern, String input) {
        return pattern.matcher(input).results().map(PyMatch::new).collect(Collectors.toList());
    }

    /** {@code re.findall} for a pattern whose result is one group. */
    static List<String> findAllGroup(Pattern pattern, String input, int group) {
        List<String> out = new ArrayList<>();
        for (MatchResult r : findAll(pattern, input)) {
            out.add(r.group(group));
        }
        return out;
    }

    /** A frozen match whose unmatched groups read as "", as in Python's template expansion. */
    private record PyMatch(MatchResult m) implements MatchResult {
        @Override
        public int start() {
            return m.start();
        }

        @Override
        public int start(int group) {
            return m.start(group);
        }

        @Override
        public int end() {
            return m.end();
        }

        @Override
        public int end(int group) {
            return m.end(group);
        }

        @Override
        public String group() {
            return m.group();
        }

        @Override
        public String group(int group) {
            String g = m.group(group);
            return g == null ? "" : g;
        }

        @Override
        public int groupCount() {
            return m.groupCount();
        }
    }

    /** {@code models_dir.glob("*.rs")}, in sorted file-name order. */
    private static List<Path> rsFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".rs") && Files.isRegularFile(p))
                    .sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
                    .collect(Collectors.toList());
        }
    }

    /** {@code Path.read_text()}: UTF-8 with universal newlines. */
    private static String read(Path file) throws IOException {
        String s = Files.readString(file, StandardCharsets.UTF_8);
        return s.indexOf('\r') < 0 ? s : s.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** {@code Path.write_text()}: UTF-8, {@code \n} newlines. */
    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
