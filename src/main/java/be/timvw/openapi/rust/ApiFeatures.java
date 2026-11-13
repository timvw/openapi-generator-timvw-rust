package be.timvw.openapi.rust;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@code apiFeatures}: one Cargo feature per group of API operations, so that a crate compiles only the APIs, and the
 * models, it uses (see docs/guides/api-features.md).
 *
 * <p>A group is a tag by default: its feature is named after the tag's API module, without {@code _api}, in
 * kebab-case ({@code vector_stores_api} is {@code vector-stores}; untagged operations, {@code default_api}, are
 * {@code default-api}). {@code apiFeaturesFile} defines groups of its own, each selecting operations by tag and/or by
 * path prefix; an operation selected by no group keeps the feature of its tag, one selected by several is in each.</p>
 *
 * <p>Every group {@code g} has two features: {@code g-types}, its models alone, and {@code g}, which adds its API
 * functions and the HTTP client ({@code _api}). The models of a group are its operations' parameters, request bodies
 * and responses (errors included), with every schema they reach through {@code $ref}: properties, items, map values,
 * {@code allOf}/{@code oneOf}/{@code anyOf} and discriminator mappings. A model every group needs is not gated; one no
 * group needs is in {@code types} only.</p>
 *
 * <p>An API module whose operations all have the same features is gated as a whole in {@code apis/mod.rs}. When a
 * group takes part of a module (by path prefix), the module is gated by every feature of its operations and each
 * function, with its error enum and typed headers, by the features of its own operation.</p>
 */
final class ApiFeatures {

    /** On an API module (its OperationsMap): the cfg predicate of its {@code pub mod} line. */
    static final String MODULE_CFG = "x-rust-module-cfg";
    /** On an operation, when the module's cfg does not already say it: the cfg predicate of its items. */
    static final String OPERATION_CFG = "x-rust-operation-cfg";
    /**
     * On an API module with an operation gated on its own: the module allows unused imports, as a
     * feature set may compile none of the functions that use one.
     */
    static final String FUNCTION_GATED = "x-rust-function-gated";
    /** On a model: the cfg predicate of its {@code pub mod} and {@code pub use} lines; absent when ungated. */
    static final String MODEL_CFG = "x-rust-model-cfg";

    /** The feature every API feature enables: the HTTP client and {@code apis}. */
    static final String API = "_api";
    static final String TYPES = "types";
    static final String FULL = "full";
    static final String TYPES_SUFFIX = "-types";
    /** Feature names the generated Cargo.toml already uses. */
    static final Set<String> RESERVED = Set.of("default", FULL, TYPES, API, "native-tls", "rustls", "retry",
            "tracing");
    /** A feature name a group may have: Cargo's rules, lower case, not starting with {@code _}. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_+-]*");

    private ApiFeatures() {
    }

    /** A group of {@code apiFeaturesFile}: the feature name, the tags and the path prefixes it selects. */
    record Group(String name, List<String> tags, List<String> paths) {
    }

    // ---------------------------------------------------------------- the file

    /**
     * Reads the groups of an {@code apiFeaturesFile} (YAML or JSON): a map from feature name to {@code tags} and/or
     * {@code paths}, each a list of strings.
     *
     * <pre>
     * administration:
     *   tags: [Audit Logs, Projects]
     *   paths: [/organization]
     * audio: { tags: [Audio] }
     * </pre>
     */
    static List<Group> read(Path file) {
        JsonNode root;
        try {
            root = Yaml.mapper().readTree(Files.readString(file));
        } catch (IOException e) {
            throw new IllegalArgumentException(TimvwRustCodegen.GENERATOR_NAME + ": cannot read apiFeaturesFile "
                    + file + ": " + e.getMessage(), e);
        }
        return parse(root, file.toString());
    }

