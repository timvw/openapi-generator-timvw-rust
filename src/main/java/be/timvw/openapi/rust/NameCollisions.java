package be.timvw.openapi.rust;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds schemas that would render to the same Rust model or file with different content (Milestone 3.3). The stock
 * generator writes such schemas to one file and keeps whichever comes last, so a client compiles and then fails to
 * decode; here generation stops and names both.
 *
 * <p>Schemas compare equal when they are equal apart from annotations (title, description, examples, external docs,
 * {@code deprecated}, extensions), following references, which may have different names as long as what they point at
 * compares equal. Such schemas can share a model. Everything else, validation keywords included, is a difference.</p>
 */
final class NameCollisions {

    /** Keywords that have no bearing on the Rust type a schema renders to. */
    private static final Set<String> ANNOTATIONS = new HashSet<>(Arrays.asList(
            "title", "description", "example", "examples", "externalDocs", "deprecated", "$comment", "xml"));

    /** Keywords whose value maps names to schemas: the names are data, not keywords. */
    private static final Set<String> SCHEMA_MAPS = new HashSet<>(Arrays.asList(
            "properties", "patternProperties", "$defs", "definitions", "dependentSchemas"));

    /** Keywords whose value is a schema or a list of schemas. Any other value is compared as data. */
    private static final Set<String> SUBSCHEMAS = new HashSet<>(Arrays.asList(
            "items", "additionalProperties", "not", "allOf", "anyOf", "oneOf", "prefixItems", "contains", "if", "then",
            "else", "propertyNames", "unevaluatedItems", "unevaluatedProperties", "contentSchema"));

    private static final String REF_PREFIX = "#/components/schemas/";
    private static final Pattern NUMBERED = Pattern.compile("^(.*)_\\d+$");

    private final boolean v31;
    @SuppressWarnings("rawtypes")
    private final Map<String, Schema> schemas;
    private final Map<String, JsonNode> trees = new HashMap<>();

    NameCollisions(OpenAPI openAPI) {
        v31 = openAPI.getOpenapi() != null
                && new org.openapitools.codegen.utils.SemVer(openAPI.getOpenapi()).atLeast("3.1.0");
        schemas = SpecRules.schemas(openAPI);
    }

    /**
     * Checks the planned models before the resolver runs. This is the only place that sees a schema the resolver
     * would overwrite: afterwards it is gone.
     *
     * @return one description per collision, empty when there is none
     */
    static List<String> beforeFlattening(OpenAPI openAPI, List<InlineNames.Site> sites,
                                         Function<String, String> modelName, Function<String, String> fileName) {
        return new NameCollisions(openAPI).check(sites, modelName, fileName);
    }

    /**
     * Checks the document the resolver produced, whose inline schemas are components now, for what naming could not
     * rule out or did not foresee: models that share a name, and models the resolver numbered.
     *
     * @return one description per collision, empty when there is none
     */
    @SuppressWarnings("rawtypes")
    static List<String> afterFlattening(OpenAPI openAPI, List<InlineNames.Site> planned,
                                        Function<String, String> modelName, Function<String, String> fileName) {
        Map<String, String> locations = new LinkedHashMap<>();
        for (InlineNames.Site site : planned) {
            locations.putIfAbsent(site.name(), site.location());
        }
        Map<String, Schema> components = SpecRules.schemas(openAPI);
        List<InlineNames.Site> sites = new ArrayList<>();
        List<String> numbered = new ArrayList<>();
        for (Map.Entry<String, Schema> e : components.entrySet()) {
            String location = locations.get(e.getKey());
            sites.add(new InlineNames.Site(e.getKey(),
                    location == null ? "(created by the inline-model resolver)" : location, e.getValue()));
            Matcher m = NUMBERED.matcher(e.getKey());
            if (location == null && m.matches() && components.containsKey(m.group(1))) {
                numbered.add("model " + e.getKey() + ": the inline-model resolver numbered a schema because the name "
                        + m.group(1) + " is taken by " + locations.getOrDefault(m.group(1), REF_PREFIX + m.group(1)));
            }
        }
        List<String> collisions = new NameCollisions(openAPI).check(sites, modelName, fileName);
        collisions.addAll(numbered);
        return collisions;
    }

    /** The first structural difference between two schemas of the document, or {@code null} when they have none. */
    @SuppressWarnings("rawtypes")
    String difference(Schema a, Schema b) {
        return schemaDifference(tree(a), tree(b), "", new HashSet<>());
    }

    private List<String> check(List<InlineNames.Site> sites, Function<String, String> modelName,
                               Function<String, String> fileName) {
        Map<String, List<InlineNames.Site>> byModel = new LinkedHashMap<>();
        Map<String, List<InlineNames.Site>> byFile = new LinkedHashMap<>();
        for (InlineNames.Site site : sites) {
            byModel.computeIfAbsent(modelName.apply(site.name()), k -> new ArrayList<>()).add(site);
            byFile.computeIfAbsent(fileName.apply(site.name()), k -> new ArrayList<>()).add(site);
        }
        List<String> collisions = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        for (Map.Entry<String, List<InlineNames.Site>> e : byModel.entrySet()) {
            describe("model " + e.getKey(), e.getValue(), collisions, reported);
        }
        for (Map.Entry<String, List<InlineNames.Site>> e : byFile.entrySet()) {
            describe("file src/models/" + e.getKey() + ".rs", e.getValue(), collisions, reported);
        }
        return collisions;
    }

