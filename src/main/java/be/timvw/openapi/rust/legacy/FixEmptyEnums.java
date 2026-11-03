package be.timvw.openapi.rust.legacy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Port of openai-client-base {@code scripts/fix_empty_enums.py}: fills the discriminated (tagged) enums the generator
 * leaves empty with one variant per union member, sanitizes enum blocks whose file contains a dash, fixes acronym
 * casing in {@code Box<models::X>} payloads, and finally replaces variant payload types whose model file does not exist
 * with {@code serde_json::Value}.
 */
public final class FixEmptyEnums implements Rewrite {

    /** Python's {@code re} matches {@code \s}, {@code \w} and {@code \b} on Unicode and {@code .} up to {@code \n} only. */
    private static final int PY = Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES;

    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";
    private static final Pattern BOX_MODEL_WITH_DASH = Pattern.compile("Box<models::([A-Za-z0-9_\\-]+)>", PY);
    private static final Pattern BOX_MODEL = Pattern.compile("Box<models::([A-Za-z0-9_]+)>", PY);
    private static final Pattern BARE_MODEL = Pattern.compile("\\bmodels::([A-Za-z0-9_]+)\\b", PY);
    private static final Pattern ENUM_BLOCK = Pattern.compile("pub\\s+enum\\s+\\w+\\s*\\{[\\s\\S]*?\\}", PY);

    public FixEmptyEnums() {
    }

    /** One discriminated variant: a payload schema (or inline index) and its tag value. */
    private record Variant(String schema, Integer index, String discriminatorValue) {
    }

    /** The result of {@code _extract_discriminated_variants} and the values of the detected-enum map. */
    private record EnumInfo(String propertyName, List<Variant> variants, boolean inline) {
    }

    /** One traversal step of {@code _derive_enum_names}: {@code ('property', name)} or {@code ('array_items', None)}. */
    private record PathStep(String kind, String name) {
    }

    /** {@code main}. */
    @Override
    public void apply(LegacyContext context) throws IOException {
        Path modelsDir = context.models();
        if (!Files.isDirectory(modelsDir)) {
            return;
        }
        Map<String, EnumInfo> emptyEnums = detectEmptyTaggedEnums(context.rawSpec());
        if (emptyEnums.isEmpty()) {
            return;
        }
        for (Map.Entry<String, EnumInfo> e : emptyEnums.entrySet()) {
            fixEmptyEnum(modelsDir, e.getKey(), e.getValue(), context.boxPolicy());
        }
        fallbackMissingVariantTypes(modelsDir);
    }

    // ---------------------------------------------------------------- spec traversal

    /** {@code _get_ref_name}. */
    private static String getRefName(Object node) {
        if (!(node instanceof Map<?, ?> map)) {
            return null;
        }
        Object ref = map.get("$ref");
        if (ref instanceof String s && s.startsWith(SCHEMA_REF_PREFIX)) {
            return s.substring(s.lastIndexOf('/') + 1);
        }
        return null;
    }

