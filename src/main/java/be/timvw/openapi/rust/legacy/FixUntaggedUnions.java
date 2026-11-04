package be.timvw.openapi.rust.legacy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static be.timvw.openapi.rust.legacy.LegacyNames.capitalize;
import static be.timvw.openapi.rust.legacy.LegacyNames.convertToRustTypeName;
import static be.timvw.openapi.rust.legacy.LegacyNames.lower;
import static be.timvw.openapi.rust.legacy.LegacyNames.pascalToSnake;
import static be.timvw.openapi.rust.legacy.LegacyNames.sanitizeVariantName;
import static be.timvw.openapi.rust.legacy.LegacyNames.snakeToPascal;
import static be.timvw.openapi.rust.legacy.LegacyNames.splitWhitespace;

/**
 * Port of openai-client-base's {@code scripts/fix_untagged_unions.py}: rewrites the models of untagged
 * {@code anyOf}/{@code oneOf} unions (detected in the preprocessed spec) as {@code #[serde(untagged)]} enums, creates
 * files for the inline string enums they use and registers new modules in {@code src/models/mod.rs}.
 *
 * <p>The unused {@code detect_mixed_tagged_unions} and {@code find_enum_values} are not ported.</p>
 */
public class FixUntaggedUnions implements Rewrite {

    /** One {@code (variant_name, variant_type)} tuple. */
    record Variant(String name, String type) {
    }

    /** The {@code {'values': [...], 'default': ...}} dict of a simple string enum. */
    static final class EnumInfo {
        List<Object> values;
        Object defaultValue;

        EnumInfo(List<Object> values, Object defaultValue) {
            this.values = values;
            this.defaultValue = defaultValue;
        }
    }

    /** The result tuple of {@code analyze_union_variants}. */
    record Analysis(List<Variant> variants, Map<String, EnumInfo> enumTypes) {
    }

    /** The result tuple of {@code detect_untagged_unions}. */
    record Detection(Map<String, List<Variant>> untaggedUnions, Map<String, EnumInfo> simpleStringEnums) {
    }

    public FixUntaggedUnions() {
    }

    /** {@code main}: detect, add the known content types, write enum files, rewrite union files, update mod.rs. */
    @Override
    public void apply(LegacyContext context) throws IOException {
        Path modelsDir = context.models();
        if (!Files.exists(modelsDir)) {
            throw new IOException("Models directory not found: " + modelsDir);
        }
        Detection detection = detectUntaggedUnions(context.preprocessedSpec());
        Map<String, List<Variant>> untaggedUnions = detection.untaggedUnions();
        Map<String, EnumInfo> simpleStringEnums = detection.simpleStringEnums();

        Map<String, List<Variant>> knownContentTypes = new LinkedHashMap<>();
        knownContentTypes.put("ChatCompletionRequestSystemMessageContent", List.of(
                new Variant("Text", "String"),
                new Variant("Array", "Vec<models::ChatCompletionRequestMessageContentPartText>")));
        knownContentTypes.put("ChatCompletionRequestUserMessageContent", List.of(
                new Variant("Text", "String"),
                new Variant("Array", "Vec<models::ChatCompletionRequestUserMessageContentPart>")));
        knownContentTypes.put("ChatCompletionRequestAssistantMessageContent", List.of(
                new Variant("Text", "String"),
                new Variant("Array", "Vec<models::ChatCompletionRequestAssistantMessageContentPart>")));
        knownContentTypes.put("ChatCompletionRequestToolMessageContent", List.of(
                new Variant("Text", "String"),
                new Variant("Array", "Vec<models::ChatCompletionRequestMessageContentPartText>")));
        knownContentTypes.put("ChatCompletionRequestDeveloperMessageContent", List.of(
                new Variant("Text", "String"),
                new Variant("Array", "Vec<models::ChatCompletionRequestMessageContentPartText>")));
        for (Map.Entry<String, List<Variant>> e : knownContentTypes.entrySet()) {
            untaggedUnions.putIfAbsent(e.getKey(), e.getValue());
        }

        Set<String> createdModules = new HashSet<>();
        boolean implDefault = !context.defaultOnlyWhereValid();

        // First, create files for simple string enums that might not exist
        for (Map.Entry<String, EnumInfo> e : simpleStringEnums.entrySet()) {
            String enumName = e.getKey();
            Path filepath = modelsDir.resolve(pascalToSnake(enumName) + ".rs");
            String newContent = "use serde::{Deserialize, Serialize};\n\n"
                    + createSimpleStringEnum(enumName, e.getValue(), implDefault);
            if (!Files.exists(filepath)) {
                write(filepath, newContent);
                createdModules.add(pascalToSnake(enumName));
            } else if (!read(filepath).equals(newContent)) {
                write(filepath, newContent);
            }
        }

        // Now fix the untagged union types (files may have been created just above)
        for (Map.Entry<String, List<Variant>> e : untaggedUnions.entrySet()) {
            Path filepath = modelsDir.resolve(pascalToSnake(e.getKey()) + ".rs");
            if (Files.exists(filepath)) {
                fixFile(filepath, e.getKey(), e.getValue(), simpleStringEnums, implDefault);
            }
        }

        if (!createdModules.isEmpty()) {
            Path modRs = modelsDir.resolve("mod.rs");
            if (Files.exists(modRs)) {
                write(modRs, addModules(read(modRs), new TreeSet<>(createdModules)));
            }
        }
    }

