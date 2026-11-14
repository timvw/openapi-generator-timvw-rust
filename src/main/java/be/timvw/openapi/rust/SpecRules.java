package be.timvw.openapi.rust;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Spec-level rules applied in {@code preprocessOpenAPI}, before titles are stripped and inline schemas are named
 * (Milestone 2.3). They replace the spec patch scripts openai-client-base ran before generation
 * ({@code route_webhook_apis.py}, {@code fix_model_fields.py}, {@code patch_spec_rust_compat.py}).
 *
 * <p>Two kinds of rule live here. The <em>generic</em> ones repair constructs the stock generator mishandles and run
 * on every spec: {@link #pruneGeneratorHostileKeywords}, {@link #stripRefSiblings},
 * {@link #collapseRequiredOnlyUnions}, {@link #titleUnnamedUnionVariants}, {@link #hoistInlineUnions},
 * {@link #inlineStringRefsInUnions}, {@link #normalizeHyphenatedSchemaNames} and
 * {@link #simplifyObjectQueryParameters}. The <em>spec decisions</em> are driven by options and name the schemas
 * or properties they apply to: {@link #routeWebhooks}, {@link #stringSchemas}, {@link #stringIdProperties},
 * {@link #stringProperties}, {@link #optionalProperties} and the deep-object allow list of
 * {@link #simplifyObjectQueryParameters}.</p>
 *
 * <p>Every rule reports what it changed through a {@code Consumer<String>} so the generator can log it.</p>
 */
final class SpecRules {

    static final String SCHEMA_REF_PREFIX = "#/components/schemas/";

    /** Marks a parameter {@link #unwrapNullableParameters} gave the non-null branch of its union. */
    static final String NULL_BRANCH = "x-rust-null-branch";

    private SpecRules() {
    }

    // ---------------------------------------------------------------- option values

    /**
     * Splits an option value into entries. A config-file value is a list whose elements are taken as they are; a
     * CLI value is one string whose entries are separated by {@code |}, {@code ;} or whitespace (a comma would end
     * the {@code --additional-properties} value), so an underscore in such an entry stands for a space.
     */
    static List<String> entries(Object value) {
        return entries(value, true);
    }

    /**
     * Splits an option value of {@code Schema.property} entries as {@link #entries(Object)} does, but keeps the
     * underscores of a CLI value: property names such as {@code cache_write_tokens} are written with them.
     */
    static List<String> propertyEntries(Object value) {
        return entries(value, false);
    }

    private static List<String> entries(Object value, boolean underscoreIsSpace) {
        List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        if (value instanceof Collection) {
            for (Object o : (Collection<?>) value) {
                if (o != null && !String.valueOf(o).isEmpty()) {
                    out.add(String.valueOf(o));
                }
            }
            return out;
        }
        for (String part : String.valueOf(value).split("[|;\\s]+")) {
            if (!part.isEmpty()) {
                out.add(underscoreIsSpace ? part.replace('_', ' ') : part);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- webhooks

    /**
     * Tags every webhook operation with {@code tag}, so that webhooks are generated into their own API module
     * instead of the module of their own tag ({@code default_api} when untagged), next to its path operations.
     *
     * @throws IllegalStateException when a path operation already carries the tag
     */
    static int routeWebhooks(OpenAPI openAPI, String tag) {
        if (openAPI.getPaths() != null) {
            for (Map.Entry<String, PathItem> e : openAPI.getPaths().entrySet()) {
                for (Map.Entry<PathItem.HttpMethod, Operation> op : e.getValue().readOperationsMap().entrySet()) {
                    if (op.getValue().getTags() != null && op.getValue().getTags().contains(tag)) {
                        throw new IllegalStateException(String.format(
                                "webhookModule tag '%s' is already used by path operation %s %s",
                                tag, op.getKey(), e.getKey()));
                    }
                }
            }
        }
        int changed = 0;
        if (openAPI.getWebhooks() != null) {
            for (PathItem item : openAPI.getWebhooks().values()) {
                for (Operation op : item.readOperationsMap().values()) {
                    if (op.getTags() == null || !op.getTags().contains(tag)) {
                        op.addTagsItem(tag);
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    // ---------------------------------------------------------------- spec decisions

    /**
     * Replaces each named component schema by {@code type: string}, keeping its description. An entry reads
     * {@code Name} or {@code Name=fallback description}, the fallback documenting a schema that had none. A schema
     * that is already a plain string is left alone, so the pass can run again after unions were hoisted into
     * components (a hoisted union may carry one of the names).
     */
    @SuppressWarnings("rawtypes")
    static void stringSchemas(OpenAPI openAPI, Collection<String> entries, Consumer<String> log) {
        Map<String, Schema> schemas = schemas(openAPI);
        for (String entry : entries) {
            int eq = entry.indexOf('=');
            String name = eq < 0 ? entry : entry.substring(0, eq);
            String fallback = eq < 0 ? null : entry.substring(eq + 1);
            Schema old = schemas.get(name);
            if (old == null) {
                continue;
            }
            if (old instanceof StringSchema && old.getEnum() == null) {
                continue;
            }
            StringSchema replacement = new StringSchema();
            replacement.setDescription(old.getDescription() != null ? old.getDescription() : fallback);
            schemas.put(name, replacement);
            log.accept("stringIdSchemas: " + name + " is now type: string");
        }
    }

    /** The schema names of {@link #stringSchemas} entries, without their fallback descriptions. */
    static List<String> names(Collection<String> entries) {
        List<String> out = new ArrayList<>();
        for (String entry : entries) {
            int eq = entry.indexOf('=');
            out.add(eq < 0 ? entry : entry.substring(0, eq));
        }
        return out;
    }

    /**
     * Renders the named properties as {@code String} wherever they are typed by an id schema: a property that
     * references one of {@code stringSchemas}, or that is a union ({@code allOf}/{@code oneOf}/{@code anyOf}) of
     * them, becomes {@code type: string}; a schema that inherits the property through {@code allOf} gets it
     * redeclared as a string in its own object component. An entry reads {@code name} or
     * {@code name=fallback description}; the fallback documents a property that had no description.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void stringIdProperties(OpenAPI openAPI, Collection<String> entries, Collection<String> stringSchemas,
                                   Consumer<String> log) {
        Map<String, Schema> schemas = schemas(openAPI);
        for (String entry : entries) {
            int eq = entry.indexOf('=');
            String name = eq < 0 ? entry : entry.substring(0, eq);
            String fallback = eq < 0 ? null : entry.substring(eq + 1);

            // Inherited through allOf: redeclare in the first object component.
            for (Map.Entry<String, Schema> e : schemas.entrySet()) {
                Schema schema = e.getValue();
                if (schema.getAllOf() == null) {
                    continue;
                }
                boolean inherited = false;
                for (Object item : schema.getAllOf()) {
                    Schema s = (Schema) item;
                    if (s.get$ref() != null) {
                        Schema parent = schemas.get(refName(s.get$ref()));
                        if (parent != null && parent.getProperties() != null && parent.getProperties().containsKey(name)) {
                            inherited = true;
                            break;
                        }
                    }
                }
                if (!inherited) {
                    continue;
                }
                for (Object item : schema.getAllOf()) {
                    Schema s = (Schema) item;
                    if ("object".equals(singleType(s))) {
                        if (s.getProperties() == null) {
                            s.setProperties(new LinkedHashMap<>());
                        }
                        s.getProperties().put(name, string(fallback));
                        log.accept("stringIdProperties: " + e.getKey() + "." + name + " redeclared as string in its allOf");
                        break;
                    }
                }
            }

            // Declared directly.
            for (Map.Entry<String, Schema> e : schemas.entrySet()) {
                Map<String, Schema> props = e.getValue().getProperties();
                if (props == null || !props.containsKey(name)) {
                    continue;
                }
                Schema prop = props.get(name);
                boolean refToId = prop.get$ref() != null && stringSchemas.contains(refName(prop.get$ref()));
                boolean union = prop.getAllOf() != null || prop.getOneOf() != null || prop.getAnyOf() != null;
                if (refToId || union) {
                    props.put(name, string(prop.getDescription() != null ? prop.getDescription() : fallback));
                    log.accept("stringIdProperties: " + e.getKey() + "." + name + " is now type: string");
                }
            }
        }
    }

    /**
     * Renders {@code Schema.property} entries as a bare {@code type: string}, directly or in an allOf component. A
     * property that can be null ({@code nullable}, {@code null} among its types or a {@code null} branch, as
     * {@link #nullableItems} reads it) stays nullable: a required one is an {@code Option<String>} that decodes
     * {@code null}, not a {@code String} that fails on it.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void stringProperties(OpenAPI openAPI, Collection<String> entries, Consumer<String> log) {
        Map<String, Schema> schemas = schemas(openAPI);
        for (String entry : entries) {
            int dot = entry.lastIndexOf('.');
            if (dot < 0) {
                throw new IllegalArgumentException("stringProperties entry must be Schema.property: " + entry);
            }
            String schemaName = entry.substring(0, dot);
            String prop = entry.substring(dot + 1);
            Schema schema = schemas.get(schemaName);
            if (schema == null) {
                log.accept("stringProperties: no component schema named " + schemaName);
                continue;
            }
            boolean done = false;
            if (schema.getProperties() != null && schema.getProperties().containsKey(prop)) {
                schema.getProperties().put(prop, string((Schema) schema.getProperties().get(prop), schemas));
                done = true;
            }
            if (schema.getAllOf() != null) {
                for (Object item : schema.getAllOf()) {
                    Schema s = (Schema) item;
                    if (s.getProperties() != null && s.getProperties().containsKey(prop)) {
                        s.getProperties().put(prop, string((Schema) s.getProperties().get(prop), schemas));
                        done = true;
                    }
                }
            }
            log.accept(done ? "stringProperties: " + entry + " is now type: string"
                    : "stringProperties: " + entry + " not found");
        }
    }

    /** A {@code type: string} in place of {@code original}, nullable when {@code original} can be null. */
    @SuppressWarnings("rawtypes")
    private static Schema string(Schema original, Map<String, Schema> schemas) {
        StringSchema string = new StringSchema();
        if (nullableItems(original, schemas)) {
            // nullable: true for 3.0, null among the types for 3.1
            string.setNullable(true);
            string.setTypes(new LinkedHashSet<>(List.of("string", "null")));
        }
        return string;
    }

    /**
     * Makes {@code Schema.property} entries optional, for properties a spec marks required that the API omits: the
     * name is removed from the {@code required} list of the schema and of every inline {@code allOf} part that lists
     * it (the addressing of {@link #stringProperties}). The property schema is left as it is, so a nullable one stays
     * nullable and becomes an optional nullable property.
     *
     * @throws IllegalArgumentException when an entry is not {@code Schema.property}, or names no component schema or
     *                                  no property declared or required by it or by its inline {@code allOf} parts
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void optionalProperties(OpenAPI openAPI, Collection<String> entries, Consumer<String> log) {
        Map<String, Schema> schemas = schemas(openAPI);
        for (String entry : entries) {
            int dot = entry.lastIndexOf('.');
            if (dot < 0) {
                throw new IllegalArgumentException(TimvwRustCodegen.GENERATOR_NAME
                        + ": optionalProperties entry must be Schema.property: " + entry);
            }
            Schema schema = schemas.get(entry.substring(0, dot));
            String prop = entry.substring(dot + 1);
            List<Schema> parts = new ArrayList<>();
            if (schema != null) {
                parts.add(schema);
                if (schema.getAllOf() != null) {
                    for (Object item : schema.getAllOf()) {
                        if (((Schema) item).get$ref() == null) {
                            parts.add((Schema) item);
                        }
                    }
                }
            }
            boolean found = false;
            boolean removed = false;
            for (Schema part : parts) {
                if (part.getProperties() != null && part.getProperties().containsKey(prop)) {
                    found = true;
                }
                if (part.getRequired() != null && part.getRequired().contains(prop)) {
                    found = true;
                    removed = true;
                    List<String> required = new ArrayList<>(part.getRequired());
                    required.remove(prop);
                    part.setRequired(required.isEmpty() ? null : required);
                }
            }
            if (!found) {
                throw new IllegalArgumentException(String.format("%s: optionalProperties: %s names no property of a "
                        + "component schema (nor of its inline allOf parts); remove it or correct its name",
                        TimvwRustCodegen.GENERATOR_NAME, entry));
            }
            log.accept(removed ? "optionalProperties: " + entry + " is no longer required"
                    : "optionalProperties: " + entry + " was not required");
        }
    }

    /** Renders {@code Schema.property} entries as a file ({@code type: string, format: binary}). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void fileProperties(OpenAPI openAPI, Collection<String> entries, Consumer<String> log) {
        Map<String, Schema> schemas = schemas(openAPI);
        for (String entry : entries) {
            int dot = entry.lastIndexOf('.');
            if (dot < 0) {
                throw new IllegalArgumentException("fileProperties entry must be Schema.property: " + entry);
            }
            Schema schema = schemas.get(entry.substring(0, dot));
            String prop = entry.substring(dot + 1);
            if (schema == null || schema.getProperties() == null || !schema.getProperties().containsKey(prop)) {
                log.accept("fileProperties: " + entry + " not found");
                continue;
            }
            Schema old = (Schema) schema.getProperties().get(prop);
            StringSchema file = new StringSchema();
            file.setFormat("binary");
            file.setDescription(old.getDescription());
            schema.getProperties().put(prop, file);
            log.accept("fileProperties: " + entry + " is now a file");
        }
    }

    // ---------------------------------------------------------------- generic rules

    /** Removes {@code default} and {@code propertyNames} from every schema under components. */
    static int pruneGeneratorHostileKeywords(OpenAPI openAPI) {
        int[] n = {0};
        forEachComponentSchema(openAPI, s -> {
            if (s.getDefault() != null || flag(s, "getDefaultSetFlag")) {
                s.setDefault(null);
                clearFlag(s, "setDefaultSetFlag");
                n[0]++;
            }
            if (s.getPropertyNames() != null) {
                s.setPropertyNames(null);
                n[0]++;
            }
        });
        return n[0];
    }

    /**
     * A {@code $ref} with sibling keywords keeps only the {@code $ref}. (A 3.1 parse that resolves references
     * otherwise turns such a schema into an {@code allOf} wrapper, whose variant the generator cannot name.)
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static int stripRefSiblings(OpenAPI openAPI) {
        int[] n = {0};
        forEachSchema(openAPI, s -> {
            if (s.get$ref() == null) {
                return;
            }
            com.fasterxml.jackson.databind.JsonNode node = io.swagger.v3.core.util.Json31.mapper().valueToTree(s);
            if (node.size() <= 1) {
                return;
            }
            String ref = s.get$ref();
            // Reset every field to the state of a fresh schema, then put the reference back.
            Schema bare = new Schema();
            for (java.lang.reflect.Field f : fields(Schema.class)) {
                try {
                    f.set(s, f.get(bare));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
            s.set$ref(ref);
            n[0]++;
        });
        return n[0];
    }

    private static List<java.lang.reflect.Field> fields(Class<?> type) {
        List<java.lang.reflect.Field> out = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    out.add(f);
                }
            }
        }
        return out;
    }

    /** Drops a {@code oneOf}/{@code anyOf} whose every branch only states {@code required} constraints. */
    static int collapseRequiredOnlyUnions(OpenAPI openAPI) {
        int[] n = {0};
        forEachSchema(openAPI, s -> {
            if (requiredOnly(s.getOneOf())) {
                s.setOneOf(null);
                n[0]++;
            }
            if (requiredOnly(s.getAnyOf())) {
                s.setAnyOf(null);
                n[0]++;
            }
        });
        return n[0];
    }

    @SuppressWarnings("rawtypes")
    private static boolean requiredOnly(List<Schema> items) {
        if (items == null || items.isEmpty()) {
            return false;
        }
        for (Schema item : items) {
            if (item.get$ref() != null || item.getType() != null || (item.getTypes() != null && !item.getTypes().isEmpty())
                    || item.getProperties() != null || item.getTitle() != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Gives an untitled, non-reference branch of a {@code oneOf}/{@code anyOf} the title the variant is named
     * after: {@code Text}, {@code Integer}, {@code Number}, {@code Boolean}, {@code ArrayOf<Item>s} (the item's
     * schema name or capitalized type; {@code ArrayOfNullable<Item>s} when the items can be null, as they are then
     * {@code Option}s) or {@code Object<index>}.
     */
    static int titleUnnamedUnionVariants(OpenAPI openAPI) {
        Map<String, Schema> schemas = schemas(openAPI);
        int[] n = {0};
        forEachSchema(openAPI, s -> {
            n[0] += titleVariants(s.getOneOf(), schemas);
            n[0] += titleVariants(s.getAnyOf(), schemas);
        });
        return n[0];
    }

    /**
     * Whether array items of {@code schema} can be null, as {@code RustCodegenBase#isNullable} decides it: the flag,
     * {@code null} among the types or as a branch, through references and single-entry {@code allOf} wrappers.
     */
    @SuppressWarnings("rawtypes")
    static boolean nullableItems(Schema schema, Map<String, Schema> schemas) {
        Schema s = schema;
        for (int depth = 0; s != null && depth < 16; depth++) {
            if (Boolean.TRUE.equals(s.getNullable()) || s.getTypes() != null && s.getTypes().contains("null")
                    || nullBranch(s.getOneOf()) || nullBranch(s.getAnyOf())) {
                return true;
            }
            if (s.get$ref() != null) {
                s = schemas.get(refName(s.get$ref()));
            } else if (s.getAllOf() != null && s.getAllOf().size() == 1 && s.getOneOf() == null
                    && s.getAnyOf() == null) {
                s = (Schema) s.getAllOf().get(0);
            } else {
                return false;
            }
        }
        return false;
    }

    @SuppressWarnings("rawtypes")
    private static boolean nullBranch(List<Schema> branches) {
        return branches != null && branches.stream().anyMatch(b -> b != null && b.get$ref() == null
                && ("null".equals(b.getType()) || b.getTypes() != null && b.getTypes().equals(Set.of("null"))));
    }

    @SuppressWarnings("rawtypes")
    private static int titleVariants(List<Schema> items, Map<String, Schema> schemas) {
        if (items == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < items.size(); i++) {
            Schema item = items.get(i);
            if (item.getTitle() != null || item.get$ref() != null) {
                continue;
            }
            String type = singleType(item);
            String title = null;
            if ("string".equals(type)) {
                title = "Text";
            } else if ("integer".equals(type)) {
                title = "Integer";
            } else if ("number".equals(type)) {
                title = "Number";
            } else if ("boolean".equals(type)) {
                title = "Boolean";
            } else if ("array".equals(type)) {
                Schema inner = item.getItems();
                String innerName;
                if (inner != null && inner.get$ref() != null) {
                    innerName = refName(inner.get$ref());
                } else {
                    String innerType = inner == null ? null : singleType(inner);
                    innerName = capitalize(innerType == null ? "item" : innerType);
                }
                boolean nullable = inner != null && nullableItems(inner, schemas);
                title = "ArrayOf" + (nullable ? "Nullable" : "") + innerName + "s";
            } else if ("object".equals(type)) {
                title = "Object" + i;
            }
            if (title != null) {
                item.setTitle(title);
                n++;
            }
        }
        return n;
    }

    /**
     * Moves an inline {@code oneOf}/{@code anyOf} of a component property into a component schema named
     * {@code <Schema><Property>} and references it, when every branch is inline (no {@code $ref}), none is
     * {@code type: null} and at least one is an array or object. The stock generator fails on the flattened
     * composed model of such a property. The union keeps the property's {@code nullable}, so that the field stays
     * nullable.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static List<String> hoistInlineUnions(OpenAPI openAPI) {
        List<String> hoisted = new ArrayList<>();
        Map<String, Schema> schemas = schemas(openAPI);
        Map<String, Schema> added = new LinkedHashMap<>();
        for (Map.Entry<String, Schema> e : schemas.entrySet()) {
            Map<String, Schema> props = e.getValue().getProperties();
            if (props == null) {
                continue;
            }
            for (Map.Entry<String, Schema> p : props.entrySet()) {
                Schema prop = p.getValue();
                for (boolean oneOf : new boolean[] {true, false}) {
                    List<Schema> items = oneOf ? prop.getOneOf() : prop.getAnyOf();
                    if (items == null || items.isEmpty()) {
                        continue;
                    }
                    boolean hasRef = false;
                    boolean hasNull = false;
                    boolean hasComplex = false;
                    for (Schema item : items) {
                        if (item.get$ref() != null) {
                            hasRef = true;
                        }
                        String type = singleType(item);
                        if ("null".equals(type)) {
                            hasNull = true;
                        }
                        if ("array".equals(type) || "object".equals(type)) {
                            hasComplex = true;
                        }
                    }
                    if (hasRef || hasNull || !hasComplex) {
                        continue;
                    }
                    String name = e.getKey() + capitalizeParts(p.getKey());
                    Schema union = new Schema();
                    if (oneOf) {
                        union.setOneOf(items);
                    } else {
                        union.setAnyOf(items);
                    }
                    union.setTitle(name);
                    union.setDescription(prop.getDescription());
                    if (Boolean.TRUE.equals(prop.getNullable())
                            || (prop.getTypes() != null && prop.getTypes().contains("null"))) {
                        union.setNullable(true);
                    }
                    added.put(name, union);
                    p.setValue(new Schema().$ref(SCHEMA_REF_PREFIX + name));
                    hoisted.add(e.getKey() + "." + p.getKey() + " -> " + name);
                    break;
                }
            }
        }
        schemas.putAll(added);
        return hoisted;
    }

    /**
     * Replaces, in the top-level {@code allOf}/{@code oneOf}/{@code anyOf} of a component schema, a reference to a
     * component that is a plain string (no enum) by an inline {@code type: string}, so that no variant is named
     * after a schema that renders to {@code String}.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static int inlineStringRefsInUnions(OpenAPI openAPI) {
        Map<String, Schema> schemas = schemas(openAPI);
        Set<String> stringSchemas = new LinkedHashSet<>();
        for (Map.Entry<String, Schema> e : schemas.entrySet()) {
            if ("string".equals(singleType(e.getValue())) && e.getValue().getEnum() == null) {
                stringSchemas.add(e.getKey());
            }
        }
        int n = 0;
        for (Schema schema : schemas.values()) {
            for (List<Schema> branches : Arrays.asList(schema.getAnyOf(), schema.getOneOf(), schema.getAllOf())) {
                if (branches == null) {
                    continue;
                }
                for (int i = 0; i < branches.size(); i++) {
                    Schema b = branches.get(i);
                    if (b.get$ref() != null && stringSchemas.contains(refName(b.get$ref()))) {
                        StringSchema s = new StringSchema();
                        s.setDescription(b.getDescription());
                        s.setTitle(b.getTitle());
                        branches.set(i, s);
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private static final Pattern HYPHEN_DIGITS = Pattern.compile("-(\\d+)");

    /**
     * Renames component schemas whose name contains a hyphen ({@code ConversationParam-2} becomes
     * {@code ConversationParam2}, other hyphens become underscores) and updates every {@code $ref}.
     */
    @SuppressWarnings("rawtypes")
    static String withoutHyphens(String name) {
        return HYPHEN_DIGITS.matcher(name).replaceAll("$1").replace('-', '_');
    }

    static List<String> normalizeHyphenatedSchemaNames(OpenAPI openAPI) {
        List<String> renamed = new ArrayList<>();
        Map<String, Schema> schemas = schemas(openAPI);
        Map<String, String> renames = new LinkedHashMap<>();
        for (String name : new ArrayList<>(schemas.keySet())) {
            if (!name.contains("-")) {
                continue;
            }
            String newName = withoutHyphens(name);
            if (schemas.containsKey(newName)) {
                throw new IllegalStateException("renaming schema " + name + " to " + newName + " collides with an existing schema");
            }
            renames.put(SCHEMA_REF_PREFIX + name, SCHEMA_REF_PREFIX + newName);
            schemas.put(newName, schemas.remove(name));
            renamed.add(name + " -> " + newName);
        }
        if (!renames.isEmpty()) {
            forEachSchema(openAPI, s -> {
                String to = s.get$ref() == null ? null : renames.get(s.get$ref());
                if (to != null) {
                    s.set$ref(to);
                }
            });
        }
        return renamed;
    }

    /**
     * Query parameters typed as an object (inline, or a reference to an object schema) become strings; the allow
     * list ({@code <path>?<name>}) names the parameters that are kept as objects and sent as {@code deepObject}
     * (a nullable type loses its null), and so is an object the spec declares with {@code style: deepObject}. A
     * {@code deepObject} style left on anything that is not an object becomes {@code form}. With {@code resetStyle}, every other query parameter gets the defaults a
     * parameter without {@code style}/{@code explode} is parsed with ({@code form}, exploded), so arrays are sent as
     * repeated parameters whatever the spec said.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static List<String> simplifyObjectQueryParameters(OpenAPI openAPI, Collection<String> deepObject,
                                                      boolean resetStyle) {
        List<String> changed = new ArrayList<>();
        if (openAPI.getPaths() == null) {
            return changed;
        }
        Map<String, Schema> schemas = schemas(openAPI);
        for (Map.Entry<String, PathItem> e : openAPI.getPaths().entrySet()) {
            String path = e.getKey();
            List<List<Parameter>> containers = new ArrayList<>();
            containers.add(e.getValue().getParameters());
            for (Operation op : e.getValue().readOperationsMap().values()) {
                containers.add(op.getParameters());
            }
            for (List<Parameter> params : containers) {
                if (params == null) {
                    continue;
                }
                for (Parameter p : params) {
                    if (!"query".equals(p.getIn()) || p.getSchema() == null) {
                        continue;
                    }
                    Schema schema = p.getSchema();
                    boolean object = "object".equals(singleType(schema));
                    if (!object && schema.get$ref() != null) {
                        Schema target = schemas.get(refName(schema.get$ref()));
                        object = target != null && isObjectLike(target);
                    }
                    // listed, or an object the spec itself sends in deepObject style: kept as an object
                    boolean declared = object && p.getStyle() == Parameter.StyleEnum.DEEPOBJECT;
                    if (deepObject.contains(path + "?" + p.getName()) || declared) {
                        p.setStyle(Parameter.StyleEnum.DEEPOBJECT);
                        p.setExplode(true);
                        if (schema.getTypes() != null && schema.getTypes().contains("null")) {
                            Set<String> types = new LinkedHashSet<>(schema.getTypes());
                            types.remove("null");
                            schema.setTypes(types);
                        }
                        changed.add(path + "?" + p.getName() + " kept as deepObject");
                        continue;
                    }
                    if (object) {
                        p.setSchema(new StringSchema());
                        changed.add(path + "?" + p.getName() + " -> string");
                    }
                    if (p.getStyle() == Parameter.StyleEnum.DEEPOBJECT) {
                        // deepObject is for objects: anything else is sent as name=value
                        p.setStyle(Parameter.StyleEnum.FORM);
                        p.setExplode(true);
                    }
                    if (resetStyle && (p.getStyle() != Parameter.StyleEnum.FORM || !Boolean.TRUE.equals(p.getExplode()))) {
                        p.setStyle(Parameter.StyleEnum.FORM);
                        p.setExplode(true);
                        if (!object) {
                            changed.add(path + "?" + p.getName() + " style reset to form/explode");
                        }
                    }
                }
            }
        }
        return changed;
    }

    /**
     * A parameter whose schema is a {@code oneOf} or {@code anyOf} of one schema and {@code type: null} (OpenAPI 3.1)
     * takes that schema, marked {@link #NULL_BRANCH}: the argument is its type and goes out as a parameter of that
     * schema does. It stays nullable (see {@code TimvwRustCodegen#fromParameter}): a parameter has no {@code null}
     * on the wire, {@code None} leaves it out. Parameters of paths, webhooks, callbacks and
     * {@code components.parameters}; the union was rendered as a string ({@code &str}) instead. A branch that only
     * wraps a reference ({@link #peel}) gives that reference, and the description of the union or the branch
     * documents the parameter when it has none of its own.
     */
    @SuppressWarnings("rawtypes")
    static List<String> unwrapNullableParameters(OpenAPI openAPI) {
        List<String> changed = new ArrayList<>();
        List<Map.Entry<String, Parameter>> params = new ArrayList<>();
        if (openAPI.getComponents() != null && openAPI.getComponents().getParameters() != null) {
            for (Map.Entry<String, Parameter> e : openAPI.getComponents().getParameters().entrySet()) {
                params.add(Map.entry("#/components/parameters/" + e.getKey(), e.getValue()));
            }
        }
        List<Map<String, PathItem>> pathItems = new ArrayList<>(Arrays.asList(openAPI.getPaths(),
                openAPI.getWebhooks()));
        for (int i = 0; i < pathItems.size(); i++) {
            Map<String, PathItem> items = pathItems.get(i);
            if (items == null) {
                continue;
            }
            for (Map.Entry<String, PathItem> item : items.entrySet()) {
                List<List<Parameter>> containers = new ArrayList<>();
                containers.add(item.getValue().getParameters());
                for (Operation op : item.getValue().readOperationsMap().values()) {
                    containers.add(op.getParameters());
                    if (op.getCallbacks() != null) {
                        pathItems.addAll(op.getCallbacks().values());
                    }
                }
                for (List<Parameter> list : containers) {
                    if (list != null) {
                        for (Parameter p : list) {
                            params.add(Map.entry(item.getKey(), p));
                        }
                    }
                }
            }
        }
        for (Map.Entry<String, Parameter> e : params) {
            Parameter p = e.getValue();
            Schema branch = p.getSchema() == null ? null : nonNullBranch(p.getSchema());
            if (branch != null) {
                String description = p.getSchema().getDescription();
                if (description == null) {
                    description = branch.getDescription();
                }
                branch = peel(branch);
                if (p.getDescription() == null && description != null) {
                    p.setDescription(description);
                }
                p.setSchema(branch);
                p.addExtension(NULL_BRANCH, true);
                changed.add(e.getKey() + " " + p.getIn() + " " + p.getName());
            }
        }
        return changed;
    }

    /**
     * The one branch that is not {@code type: null} of a {@code oneOf} or {@code anyOf} that also has a
     * {@code type: null} branch and nothing else (no type, properties or {@code allOf} of its own), or {@code null}.
     */
    @SuppressWarnings("rawtypes")
    static Schema nonNullBranch(Schema<?> schema) {
        if (schema.get$ref() != null || schema.getAllOf() != null || schema.getProperties() != null
                || (schema.getOneOf() != null) == (schema.getAnyOf() != null)) {
            return null;
        }
        if (schema.getTypes() != null && !schema.getTypes().isEmpty()
                && !(schema.getTypes().size() == 1 && schema.getTypes().contains("null"))) {
            return null;
        }
        if (schema.getType() != null && !"null".equals(schema.getType())) {
            return null;
        }
        List<Schema> branches = schema.getOneOf() != null ? schema.getOneOf() : schema.getAnyOf();
        Schema other = null;
        boolean nullBranch = false;
        for (Schema b : branches) {
            if (b.get$ref() == null && "null".equals(singleType(b))) {
                nullBranch = true;
            } else if (other != null) {
                return null;
            } else {
                other = b;
            }
        }
        return nullBranch ? other : null;
    }

    /**
     * The reference a schema wraps: while it is a {@code oneOf}, {@code anyOf} or {@code allOf} of one element and
     * has no type or properties of its own, that element, as long as it leads to a {@code $ref}
     * ({@code {description, oneOf: [$ref]}}, {@code {allOf: [{oneOf: [$ref]}]}}). Anything else is returned as it is.
     */
    @SuppressWarnings("rawtypes")
    static Schema peel(Schema<?> schema) {
        Schema current = schema;
        while (true) {
            Schema inner = singleElement(current);
            if (inner == null) {
                return current.get$ref() != null ? current : schema;
            }
            current = inner;
        }
    }

    /** The one element of a wrapper (see {@link #peel}), or {@code null}. */
    @SuppressWarnings("rawtypes")
    private static Schema singleElement(Schema<?> schema) {
        if (schema.get$ref() != null || schema.getProperties() != null || schema.getItems() != null
                || schema.getType() != null || (schema.getTypes() != null && !schema.getTypes().isEmpty())
                || schema.getEnum() != null) {
            return null;
        }
        List<Schema> only = null;
        for (List<Schema> list : Arrays.asList(schema.getOneOf(), schema.getAnyOf(), schema.getAllOf())) {
            if (list == null || list.isEmpty()) {
                continue;
            }
            if (only != null || list.size() != 1) {
                return null;
            }
            only = list;
        }
        return only == null ? null : only.get(0);
    }

    /**
     * The {@code $ref} of an {@code allOf} of that one reference and nothing else (the normalizer's form of a
     * nullable reference), or {@code null}.
     */
    @SuppressWarnings("rawtypes")
    static Schema singleAllOfRef(Schema<?> schema) {
        if (schema.get$ref() != null || schema.getAllOf() == null || schema.getAllOf().size() != 1
                || schema.getAllOf().get(0).get$ref() == null || schema.getOneOf() != null
                || schema.getAnyOf() != null || schema.getProperties() != null) {
            return null;
        }
        String type = singleType(schema);
        if (type != null && !"object".equals(type) && !"null".equals(type)
                || schema.getTypes() != null && schema.getTypes().size() > 1
                && !(schema.getTypes().size() == 2 && schema.getTypes().contains("null"))) {
            return null;
        }
        return schema.getAllOf().get(0);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * swagger-models 2.2.3x (openapi-generator 7.25) records whether {@code default}/{@code example} was set, so a
     * cleared value would serialize as an explicit {@code null}; the 7.15 version has no such flag. Read and clear it
     * reflectively so the generator builds against both.
     */
    private static boolean flag(Schema<?> schema, String getter) {
        try {
            return (Boolean) Schema.class.getMethod(getter).invoke(schema);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static void clearFlag(Schema<?> schema, String setter) {
        try {
            Schema.class.getMethod(setter, boolean.class).invoke(schema, false);
        } catch (ReflectiveOperationException e) {
            // not present in this swagger-models version
        }
    }

    /** An object schema, possibly nullable, possibly expressed as a union of an object and null. */
    @SuppressWarnings("rawtypes")
    private static boolean isObjectLike(Schema<?> schema) {
        if ("object".equals(singleType(schema)) || (schema.getTypes() != null && schema.getTypes().contains("object"))) {
            return true;
        }
        for (List<Schema> branches : Arrays.asList(schema.getAnyOf(), schema.getOneOf())) {
            if (branches == null) {
                continue;
            }
            boolean anyObject = false;
            boolean allObjectOrNull = true;
            for (Schema b : branches) {
                String t = singleType(b);
                if ("object".equals(t)) {
                    anyObject = true;
                } else if (!"null".equals(t)) {
                    allObjectOrNull = false;
                }
            }
            if (anyObject && allObjectOrNull) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("rawtypes")
    static Map<String, Schema> schemas(OpenAPI openAPI) {
        if (openAPI.getComponents() == null || openAPI.getComponents().getSchemas() == null) {
            return new LinkedHashMap<>();
        }
        return openAPI.getComponents().getSchemas();
    }

    static String refName(String ref) {
        return ref.substring(ref.lastIndexOf('/') + 1);
    }

    /** The schema's type when it has exactly one (3.0 {@code type} or a single-element 3.1 {@code type}). */
    static String singleType(Schema<?> schema) {
        if (schema.getTypes() != null && !schema.getTypes().isEmpty()) {
            return schema.getTypes().size() == 1 ? schema.getTypes().iterator().next() : null;
        }
        return schema.getType();
    }

    private static StringSchema string(String description) {
        StringSchema s = new StringSchema();
        s.setDescription(description);
        return s;
    }

    /** Python's {@code str.capitalize()}: first letter upper, the rest lower. */
    static String capitalize(String s) {
        if (s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    /** {@code snake_case} to a name with each part capitalized (and lower-cased after the first letter). */
    static String capitalizeParts(String snake) {
        StringBuilder sb = new StringBuilder();
        for (String part : snake.split("_")) {
            sb.append(capitalize(part));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- walking

    @SuppressWarnings("rawtypes")
    static void forEachComponentSchema(OpenAPI openAPI, Consumer<Schema> visitor) {
        Set<Schema> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Schema s : schemas(openAPI).values()) {
            walk(s, seen, visitor);
        }
    }

    /** Visits every schema in the document: components, paths and webhooks (parameters, bodies, responses). */
    @SuppressWarnings("rawtypes")
    static void forEachSchema(OpenAPI openAPI, Consumer<Schema> visitor) {
        Set<Schema> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Schema s : schemas(openAPI).values()) {
            walk(s, seen, visitor);
        }
        if (openAPI.getComponents() != null) {
            if (openAPI.getComponents().getParameters() != null) {
                for (Parameter p : openAPI.getComponents().getParameters().values()) {
                    walkParameter(p, seen, visitor);
                }
            }
            if (openAPI.getComponents().getRequestBodies() != null) {
                for (RequestBody b : openAPI.getComponents().getRequestBodies().values()) {
                    walkContent(b.getContent(), seen, visitor);
                }
            }
            if (openAPI.getComponents().getResponses() != null) {
                for (ApiResponse r : openAPI.getComponents().getResponses().values()) {
                    walkContent(r.getContent(), seen, visitor);
                }
            }
        }
        for (Map<String, PathItem> items : Arrays.asList(openAPI.getPaths(), openAPI.getWebhooks())) {
            if (items == null) {
                continue;
            }
            for (PathItem item : items.values()) {
                if (item.getParameters() != null) {
                    for (Parameter p : item.getParameters()) {
                        walkParameter(p, seen, visitor);
                    }
                }
                for (Operation op : item.readOperationsMap().values()) {
                    if (op.getParameters() != null) {
                        for (Parameter p : op.getParameters()) {
                            walkParameter(p, seen, visitor);
                        }
                    }
                    if (op.getRequestBody() != null) {
                        walkContent(op.getRequestBody().getContent(), seen, visitor);
                    }
                    if (op.getResponses() != null) {
                        for (ApiResponse r : op.getResponses().values()) {
                            walkContent(r.getContent(), seen, visitor);
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private static void walkParameter(Parameter p, Set<Schema> seen, Consumer<Schema> visitor) {
        walk(p.getSchema(), seen, visitor);
        walkContent(p.getContent(), seen, visitor);
    }

    @SuppressWarnings("rawtypes")
    private static void walkContent(Content content, Set<Schema> seen, Consumer<Schema> visitor) {
        if (content == null) {
            return;
        }
        for (MediaType m : content.values()) {
            walk(m.getSchema(), seen, visitor);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void walk(Schema schema, Set<Schema> seen, Consumer<Schema> visitor) {
        if (schema == null || !seen.add(schema)) {
            return;
        }
        visitor.accept(schema);
        if (schema.getProperties() != null) {
            for (Object s : schema.getProperties().values()) {
                walk((Schema) s, seen, visitor);
            }
        }
        walk(schema.getItems(), seen, visitor);
        if (schema.getAdditionalProperties() instanceof Schema) {
            walk((Schema) schema.getAdditionalProperties(), seen, visitor);
        }
        for (List<Schema> list : Arrays.asList(schema.getAllOf(), schema.getOneOf(), schema.getAnyOf(), schema.getPrefixItems())) {
            if (list != null) {
                for (Schema s : new ArrayList<>(list)) {
                    walk(s, seen, visitor);
                }
            }
        }
        walk(schema.getNot(), seen, visitor);
        if (schema.getPatternProperties() != null) {
            for (Object s : schema.getPatternProperties().values()) {
                walk((Schema) s, seen, visitor);
            }
        }
    }
}