    /** {@code _extract_discriminated_variants}. */
    private static EnumInfo extractDiscriminatedVariants(Object unionSchema, Map<String, Object> schemas) {
        if (!(unionSchema instanceof Map<?, ?> union)) {
            return null;
        }
        if (!union.containsKey("discriminator")) {
            return null;
        }
        if (!union.containsKey("anyOf") && !union.containsKey("oneOf")) {
            return null;
        }
        Map<?, ?> discriminator = asMap(union.get("discriminator"));
        String propertyName = discriminator.containsKey("propertyName")
                ? pyStr(discriminator.get("propertyName")) : "type";
        Object itemsValue = truthy(union.get("anyOf")) ? union.get("anyOf")
                : truthy(union.get("oneOf")) ? union.get("oneOf") : List.of();
        List<?> items = (List<?>) itemsValue;

        // Case 1: all variants are $ref schemas
        boolean allRefs = items.stream().allMatch(it -> it instanceof Map<?, ?> m && m.containsKey("$ref"));
        if (allRefs) {
            List<Variant> variantInfo = new ArrayList<>();
            for (Object it : items) {
                String refName = getRefName(it);
                if (refName == null || refName.isEmpty()) {
                    continue;
                }
                Map<?, ?> refSchema = schemas.containsKey(refName) ? asMap(schemas.get(refName)) : Map.of();
                Map<?, ?> properties = refSchema.containsKey("properties") ? asMap(refSchema.get("properties")) : Map.of();
                Object discriminatorValue = null;
                if (properties.containsKey(propertyName)) {
                    if (properties.get(propertyName) instanceof Map<?, ?> propDef) {
                        if (propDef.containsKey("const")) {
                            discriminatorValue = propDef.get("const");
                        } else if (propDef.get("enum") instanceof List<?> en && en.size() == 1) {
                            discriminatorValue = en.get(0);
                        }
                    }
                }
                if (!truthy(discriminatorValue)) {
                    // Fallback heuristic
                    discriminatorValue = pyLower(refName);
                }
                variantInfo.add(new Variant(refName, null, pyStr(discriminatorValue)));
            }
            if (variantInfo.isEmpty()) {
                return null;
            }
            return new EnumInfo(propertyName, variantInfo, false);
        }

        // Case 2: inline object variants with discriminator set via enum/const
        List<Variant> inlineVariants = new ArrayList<>();
        boolean allInlineObjects = true;
        for (int idx = 0; idx < items.size(); idx++) {
            Object it = items.get(idx);
            if (!(it instanceof Map<?, ?> item) || !"object".equals(item.get("type"))) {
                allInlineObjects = false;
                break;
            }
            Object propsValue = item.get("properties");
            Map<?, ?> props = truthy(propsValue) ? asMap(propsValue) : Map.of();
            Object discVal = null;
            if (props.get(propertyName) instanceof Map<?, ?> disc) {
                if (disc.containsKey("const")) {
                    discVal = disc.get("const");
                } else if (disc.get("enum") instanceof List<?> en && en.size() == 1) {
                    discVal = en.get(0);
                }
            }
            if (discVal == null) {
                allInlineObjects = false;
                break;
            }
            inlineVariants.add(new Variant(null, idx, pyStr(discVal)));
        }
        if (allInlineObjects && !inlineVariants.isEmpty()) {
            return new EnumInfo(propertyName, inlineVariants, true);
        }
        return null;
    }