    /**
     * The mod.rs part of {@code main}: inserts {@code pub mod}/{@code pub use} pairs before the first greater
     * {@code pub mod} line, else around the first non-{@code pub mod} line after the first. The "already present"
     * check runs against the original content, as in the script.
     */
    static String addModules(String content, Collection<String> sortedModules) {
        List<String> lines = LegacyNames.split(content, "\n");
        for (String moduleName : sortedModules) {
            String pubLine = "pub mod " + moduleName + ";";
            String useLine = "pub use self::" + moduleName + "::" + snakeToPascal(moduleName) + ";";
            if (content.contains(pubLine)) {
                continue;
            }
            boolean inserted = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith("pub mod ") && line.compareTo(pubLine) > 0) {
                    lines.add(i, pubLine);
                    lines.add(i + 1, useLine);
                    inserted = true;
                    break;
                }
            }
            if (!inserted) {
                for (int i = 0; i < lines.size(); i++) {
                    if (!lines.get(i).startsWith("pub mod ") && i > 0) {
                        lines.add(i - 1, pubLine);
                        lines.add(i, useLine);
                        break;
                    }
                }
            }
        }
        return String.join("\n", lines);
    }

    /** {@code _merge_simple_string_enums}: adds new enums, fills a missing default, prefers the larger value set. */
    static void mergeSimpleStringEnums(Map<String, EnumInfo> target, Map<String, EnumInfo> newEntries) {
        for (Map.Entry<String, EnumInfo> e : newEntries.entrySet()) {
            EnumInfo info = e.getValue();
            EnumInfo existing = target.get(e.getKey());
            if (existing != null) {
                if (existing.defaultValue == null && info.defaultValue != null) {
                    existing.defaultValue = info.defaultValue;
                }
                if (size(info.values) > size(existing.values)) {
                    existing.values = info.values;
                }
            } else {
                target.put(e.getKey(), new EnumInfo(new ArrayList<>(info.values == null ? List.of() : info.values),
                        info.defaultValue));
            }
        }
    }

    /** {@code detect_untagged_unions}: property-level and schema-level unions without a discriminator. */
    static Detection detectUntaggedUnions(Map<String, Object> spec) {
        Map<String, List<Variant>> untaggedUnions = new LinkedHashMap<>();
        Map<String, EnumInfo> simpleStringEnums = new LinkedHashMap<>();
        Map<String, Object> schemas = map(get(map(get(spec, "components", new LinkedHashMap<>())), "schemas",
                new LinkedHashMap<>()));

        for (Map.Entry<String, Object> s : schemas.entrySet()) {
            String schemaName = s.getKey();
            Object schemaDef = s.getValue();
            if (in("$ref", schemaDef)) {
                continue;
            }
            if (schemaDef instanceof Map && "object".equals(map(schemaDef).get("type"))) {
                Map<String, Object> props = map(get(map(schemaDef), "properties", new LinkedHashMap<>()));
                for (Map.Entry<String, Object> p : props.entrySet()) {
                    String propName = p.getKey();
                    Object propDef = p.getValue();
                    if (in("anyOf", propDef) || in("oneOf", propDef)) {
                        if (!in("discriminator", propDef)) {
                            StringBuilder propPascal = new StringBuilder();
                            for (String word : splitWhitespace(propName.replace("_", " "))) {
                                propPascal.append(capitalize(word));
                            }
                            String typeName = schemaName + propPascal;
                            Analysis a = analyzeUnionVariants(anyOfOrOneOf(map(propDef)), schemas, typeName);
                            if (!a.variants().isEmpty()) {
                                untaggedUnions.put(typeName, a.variants());
                                mergeSimpleStringEnums(simpleStringEnums, a.enumTypes());
                            }
                        }
                    }
                }
            }
            if (in("anyOf", schemaDef) || in("oneOf", schemaDef)) {
                if (!in("discriminator", schemaDef)) {
                    Analysis a = analyzeUnionVariants(anyOfOrOneOf(map(schemaDef)), schemas, schemaName);
                    if (!a.variants().isEmpty()) {
                        untaggedUnions.put(schemaName, a.variants());
                        mergeSimpleStringEnums(simpleStringEnums, a.enumTypes());
                    }
                }
            }
        }
        return new Detection(untaggedUnions, simpleStringEnums);
    }

    /** {@code analyze_union_variants}: maps each anyOf/oneOf item to a {@code (name, type)} variant. */
    static Analysis analyzeUnionVariants(Object unionItems, Map<String, Object> schemas, String contextName) {
        List<Variant> variants = new ArrayList<>();
        Map<String, EnumInfo> enumTypes = new LinkedHashMap<>();

        for (Object element : iterate(unionItems)) {
            if (!(element instanceof Map)) {
                continue;
            }
            Map<String, Object> item = map(element);

            // Flatten nested anyOf/oneOf structures before handling concrete nodes
            Object nested = truthy(item.get("anyOf")) ? item.get("anyOf") : item.get("oneOf");
            if (truthy(nested)) {
                Analysis n = analyzeUnionVariants(nested, schemas, contextName);
                for (Variant v : n.variants()) {
                    if (!variants.contains(v)) {
                        variants.add(v);
                    }
                }
                enumTypes.putAll(n.enumTypes());
                continue;
            }

            Object type = item.get("type");
            if ("string".equals(type)) {
                String title = (String) get(item, "title", "Text");
                if (item.containsKey("enum")) {
                    String variantName = sanitizeVariantName(title);
                    if (names(variants).contains(variantName) || variantName.equals("Text")) {
                        variantName = variantName + "Variant";
                    }
                    String baseName = contextName != null && !contextName.isEmpty() ? convertToRustTypeName(contextName) : "";
                    String enumName = !baseName.isEmpty() ? baseName + variantName + "Enum" : variantName + "Enum";
                    variants.add(new Variant(variantName, enumName));
                    enumTypes.put(enumName, new EnumInfo(list(item.get("enum")), item.get("default")));
                } else {
                    String variantName = sanitizeVariantName(title);
                    if (names(variants).contains(variantName)) {
                        variantName = variantName + "String";
                    }
                    variants.add(new Variant(variantName, "String"));
                }
            } else if ("array".equals(type)) {
                String title = (String) get(item, "title", "Array");
                Object itemsDef = get(item, "items", new LinkedHashMap<>());
                if (in("$ref", itemsDef)) {
                    String refType = lastSegment((String) map(itemsDef).get("$ref"));
                    if (refType.equals("ChatCompletionRequestSystemMessageContentPart")
                            || refType.equals("ChatCompletionRequestToolMessageContentPart")
                            || refType.equals("ChatCompletionRequestDeveloperMessageContentPart")) {
                        refType = "ChatCompletionRequestMessageContentPartText";
                    }
                    variants.add(new Variant(sanitizeVariantName(title), "Vec<" + modelType(refType, refType, schemas) + ">"));
                } else if ("string".equals(map(itemsDef).get("type"))) {
                    variants.add(new Variant("ArrayOfStrings", "Vec<String>"));
                } else if ("integer".equals(map(itemsDef).get("type"))) {
                    variants.add(new Variant("ArrayOfIntegers", "Vec<i32>"));
                } else if ("array".equals(map(itemsDef).get("type"))) {
                    Map<String, Object> inner = map(get(map(itemsDef), "items", new LinkedHashMap<>()));
                    if ("integer".equals(inner.get("type"))) {
                        variants.add(new Variant("ArrayOfIntegerArrays", "Vec<Vec<i32>>"));
                    }
                } else {
                    variants.add(new Variant(sanitizeVariantName(title), "Vec<serde_json::Value>"));
                }
            } else if ("object".equals(type) || item.containsKey("$ref")) {
                if (item.containsKey("$ref")) {
                    String refName = lastSegment((String) item.get("$ref"));
                    String title = (String) get(item, "title", refName);
                    if (schemas.containsKey(refName) && "array".equals(map(schemas.get(refName)).get("type"))) {
                        Object arrayItems = get(map(schemas.get(refName)), "items", new LinkedHashMap<>());
                        if (in("$ref", arrayItems)) {
                            String innerType = lastSegment((String) map(arrayItems).get("$ref"));
                            variants.add(new Variant(sanitizeVariantName(title),
                                    "Vec<" + modelType(innerType, innerType, schemas) + ">"));
                        } else {
                            variants.add(new Variant(sanitizeVariantName(title), "Vec<serde_json::Value>"));
                        }
                    } else {
                        variants.add(new Variant(sanitizeVariantName(title),
                                modelType(refName, convertToRustTypeName(refName), schemas)));
                    }
                } else {
                    Object props = get(item, "properties", new LinkedHashMap<>());
                    if (in("type", props) || in("function", props) || in("custom", props)) {
                        String title = (String) get(item, "title", "Named");
                        variants.add(new Variant(sanitizeVariantName(title), "models::ChatCompletionNamedToolChoice"));
                    }
                }
            } else if ("null".equals(type)) {
                variants.add(new Variant("Null", "()"));
            }
        }
        return new Analysis(variants, enumTypes);
    }

    /** A free-form map, which the generator renders as a map and writes no model file for. */
    static final String FREE_FORM_MAP = "std::collections::HashMap<String, serde_json::Value>";

    /**
     * The Rust type of a referenced component schema: {@code models::<rustName>}, or {@link #FREE_FORM_MAP} for a
     * free-form object ({@code type: object} without properties, composition or a typed {@code additionalProperties}),
     * which the stock generator renders the same way everywhere else. The script always wrote {@code models::}, which
     * does not compile for such a schema; no union of the spec it ran on referenced one.
     */
    static String modelType(String schemaName, String rustName, Map<String, Object> schemas) {
        Object def = schemas.get(schemaName);
        if (def instanceof Map && isFreeFormObject(map(def))) {
            return FREE_FORM_MAP;
        }
        return "models::" + rustName;
    }

    private static boolean isFreeFormObject(Map<String, Object> schema) {
        Object additional = schema.get("additionalProperties");
        return "object".equals(schema.get("type"))
                && !truthy(schema.get("properties"))
                && !schema.containsKey("allOf") && !schema.containsKey("anyOf") && !schema.containsKey("oneOf")
                && !schema.containsKey("enum")
                && (additional == null || Boolean.TRUE.equals(additional)
                        || (additional instanceof Map && map(additional).isEmpty()));
    }

    /** {@code create_untagged_enum}: the untagged enum with Default (if asked), constructors, From and Display impls. */
    static String createUntaggedEnum(String name, List<Variant> variants, boolean implDefault) {
        List<String> lines = new ArrayList<>(List.of(
                "/// " + name + " - Untagged union type",
                "#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]",
                "#[serde(untagged)]",
                "pub enum " + name + " {"));
        for (Variant v : variants) {
            if (v.type().equals("()")) {
                lines.add("    " + v.name() + ",");
            } else {
                lines.add("    " + v.name() + "(" + v.type() + "),");
            }
        }
        lines.add("}");
        lines.add("");

        String firstName = variants.isEmpty() ? null : variants.get(0).name();
        String firstType = variants.isEmpty() ? null : variants.get(0).type();
        if (implDefault && List.of("String", "()", "Vec<String>", "Vec<i32>").contains(firstType)) {
            lines.add("impl Default for " + name + " {");
            lines.add("    fn default() -> Self {");
            if (firstType.equals("String")) {
                lines.add("        Self::" + firstName + "(String::new())");
            } else if (firstType.equals("()")) {
                lines.add("        Self::" + firstName);
            } else if (firstType.startsWith("Vec<")) {
                lines.add("        Self::" + firstName + "(Vec::new())");
            }
            lines.add("    }");
            lines.add("}");
            lines.add("");
        }

        if (variants.size() >= 2 && variants.get(0).type().equals("String")) {
            String first = variants.get(0).name();
            lines.add("impl " + name + " {");
            lines.add("    pub fn new_text(text: String) -> Self {");
            lines.add("        Self::" + first + "(text)");
            lines.add("    }");
            for (Variant v : variants.subList(1, variants.size())) {
                if (v.type().startsWith("Vec<")) {
                    lines.add("    pub fn new_" + lower(v.name()) + "(items: " + v.type() + ") -> Self {");
                    lines.add("        Self::" + v.name() + "(items)");
                    lines.add("    }");
                }
            }
            lines.addAll(List.of(
                    "}",
                    "",
                    "impl From<String> for " + name + " {",
                    "    fn from(s: String) -> Self {",
                    "        Self::" + first + "(s)",
                    "    }",
                    "}",
                    "",
                    "impl From<&str> for " + name + " {",
                    "    fn from(s: &str) -> Self {",
                    "        Self::" + first + "(s.to_string())",
                    "    }",
                    "}"));
        }

        lines.add("impl std::fmt::Display for " + name + " {");
        lines.add("    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {");
        lines.add("        match self {");
        for (Variant v : variants) {
            if (v.type().equals("()")) {
                lines.add("            " + name + "::" + v.name() + " => write!(f, \"null\"),");
            } else if (v.type().equals("String") || v.type().endsWith("Enum")) {
                lines.add("            " + name + "::" + v.name() + "(value) => write!(f, \"{}\", value),");
            } else {
                lines.add("            " + name + "::" + v.name() + "(value) => match serde_json::to_string(value) {");
                lines.add("                Ok(s) => write!(f, \"{}\", s),");
                lines.add("                Err(_) => Err(std::fmt::Error),");
                lines.add("            },");
            }
        }
        lines.addAll(List.of("        }", "    }", "}", ""));
        lines.add("");
        return String.join("\n", lines);
    }

    /** {@code create_simple_string_enum}: a lower-case-renamed string enum with Default (if asked) and Display impls. */
    static String createSimpleStringEnum(String name, EnumInfo enumInfo, boolean implDefault) {
        List<Object> values = enumInfo.values;
        Object defaultValue = enumInfo.defaultValue;
        List<String> lines = new ArrayList<>(List.of(
                "/// " + name + " - String enum type",
                "#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]",
                "#[serde(rename_all = \"lowercase\")]",
                "pub enum " + name + " {"));

        List<String[]> pairs = new ArrayList<>();
        for (Object raw : values) {
            String value = (String) raw;
            String variant = sanitizeVariantName(value);
            if (!lower(variant).equals(value)) {
                lines.add("    #[serde(rename = \"" + value + "\")]");
            }
            lines.add("    " + variant + ",");
            pairs.add(new String[] {variant, value});
        }

        lines.addAll(List.of("}", ""));

        if (implDefault) {
            lines.addAll(List.of(
                    "impl Default for " + name + " {",
                    "    fn default() -> Self {"));

            Object firstValue = values.contains(defaultValue) ? defaultValue : values.get(0);
            String firstVariant = null;
            for (String[] pair : pairs) {
                if (Objects.equals(pair[1], firstValue)) {
                    firstVariant = pair[0];
                    break;
                }
            }
            if (firstVariant == null && !pairs.isEmpty()) {
                firstVariant = pairs.get(0)[0];
            }
            lines.addAll(List.of(
                    "        Self::" + (firstVariant == null ? "None" : firstVariant),
                    "    }",
                    "}",
                    ""));
        }

        lines.addAll(List.of(
                "impl std::fmt::Display for " + name + " {",
                "    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {",
                "        let value = match self {"));
        for (String[] pair : pairs) {
            lines.add("            " + name + "::" + pair[0] + " => \"" + pair[1] + "\",");
        }
        lines.addAll(List.of(
                "        };",
                "        write!(f, \"{}\", value)",
                "    }",
                "}",
                ""));
        return String.join("\n", lines);
    }

    /** {@code fix_file}: overwrites a model file with the untagged enum and the string enums its variants use. */
    static void fixFile(Path filepath, String typeName, List<Variant> variants, Map<String, EnumInfo> simpleStringEnums,
                        boolean implDefault) throws IOException {
        String rustTypeName = convertToRustTypeName(typeName);
        StringBuilder content = new StringBuilder("use crate::models;\nuse serde::{Deserialize, Serialize};\n\n");
        content.append(createUntaggedEnum(rustTypeName, variants, implDefault));
        Set<String> addedEnums = new HashSet<>();
        for (Variant v : variants) {
            if (simpleStringEnums.containsKey(v.type()) && !addedEnums.contains(v.type())) {
                content.append(createSimpleStringEnum(v.type(), simpleStringEnums.get(v.type()), implDefault));
                addedEnums.add(v.type());
            }
        }
        write(filepath, content.toString());
    }

    // --- Python data-model helpers -------------------------------------------------------------------------------

    /** Python {@code open(path).read()} in text mode: universal newlines turn {@code \r\n} and {@code \r} into {@code \n}. */
    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\r", "\n");
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** {@code node.get('anyOf') or node.get('oneOf')}. */
    private static Object anyOfOrOneOf(Map<String, Object> node) {
        Object anyOf = node.get("anyOf");
        return truthy(anyOf) ? anyOf : node.get("oneOf");
    }

    /** {@code s.split('/')[-1]}. */
    private static String lastSegment(String ref) {
        return ref.substring(ref.lastIndexOf('/') + 1);
    }

    private static List<String> names(List<Variant> variants) {
        List<String> names = new ArrayList<>();
        for (Variant v : variants) {
            names.add(v.name());
        }
        return names;
    }

    /** Python {@code key in container} for dicts, lists and strings. */
    private static boolean in(String key, Object container) {
        if (container instanceof Map) {
            return ((Map<?, ?>) container).containsKey(key);
        }
        if (container instanceof List) {
            return ((List<?>) container).contains(key);
        }
        if (container instanceof String) {
            return ((String) container).contains(key);
        }
        throw new IllegalStateException("argument of type " + typeOf(container) + " is not iterable");
    }

    /** Python truthiness of a JSON value. */
    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0;
        }
        if (value instanceof String) {
            return !((String) value).isEmpty();
        }
        if (value instanceof Collection) {
            return !((Collection<?>) value).isEmpty();
        }
        if (value instanceof Map) {
            return !((Map<?, ?>) value).isEmpty();
        }
        return true;
    }

    /** Python {@code for x in value}: dicts iterate their keys. */
    private static Iterable<?> iterate(Object value) {
        if (value instanceof Map) {
            return ((Map<?, ?>) value).keySet();
        }
        if (value instanceof Iterable) {
            return (Iterable<?>) value;
        }
        throw new IllegalStateException("'" + typeOf(value) + "' object is not iterable");
    }

    /** {@code d.get(key, default)}: the default only when the key is absent, not when it maps to null. */
    private static Object get(Map<String, Object> map, String key, Object fallback) {
        return map.containsKey(key) ? map.get(key) : fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        throw new IllegalStateException("'" + typeOf(value) + "' object has no attribute 'get'");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static int size(List<Object> values) {
        return values == null ? 0 : values.size();
    }

    private static String typeOf(Object value) {
        return value == null ? "NoneType" : value.getClass().getSimpleName();
    }
}