    /** Compares every schema of a name with the first one: equality is transitive, so that finds every difference. */
    private void describe(String what, List<InlineNames.Site> sites, List<String> collisions, Set<String> reported) {
        if (sites.size() < 2) {
            return;
        }
        InlineNames.Site first = sites.get(0);
        for (InlineNames.Site other : sites.subList(1, sites.size())) {
            String difference = schemaDifference(tree(first.schema()), tree(other.schema()), "", new HashSet<>());
            if (difference != null && reported.add(first.location() + "|" + other.location())) {
                collisions.add(what + ": '" + first.name() + "' at " + first.location() + " and '" + other.name()
                        + "' at " + other.location() + " differ: " + difference);
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private JsonNode tree(Schema schema) {
        return v31 ? Json31.mapper().valueToTree(schema) : Json.mapper().valueToTree(schema);
    }

    private JsonNode component(String name) {
        return trees.computeIfAbsent(name, n -> schemas.containsKey(n) ? tree(schemas.get(n)) : null);
    }

    /** The first structural difference between two schemas, or {@code null} when they have none. */
    private String schemaDifference(JsonNode a, JsonNode b, String path, Set<String> assumedEqual) {
        String refA = ref(a);
        String refB = ref(b);
        if (refA != null || refB != null) {
            if (refA != null && refA.equals(refB)) {
                return null;
            }
            // Coming back to a pair under comparison: a cycle differs only if something else on it does.
            if (!assumedEqual.add(a + "|" + b)) {
                return null;
            }
            JsonNode targetA = refA == null ? a : component(refA);
            JsonNode targetB = refB == null ? b : component(refB);
            if (targetA == null || targetB == null) {
                return at(path) + "unresolved reference to " + (targetA == null ? refA : refB);
            }
            return schemaDifference(targetA, targetB, path, assumedEqual);
        }
        if (!a.isObject() || !b.isObject()) {
            return a.equals(b) ? null : at(path) + a + " vs " + b;
        }
        Set<String> keywords = new TreeSet<>();
        a.fieldNames().forEachRemaining(keywords::add);
        b.fieldNames().forEachRemaining(keywords::add);
        for (String keyword : keywords) {
            if (ANNOTATIONS.contains(keyword) || keyword.startsWith("x-")) {
                continue;
            }
            JsonNode x = a.get(keyword);
            JsonNode y = b.get(keyword);
            String here = path + "/" + keyword;
            String found;
            if (x == null || y == null) {
                found = at(path) + "keyword " + keyword + " only in the " + (x == null ? "second" : "first");
            } else if ("required".equals(keyword) || "type".equals(keyword)) {
                found = members(x).equals(members(y)) ? null : at(here) + x + " vs " + y;
            } else if ("discriminator".equals(keyword)) {
                found = discriminatorDifference(x, y, here);
            } else if (SCHEMA_MAPS.contains(keyword)) {
                found = mapDifference(x, y, here, assumedEqual);
            } else if (SUBSCHEMAS.contains(keyword)) {
                found = subschemaDifference(x, y, here, assumedEqual);
            } else {
                found = x.equals(y) ? null : at(here) + x + " vs " + y;
            }
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private String subschemaDifference(JsonNode a, JsonNode b, String path, Set<String> assumedEqual) {
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return at(path) + a.size() + " vs " + b.size() + " schemas";
            }
            for (int i = 0; i < a.size(); i++) {
                String found = schemaDifference(a.get(i), b.get(i), path + "/" + i, assumedEqual);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        return schemaDifference(a, b, path, assumedEqual);
    }

    private String mapDifference(JsonNode a, JsonNode b, String path, Set<String> assumedEqual) {
        Set<String> names = new TreeSet<>();
        a.fieldNames().forEachRemaining(names::add);
        b.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            JsonNode x = a.get(name);
            JsonNode y = b.get(name);
            if (x == null || y == null) {
                return at(path) + name + " only in the " + (x == null ? "second" : "first");
            }
            String found = schemaDifference(x, y, path + "/" + name, assumedEqual);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The branches a mapping points at are compared through {@code oneOf}/{@code anyOf}; here only the tags count. */
    private static String discriminatorDifference(JsonNode a, JsonNode b, String path) {
        if (!a.path("propertyName").equals(b.path("propertyName"))) {
            return at(path) + a.path("propertyName") + " vs " + b.path("propertyName");
        }
        Set<String> x = new TreeSet<>();
        Set<String> y = new TreeSet<>();
        a.path("mapping").fieldNames().forEachRemaining(x::add);
        b.path("mapping").fieldNames().forEachRemaining(y::add);
        return x.equals(y) ? null : at(path + "/mapping") + x + " vs " + y;
    }

    private static String at(String path) {
        return path.isEmpty() ? "" : path.substring(1) + ": ";
    }

    private static Set<String> members(JsonNode node) {
        Set<String> members = new TreeSet<>();
        if (node.isArray()) {
            node.forEach(n -> members.add(n.asText()));
        } else {
            members.add(node.asText());
        }
        return members;
    }

    private static String ref(JsonNode node) {
        JsonNode ref = node.isObject() ? node.get("$ref") : null;
        return ref != null && ref.isTextual() && ref.asText().startsWith(REF_PREFIX)
                ? ref.asText().substring(REF_PREFIX.length()) : null;
    }
}