    /** {@code _derive_enum_names}. */
    private static List<String> deriveEnumNames(String schemaName, List<PathStep> pathSteps, boolean rootHasAllof) {
        List<String> parts = new ArrayList<>();
        parts.add(schemaName);
        List<String> propOnlyParts = new ArrayList<>();
        for (PathStep step : pathSteps) {
            if (step.kind().equals("property") && step.name() != null && !step.name().isEmpty()) {
                String pas = Utils.snakeToPascal(step.name());
                parts.add(pas);
                propOnlyParts.add(pas);
            } else if (step.kind().equals("array_items")) {
                parts.add("Inner");
            }
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(String.join("", parts));
        if (pathSteps.size() == 1 && pathSteps.get(0).kind().equals("array_items")) {
            candidates.add(schemaName + "Inner");
        }
        if (rootHasAllof && !propOnlyParts.isEmpty()) {
            candidates.add(schemaName + "AllOf" + String.join("", propOnlyParts));
        }
        String noInner = parts.stream().filter(p -> !p.equals("Inner")).collect(Collectors.joining());
        if (!candidates.contains(noInner)) {
            candidates.add(noInner);
        }
        List<String> uniq = new ArrayList<>();
        for (String c : candidates) {
            if (!uniq.contains(c)) {
                uniq.add(c);
            }
        }
        return uniq;
    }

    /** {@code detect_empty_tagged_enums}, with its nested {@code resolve}, {@code walk} and {@code walk_node}. */
    private static Map<String, EnumInfo> detectEmptyTaggedEnums(Map<String, Object> spec) {
        Map<?, ?> components = spec.containsKey("components") ? asMap(spec.get("components")) : Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> schemas = components.containsKey("schemas")
                ? (Map<String, Object>) components.get("schemas") : Map.of();
        Walker walker = new Walker(schemas);
        for (Map.Entry<String, Object> e : schemas.entrySet()) {
            boolean hasAllof = e.getValue() instanceof Map<?, ?> m && m.containsKey("allOf");
            walker.walk(e.getKey(), e.getValue(), List.of(), hasAllof);
        }
        return walker.result;
    }

    /** The closure state of {@code detect_empty_tagged_enums}; {@code active_nodes} holds {@code id(node)}s. */
    private static final class Walker {
        final Map<String, Object> schemas;
        final Map<String, EnumInfo> result = new LinkedHashMap<>();
        final Set<Object> activeNodes = Collections.newSetFromMap(new IdentityHashMap<>());

        Walker(Map<String, Object> schemas) {
            this.schemas = schemas;
        }

        /** {@code resolve}. */
        Object resolve(Object node) {
            String ref = node instanceof Map ? getRefName(node) : null;
            if (ref != null && !ref.isEmpty()) {
                // schemas.get(ref, {}): a fresh dict each time, so it never trips the cycle check
                return schemas.containsKey(ref) ? schemas.get(ref) : new LinkedHashMap<>();
            }
            return node;
        }

        /** {@code walk}. */
        void walk(String schemaName, Object node, List<PathStep> path, boolean rootHasAllof) {
            node = resolve(node);
            if (!(node instanceof Map<?, ?> map)) {
                return;
            }
            if (!activeNodes.add(map)) {
                return;
            }
            try {
                walkNode(schemaName, map, path, rootHasAllof);
            } finally {
                activeNodes.remove(map);
            }
        }

        /** {@code walk_node}. */
        void walkNode(String schemaName, Map<?, ?> node, List<PathStep> path, boolean rootHasAllof) {
            EnumInfo extracted = extractDiscriminatedVariants(node, schemas);
            if (extracted != null) {
                String propertyName = extracted.propertyName();
                if (!extracted.inline()) {
                    for (String enumName : deriveEnumNames(schemaName, path, rootHasAllof)) {
                        result.put(enumName, new EnumInfo(propertyName, extracted.variants(), false));
                    }
                } else {
                    for (String enumName : deriveEnumNames(schemaName, path, rootHasAllof)) {
                        List<Variant> built = new ArrayList<>();
                        for (Variant v : extracted.variants()) {
                            int idx = v.index();
                            String suffix = idx == 0 ? "" : Integer.toString(idx);
                            built.add(new Variant(enumName + "AnyOf" + suffix, null, v.discriminatorValue()));
                        }
                        result.put(enumName, new EnumInfo(propertyName, built, false));
                    }
                }
            }

            for (String key : List.of("allOf", "oneOf", "anyOf")) {
                if (node.containsKey(key) && node.get(key) instanceof List<?> subs) {
                    for (Object sub : subs) {
                        if (sub instanceof Map<?, ?> subMap && subMap.containsKey("$ref")) {
                            String refName = getRefName(sub);
                            if (refName != null && !refName.isEmpty()) {
                                Object target = schemas.get(refName);
                                Map<?, ?> targetMap = truthy(target) ? asMap(target) : Map.of();
                                walk(refName, sub, path, targetMap.containsKey("allOf"));
                                continue;
                            }
                        }
                        walk(schemaName, sub, path, rootHasAllof);
                    }
                }
            }

            if ("object".equals(node.get("type"))) {
                Object props = node.containsKey("properties") ? node.get("properties") : Map.of();
                if (props instanceof Map<?, ?> propMap) {
                    for (Map.Entry<?, ?> e : propMap.entrySet()) {
                        walk(schemaName, e.getValue(), append(path, new PathStep("property", (String) e.getKey())),
                                rootHasAllof);
                    }
                }
            }

            if ("array".equals(node.get("type")) && node.containsKey("items")) {
                walk(schemaName, node.get("items"), append(path, new PathStep("array_items", null)), rootHasAllof);
            }
        }
    }

    // ---------------------------------------------------------------- file rewrites

    /** {@code fix_case_sensitivity_in_enum}. */
    private static boolean fixCaseSensitivityInEnum(Path enumFile, String content) throws IOException {
        boolean[] fixedAny = {false};
        String fixed = BOX_MODEL_WITH_DASH.matcher(content).replaceAll(m -> {
            String typeName = m.group(1);
            String fixedName = Utils.convertToRustTypeName(sanitize(typeName));
            if (!typeName.equals(fixedName)) {
                fixedAny[0] = true;
                return Matcher.quoteReplacement("Box<models::" + fixedName + ">");
            }
            return Matcher.quoteReplacement(m.group(0));
        });
        if (fixedAny[0]) {
            Files.writeString(enumFile, fixed);
            return true;
        }
        return false;
    }

    /**
     * {@code replace_enum_block}. The script matched the block up to the first {@code }}, which cuts an enum with
     * struct variants (a discriminated {@code anyOf} renders its variants inline) after the first variant and leaves
     * the rest behind as unparsable text; the block here ends at the brace that closes the enum.
     */
    static String replaceEnumBlock(String enumName, String content, String newEnumSrc) {
        // enum_name is interpolated unescaped into the pattern, as in the script
        Pattern pattern = Pattern.compile("(#[^\\n]*\\n)*\\s*pub enum " + enumName + " \\{",
                PY | Pattern.MULTILINE);
        Matcher m = pattern.matcher(content);
        if (!m.find()) {
            return null;
        }
        int end = Utils.closingBrace(content, m.end() - 1);
        if (end < 0) {
            return null;
        }
        return content.substring(0, m.start()) + newEnumSrc + content.substring(end + 1);
    }

    /**
     * The stock {@code impl Default} of a discriminated enum with struct variants builds the first variant with a
     * struct literal ({@code Self::A { id: Default::default(), .. }}); once the variants are rebuilt as tuple
     * variants that impl does not compile, and it goes, like the impls of the enums that were rendered empty.
     */
    static String dropStructVariantDefault(String enumName, String content) {
        Matcher header = Pattern.compile("impl Default for " + Pattern.quote(enumName) + " \\{", PY).matcher(content);
        if (!header.find()) {
            return content;
        }
        int end = Utils.closingBrace(content, header.end() - 1);
        if (end < 0 || !Pattern.compile("Self::\\w+ \\{", PY).matcher(content.substring(header.end(), end)).find()) {
            return content;
        }
        int from = end + 1;
        while (from < content.length() && Character.isWhitespace(content.charAt(from))) {
            from++;
        }
        return content.substring(0, header.start()) + content.substring(from);
    }

    /** The variant lines both branches of {@code fix_empty_enum} build; only the empty case upper-cases a lower first letter. */
    private static List<String> buildVariants(String enumName, EnumInfo enumInfo, boolean upperFirst,
                                              BoxPolicy boxPolicy) {
        List<String> variants = new ArrayList<>();
        for (Variant variant : enumInfo.variants()) {
            String schemaName = variant.schema();
            String rustTypeName = Utils.convertToRustTypeName(sanitize(schemaName));
            String variantName = schemaName;
            if (schemaName.startsWith(enumName)) {
                variantName = schemaName.substring(enumName.length());
            }
            if (variantName.isEmpty()) {
                variantName = schemaName;
            }
            variantName = Utils.convertToRustTypeName(sanitize(variantName));
            if (upperFirst && !variantName.isEmpty() && Utils.isLower(variantName.charAt(0))) {
                variantName = Character.toUpperCase(variantName.charAt(0)) + variantName.substring(1);
            }
            variants.add("    #[serde(rename = \"" + variant.discriminatorValue() + "\")]\n    " + variantName
                    + "(" + boxPolicy.type(enumName, rustTypeName) + "),");
        }
        return variants;
    }

    private static String buildEnum(String enumName, EnumInfo enumInfo, List<String> variants) {
        return "#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]\n#[serde(tag = \"" + enumInfo.propertyName()
                + "\")]\npub enum " + enumName + " {\n" + String.join("\n", variants) + "\n}";
    }

    /** {@code fix_empty_enum}. */
    private static boolean fixEmptyEnum(Path modelsDir, String enumName, EnumInfo enumInfo, BoxPolicy boxPolicy)
            throws IOException {
        Path enumFile = modelsDir.resolve(Utils.pascalToSnake(enumName) + ".rs");
        if (!Files.exists(enumFile)) {
            return false;
        }
        String content = Files.readString(enumFile);

        boolean isEmpty = Pattern.compile("pub enum " + enumName + " \\{\\s*\\}", PY).matcher(content).find();
        if (!isEmpty) {
            // note: '-' anywhere in the file, not only in the enum block
            boolean enumBlockHasDash = content.contains("pub enum " + enumName + " {") && content.contains("-");
            if (enumBlockHasDash) {
                String newEnum = buildEnum(enumName, enumInfo, buildVariants(enumName, enumInfo, false, boxPolicy));
                String replaced = replaceEnumBlock(enumName, content, newEnum);
                if (replaced != null) {
                    Files.writeString(enumFile, dropStructVariantDefault(enumName, replaced));
                    return true;
                }
            }
            return fixCaseSensitivityInEnum(enumFile, content);
        }

        List<String> variants = buildVariants(enumName, enumInfo, true, boxPolicy);
        if (variants.isEmpty()) {
            return false;
        }
        String newEnum = buildEnum(enumName, enumInfo, variants);
        Pattern empty = Pattern.compile("#\\[derive\\([^)]*\\)\\]\\s*(?:#\\[serde\\([^)]*\\)\\]\\s*)*pub enum " + enumName
                + " \\{\\s*\\}", PY | Pattern.MULTILINE | Pattern.DOTALL);
        content = empty.matcher(content).replaceAll(Matcher.quoteReplacement(Utils.pythonReplacementTemplate(newEnum)));
        Files.writeString(enumFile, content);
        return true;
    }

    /** {@code _type_file_exists}. */
    private static boolean typeFileExists(Path modelsDir, String rustTypeName) {
        return Files.exists(modelsDir.resolve(Utils.pascalToSnake(rustTypeName) + ".rs"));
    }

    /** {@code fallback_missing_variant_types}, with its nested {@code fix_block}. */
    private static int fallbackMissingVariantTypes(Path modelsDir) throws IOException {
        int changed = 0;
        for (Path filePath : rustFiles(modelsDir)) {
            String src = Files.readString(filePath);
            StringBuilder newSrc = new StringBuilder();
            int lastEnd = 0;
            Matcher m = ENUM_BLOCK.matcher(src);
            while (m.find()) {
                newSrc.append(src, lastEnd, m.start());
                newSrc.append(fixBlock(modelsDir, m.group()));
                lastEnd = m.end();
            }
            newSrc.append(src.substring(lastEnd));
            if (!newSrc.toString().equals(src)) {
                Files.writeString(filePath, newSrc.toString());
                changed++;
            }
        }
        return changed;
    }

    /** {@code fix_block} inside {@code fallback_missing_variant_types} (with {@code repl_box} and {@code repl_bare}). */
    private static String fixBlock(Path modelsDir, String block) {
        block = BOX_MODEL.matcher(block).replaceAll(m -> Matcher.quoteReplacement(
                typeFileExists(modelsDir, m.group(1)) ? m.group(0) : "serde_json::Value"));
        block = BARE_MODEL.matcher(block).replaceAll(m -> Matcher.quoteReplacement(
                typeFileExists(modelsDir, m.group(1)) ? m.group(0) : "serde_json::Value"));
        return block;
    }

    // ---------------------------------------------------------------- Python semantics helpers

    /** {@code models_dir.glob('*.rs')}, in sorted file-name order. */
    static List<Path> rustFiles(Path modelsDir) throws IOException {
        try (Stream<Path> s = Files.list(modelsDir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".rs") && Files.isRegularFile(p))
                    .sorted()
                    .toList();
        }
    }

    /** {@code ''.join(ch for ch in s if ch.isalnum() or ch == '_')}. */
    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().filter(cp -> Character.isLetterOrDigit(cp) || cp == '_').forEach(sb::appendCodePoint);
        return sb.toString();
    }

    private static List<PathStep> append(List<PathStep> path, PathStep step) {
        List<PathStep> next = new ArrayList<>(path);
        next.add(step);
        return next;
    }

    private static Map<?, ?> asMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            return m;
        }
        throw new IllegalStateException("expected a mapping, got " + value);
    }

    /** Python truthiness of a YAML value. */
    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        if (value instanceof List<?> l) {
            return !l.isEmpty();
        }
        return true;
    }

    /** Python {@code str()} of a YAML scalar. */
    private static String pyStr(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger) {
            return value.toString();
        }
        throw new IllegalStateException("unsupported discriminator value type: " + value.getClass() + " " + value);
    }

    /** Python {@code str.lower()} (ASCII input). */
    private static String pyLower(String s) {
        return s.toLowerCase(java.util.Locale.ROOT);
    }
}