    static List<Group> parse(JsonNode root, String source) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return List.of();
        }
        if (!root.isObject()) {
            throw invalid(source, "expected a map from feature name to {tags, paths}");
        }
        List<Group> groups = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String name = e.getKey();
            if (!NAME.matcher(name).matches()) {
                throw invalid(source, "'" + name + "' is not a feature name: use lower-case letters, digits, '-', '_'"
                        + " or '+', starting with a letter or digit");
            }
            if (RESERVED.contains(name)) {
                throw invalid(source, "'" + name + "' is a feature the generated Cargo.toml already has; rename it");
            }
            JsonNode body = e.getValue();
            if (body == null || !body.isObject()) {
                throw invalid(source, name + ": expected a map with 'tags' and/or 'paths'");
            }
            for (Iterator<String> keys = body.fieldNames(); keys.hasNext(); ) {
                String key = keys.next();
                if (!key.equals("tags") && !key.equals("paths")) {
                    throw invalid(source, name + ": unknown key '" + key + "' (use 'tags' and/or 'paths')");
                }
            }
            List<String> tags = strings(body.get("tags"), source, name + ".tags");
            List<String> paths = strings(body.get("paths"), source, name + ".paths");
            if (tags.isEmpty() && paths.isEmpty()) {
                throw invalid(source, name + ": selects nothing; give it 'tags' and/or 'paths'");
            }
            for (String p : paths) {
                if (!p.startsWith("/")) {
                    throw invalid(source, name + ": path prefix '" + p + "' does not start with '/'");
                }
            }
            groups.add(new Group(name, tags, paths));
        }
        return groups;
    }

    private static List<String> strings(JsonNode node, String source, String where) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw invalid(source, where + ": expected a list");
        }
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isEmpty()) {
                throw invalid(source, where + ": expected strings, got " + item);
            }
            out.add(item.asText());
        }
        return out;
    }

    private static IllegalArgumentException invalid(String source, String message) {
        return new IllegalArgumentException(TimvwRustCodegen.GENERATOR_NAME + ": apiFeaturesFile " + source + ": "
                + message);
    }

    // ---------------------------------------------------------------- the plan

    /**
     * An operation as the generator groups it: once per tag, in the API module of that tag.
     *
     * @param module the API module's class name ({@code toApiName} of the sanitized tag)
     * @param tag the sanitized tag ({@code Default} when untagged)
     * @param rawTag the tag as the spec writes it ({@code null} when untagged)
     * @param path the path, or {@code null} for a webhook (keyed by its name)
     * @param key the webhook's name or the path, as {@code CodegenOperation.path} has it
     */
    record Op(String module, String tag, String rawTag, String path, String key, String method, Operation operation,
            List<io.swagger.v3.oas.models.parameters.Parameter> pathParameters) {
    }

    /** How the generator names things, so the plan agrees with the modules it writes. */
    record Naming(Function<String, String> sanitizeTag, Function<String, String> moduleName,
            Function<String, String> moduleFile) {
    }

    /** The features of every operation, API module and model. */
    static final class Plan {
        /** Every API feature, sorted. */
        final SortedSet<String> features = new TreeSet<>();
        /** The features of an operation, by {@link #opKey}. */
        final Map<String, SortedSet<String>> operations = new HashMap<>();
        /** The features of an API module, by class name. */
        final Map<String, SortedSet<String>> modules = new HashMap<>();
        /** The features whose types include a model, by schema name. */
        final Map<String, SortedSet<String>> models = new HashMap<>();
        /** What each feature selects, for the README: tags and path prefixes, by feature. */
        final Map<String, String> selects = new TreeMap<>();

        /** The cfg predicate of an API module, or {@code null} when the plan does not know it. */
        String moduleCfg(String module) {
            SortedSet<String> f = modules.get(module);
            return f == null || f.isEmpty() ? null : any(f, "");
        }

        /** The cfg predicate of an operation's items, or {@code null} when its module's cfg says it already. */
        String operationCfg(String module, String key, String method) {
            SortedSet<String> f = operations.get(opKey(module, key, method));
            if (f == null || f.equals(modules.get(module))) {
                return null;
            }
            return any(f, "");
        }

        /**
         * The cfg predicate of a model: the {@code -types} features of the groups that need it, {@code types} when
         * none does, {@code null} (ungated) when every group does.
         */
        String modelCfg(String schema) {
            SortedSet<String> f = models.get(schema);
            if (f == null || f.isEmpty()) {
                return "feature = \"" + TYPES + "\"";
            }
            if (f.equals(features)) {
                return null;
            }
            return any(f, TYPES_SUFFIX);
        }
    }

    static String opKey(String module, String key, String method) {
        return module + " " + method.toUpperCase(java.util.Locale.ROOT) + " " + key;
    }

    private static String any(Set<String> features, String suffix) {
        List<String> each = features.stream().map(f -> "feature = \"" + f + suffix + "\"").toList();
        return each.size() == 1 ? each.get(0) : "any(" + String.join(", ", each) + ")";
    }

    /**
     * The default feature of a tag: its module file without {@code _api}, in kebab-case; {@code -api} is appended to
     * a name the Cargo.toml already uses ({@code default_api} is {@code default-api}).
     */
    static String defaultFeature(String moduleFile) {
        String base = moduleFile.endsWith("_api") ? moduleFile.substring(0, moduleFile.length() - 4) : moduleFile;
        String name = base.replace('_', '-');
        return RESERVED.contains(name) ? name + "-api" : name;
    }

    /** The operations of the spec, as the generator groups them (one entry per tag). */
    static List<Op> operations(OpenAPI openAPI, Naming naming) {
        List<Op> ops = new ArrayList<>();
        if (openAPI.getPaths() != null) {
            openAPI.getPaths().forEach((path, item) -> add(ops, path, path, item, naming));
        }
        if (openAPI.getWebhooks() != null) {
            openAPI.getWebhooks().forEach((name, item) -> add(ops, null, name, item, naming));
        }
        return ops;
    }

    private static void add(List<Op> ops, String path, String key, PathItem item, Naming naming) {
        if (item == null) {
            return;
        }
        for (Map.Entry<PathItem.HttpMethod, Operation> e : item.readOperationsMap().entrySet()) {
            Operation operation = e.getValue();
            boolean untagged = operation.getTags() == null || operation.getTags().isEmpty();
            List<String> tags = untagged ? List.of("default") : operation.getTags();
            Set<String> seen = new HashSet<>();
            for (String raw : tags) {
                String tag = naming.sanitizeTag().apply(raw);
                if (seen.add(tag)) {
                    ops.add(new Op(naming.moduleName().apply(tag), tag, untagged ? null : raw, path, key,
                            e.getKey().name(), operation, item.getParameters()));
                }
            }
        }
    }

    /**
     * Assigns every operation its features and every model the features that need it.
     *
     * @throws IllegalArgumentException when a group selects a tag or path no operation has, or two features would
     *         share a name
     */
    static Plan plan(OpenAPI openAPI, List<Group> groups, Naming naming) {
        List<Op> ops = operations(openAPI, naming);
        Plan plan = new Plan();
        Map<String, Set<String>> defaultTags = new TreeMap<>();
        Map<String, List<String>> defaultOps = new TreeMap<>();
        Set<String> usedGroupTags = new HashSet<>();
        Set<String> usedPrefixes = new HashSet<>();
        /** The modules some of whose operations a group selects. */
        Set<String> grouped = new HashSet<>();
        Map<Op, SortedSet<String>> opFeatures = new LinkedHashMap<>();
        for (Op op : ops) {
            SortedSet<String> features = new TreeSet<>();
            for (Group g : groups) {
                for (String t : g.tags()) {
                    if (naming.sanitizeTag().apply(t).equals(op.tag())) {
                        features.add(g.name());
                        usedGroupTags.add(g.name() + "\0" + t);
                    }
                }
                for (String p : g.paths()) {
                    if (op.path() != null && underPrefix(op.path(), p)) {
                        features.add(g.name());
                        usedPrefixes.add(g.name() + "\0" + p);
                    }
                }
            }
            if (features.isEmpty()) {
                String f = defaultFeature(naming.moduleFile().apply(op.tag()));
                features.add(f);
                defaultTags.computeIfAbsent(f, k -> new TreeSet<>()).add(op.rawTag() == null ? "" : op.rawTag());
                defaultOps.computeIfAbsent(f, k -> new ArrayList<>()).add(op.method() + " " + op.key());
            } else {
                grouped.add(op.module());
            }
            opFeatures.put(op, features);
        }
        for (Group g : groups) {
            for (String t : g.tags()) {
                if (!usedGroupTags.contains(g.name() + "\0" + t)) {
                    throw new IllegalArgumentException(String.format("%s: apiFeaturesFile: feature '%s' selects tag "
                            + "'%s', which no operation has", TimvwRustCodegen.GENERATOR_NAME, g.name(), t));
                }
            }
            for (String p : g.paths()) {
                if (!usedPrefixes.contains(g.name() + "\0" + p)) {
                    throw new IllegalArgumentException(String.format("%s: apiFeaturesFile: feature '%s' selects "
                            + "path prefix '%s', under which there is no operation", TimvwRustCodegen.GENERATOR_NAME,
                            g.name(), p));
                }
            }
        }
        // Every feature name, with its -types twin, is used once.
        Map<String, String> names = new HashMap<>();
        for (String r : RESERVED) {
            names.put(r, "a feature the generated Cargo.toml already has");
        }
        for (Group g : groups) {
            claim(names, g.name(), "feature '" + g.name() + "' of apiFeaturesFile");
            plan.selects.put(g.name(), describe(g));
        }
        Map<String, String> defaultModules = new HashMap<>();
        for (Op op : ops) {
            if (opFeatures.get(op).size() == 1 && defaultTags.containsKey(opFeatures.get(op).first())) {
                defaultModules.put(opFeatures.get(op).first(), op.module());
            }
        }
        for (Map.Entry<String, Set<String>> e : defaultTags.entrySet()) {
            Set<String> raw = new TreeSet<>(e.getValue());
            boolean untagged = raw.remove("");
            String tags = (raw.isEmpty() ? "" : (raw.size() == 1 ? "tag " : "tags ") + String.join(", ", raw))
                    + (untagged ? (raw.isEmpty() ? "" : " and ") + "untagged operations" : "");
            List<String> some = defaultOps.get(e.getKey());
            claim(names, e.getKey(), "the feature of " + tags + " for the operations no group of apiFeaturesFile "
                    + "selects (" + String.join(", ", some.subList(0, Math.min(3, some.size())))
                    + (some.size() > 3 ? ", ..." : "") + ")");
            plan.selects.put(e.getKey(), tags + (grouped.contains(defaultModules.get(e.getKey()))
                    ? " (operations no group selects)" : ""));
        }
        plan.features.addAll(plan.selects.keySet());

        Refs refs = new Refs(openAPI);
        Map<String, Set<String>> featureModels = new HashMap<>();
        for (Map.Entry<Op, SortedSet<String>> e : opFeatures.entrySet()) {
            Op op = e.getKey();
            plan.operations.put(opKey(op.module(), op.key(), op.method()), e.getValue());
            plan.modules.computeIfAbsent(op.module(), k -> new TreeSet<>()).addAll(e.getValue());
            Set<String> schemas = refs.schemas(op);
            for (String f : e.getValue()) {
                featureModels.computeIfAbsent(f, k -> new HashSet<>()).addAll(schemas);
            }
        }
        featureModels.forEach((f, schemas) -> schemas.forEach(
                s -> plan.models.computeIfAbsent(s, k -> new TreeSet<>()).add(f)));
        return plan;
    }

    private static void claim(Map<String, String> names, String name, String what) {
        for (String n : List.of(name, name + TYPES_SUFFIX)) {
            String before = names.putIfAbsent(n, what);
            if (before != null) {
                throw new IllegalArgumentException(String.format("%s: apiFeatures: two features would be named '%s': "
                        + "%s, and %s; rename the group in apiFeaturesFile, or give the tag a group of its own",
                        TimvwRustCodegen.GENERATOR_NAME, n, before, what));
            }
        }
    }

    private static String describe(Group g) {
        List<String> parts = new ArrayList<>();
        if (!g.tags().isEmpty()) {
            parts.add((g.tags().size() == 1 ? "tag " : "tags ") + String.join(", ", g.tags()));
        }
        if (!g.paths().isEmpty()) {
            parts.add("paths under " + g.paths().stream().map(p -> "`" + p + "`").collect(Collectors.joining(", ")));
        }
        return String.join("; ", parts);
    }

    /** Whether {@code path} is {@code prefix} or below it: {@code /organization} covers {@code /organization/x}. */
    static boolean underPrefix(String path, String prefix) {
        if (prefix.endsWith("/")) {
            return path.startsWith(prefix) || path.equals(prefix.substring(0, prefix.length() - 1));
        }
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    // ---------------------------------------------------------------- schema references

    /** The component schemas an operation reaches through {@code $ref}, transitively. */
    static final class Refs {
        private static final String COMPONENTS = "#/components/";
        private static final String SCHEMAS = "#/components/schemas/";
        private final com.fasterxml.jackson.databind.ObjectMapper mapper;
        private final JsonNode components;
        /** The references each component makes directly, by its reference. */
        private final Map<String, Set<String>> direct = new HashMap<>();

        Refs(OpenAPI openAPI) {
            boolean v31 = openAPI.getOpenapi() != null
                    && new org.openapitools.codegen.utils.SemVer(openAPI.getOpenapi()).atLeast("3.1.0");
            mapper = v31 ? Json31.mapper() : Json.mapper();
            components = openAPI.getComponents() == null ? null : mapper.valueToTree(openAPI.getComponents());
        }

        Set<String> schemas(Op op) {
            Set<String> start = new HashSet<>();
            collect(mapper.valueToTree(op.operation()), start);
            if (op.pathParameters() != null) {
                collect(mapper.valueToTree(op.pathParameters()), start);
            }
            Set<String> seen = new HashSet<>();
            Deque<String> todo = new ArrayDeque<>(start);
            while (!todo.isEmpty()) {
                String ref = todo.pop();
                if (!seen.add(ref)) {
                    continue;
                }
                todo.addAll(direct.computeIfAbsent(ref, this::refsOf));
            }
            Set<String> out = new TreeSet<>();
            for (String ref : seen) {
                if (ref.startsWith(SCHEMAS)) {
                    out.add(unescape(ref.substring(SCHEMAS.length())));
                }
            }
            return out;
        }

        private Set<String> refsOf(String ref) {
            Set<String> out = new HashSet<>();
            if (components == null || !ref.startsWith(COMPONENTS)) {
                return out;
            }
            JsonNode node = components.at(ref.substring(COMPONENTS.length() - 1));
            if (!node.isMissingNode()) {
                collect(node, out);
            }
            return out;
        }

        /** Every {@code $ref} under {@code node}, and the targets of discriminator mappings. */
        static void collect(JsonNode node, Set<String> out) {
            if (node == null) {
                return;
            }
            if (node.isObject()) {
                for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> e = it.next();
                    JsonNode value = e.getValue();
                    if (e.getKey().equals("$ref") && value.isTextual()) {
                        out.add(value.asText());
                    } else if (e.getKey().equals("discriminator") && value.isObject()
                            && value.get("mapping") != null && value.get("mapping").isObject()) {
                        for (JsonNode target : value.get("mapping")) {
                            if (target.isTextual()) {
                                String t = target.asText();
                                out.add(t.startsWith("#") ? t : SCHEMAS + t);
                            }
                        }
                    } else {
                        collect(value, out);
                    }
                }
            } else if (node.isArray()) {
                for (JsonNode item : node) {
                    collect(item, out);
                }
            }
        }

        private static String unescape(String segment) {
            return segment.replace("~1", "/").replace("~0", "~");
        }
    }
}
