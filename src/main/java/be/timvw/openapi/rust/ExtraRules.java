package be.timvw.openapi.rust;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The opt-in renderings that close the gaps the generator comparison found (docs/comparison): typed enum parameters,
 * nullable union fields, schema defaults in the documentation and {@code #[deprecated]}. Each changes the public API or
 * its documentation, so each is an option that is off by default.
 */
final class ExtraRules {

    private ExtraRules() {
    }

    // ---------------------------------------------------------------- schema defaults in the documentation

    /**
     * Appends {@code Defaults to `<value>`.} to the description of every property and parameter whose schema has a
     * {@code default}. Runs before the spec rules, which remove {@code default} from component schemas.
     *
     * @return how many defaults were noted
     */
    @SuppressWarnings("rawtypes")
    static int documentDefaults(OpenAPI openAPI) {
        int[] n = {0};
        Set<Schema> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        SpecRules.schemas(openAPI).values().forEach(s -> documentProperties(s, seen, n));
        if (openAPI.getPaths() != null) {
            for (PathItem item : openAPI.getPaths().values()) {
                List<Parameter> shared = item.getParameters() == null ? List.of() : item.getParameters();
                shared.forEach(p -> documentParameter(p, seen, n));
                for (Operation op : item.readOperations()) {
                    if (op.getParameters() != null) {
                        op.getParameters().forEach(p -> documentParameter(p, seen, n));
                    }
                    if (op.getRequestBody() != null && op.getRequestBody().getContent() != null) {
                        for (MediaType media : op.getRequestBody().getContent().values()) {
                            documentProperties(media.getSchema(), seen, n);
                        }
                    }
                }
            }
        }
        return n[0];
    }

    private static void documentParameter(Parameter p, Set<Schema> seen, int[] n) {
        if (p == null || p.getSchema() == null) {
            return;
        }
        String note = defaultNote(p.getSchema());
        if (note != null) {
            p.setDescription(withNote(p.getDescription(), note));
            n[0]++;
        }
        documentProperties(p.getSchema(), seen, n);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void documentProperties(Schema s, Set<Schema> seen, int[] n) {
        if (s == null || !seen.add(s)) {
            return;
        }
        if (s.getProperties() != null) {
            for (Object value : s.getProperties().values()) {
                Schema p = (Schema) value;
                String note = p.get$ref() == null ? defaultNote(p) : null;
                if (note != null) {
                    p.setDescription(withNote(p.getDescription(), note));
                    n[0]++;
                }
                documentProperties(p, seen, n);
            }
        }
        documentProperties(s.getItems(), seen, n);
        if (s.getAdditionalProperties() instanceof Schema) {
            documentProperties((Schema) s.getAdditionalProperties(), seen, n);
        }
        for (List<Schema> branches : Arrays.asList(s.getAllOf(), s.getOneOf(), s.getAnyOf())) {
            if (branches != null) {
                branches.forEach(b -> documentProperties(b, seen, n));
            }
        }
    }

    /** {@code Defaults to `<json>`.}, or {@code null} when the schema has no default (or {@code default: null}). */
    @SuppressWarnings("rawtypes")
    static String defaultNote(Schema s) {
        Object value = s.getDefault();
        if (value == null) {
            return null;
        }
        String json;
        try {
            json = io.swagger.v3.core.util.Json.mapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            json = String.valueOf(value);
        }
        return "Defaults to `" + json.replace("`", "'") + "`.";
    }

    private static String withNote(String description, String note) {
        if (description == null || description.isBlank()) {
            return note;
        }
        String trimmed = description.stripTrailing();
        return trimmed.contains(note) ? description : trimmed + "\n\n" + note;
    }

    // ---------------------------------------------------------------- typedEnumParameters

    private static final Set<String> FORM_MEDIA_TYPES = Set.of("multipart/form-data",
            "application/x-www-form-urlencoded");

    /**
     * Moves the inline enums of operation parameters, and of the properties of form and multipart bodies, into
     * component schemas, so they render as enums in {@code models} instead of {@code &str}. A parameter is named
     * {@code <operationId>_<parameter>}, a body property {@code <Schema>_<property>} (an inline body counts as
     * {@code <operationId>_request}); the items of an array get {@code _item}. Parameters declared on the path or under
     * {@code components/parameters} are shared between operations and keep their type.
     *
     * @return one line per enum moved
     * @throws IllegalArgumentException when the name is already a component's (after Rust normalization)
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static List<String> typedEnumParameters(OpenAPI openAPI, Function<String, String> toModelName) {
        List<String> moved = new ArrayList<>();
        if (openAPI.getPaths() == null) {
            return moved;
        }
        if (openAPI.getComponents() == null) {
            openAPI.setComponents(new io.swagger.v3.oas.models.Components());
        }
        if (openAPI.getComponents().getSchemas() == null) {
            openAPI.getComponents().setSchemas(new LinkedHashMap<>());
        }
        Map<String, Schema> schemas = openAPI.getComponents().getSchemas();
        Map<String, String> rustNames = new LinkedHashMap<>();
        schemas.keySet().forEach(k -> rustNames.put(toModelName.apply(k), k));
        for (Map.Entry<String, PathItem> path : openAPI.getPaths().entrySet()) {
            for (Map.Entry<PathItem.HttpMethod, Operation> entry : path.getValue().readOperationsMap().entrySet()) {
                Operation op = entry.getValue();
                String where = entry.getKey() + " " + path.getKey();
                if (op.getOperationId() == null) {
                    continue;
                }
                if (op.getParameters() != null) {
                    for (Parameter p : op.getParameters()) {
                        if (p.get$ref() != null || p.getSchema() == null) {
                            continue;
                        }
                        String name = componentName(op.getOperationId() + "_" + p.getName());
                        Schema hoisted = hoist(p.getSchema(), name, schemas, rustNames, toModelName, where);
                        if (hoisted != null) {
                            p.setSchema(hoisted);
                            moved.add(where + " parameter " + p.getName() + " -> " + name);
                        }
                    }
                }
                if (op.getRequestBody() == null || op.getRequestBody().getContent() == null) {
                    continue;
                }
                for (Map.Entry<String, MediaType> media : op.getRequestBody().getContent().entrySet()) {
                    if (!FORM_MEDIA_TYPES.contains(media.getKey()) || media.getValue().getSchema() == null) {
                        continue;
                    }
                    Schema body = media.getValue().getSchema();
                    String owner = op.getOperationId() + "_request";
                    if (body.get$ref() != null) {
                        owner = SpecRules.refName(body.get$ref());
                        body = schemas.get(owner);
                    }
                    if (body == null || body.getProperties() == null) {
                        continue;
                    }
                    for (Map.Entry<String, Schema> prop : ((Map<String, Schema>) body.getProperties()).entrySet()) {
                        String name = componentName(owner + "_" + prop.getKey());
                        Schema hoisted = hoist(prop.getValue(), name, schemas, rustNames, toModelName, where);
                        if (hoisted != null) {
                            prop.setValue(hoisted);
                            moved.add(where + " " + media.getKey() + " property " + prop.getKey() + " -> " + name);
                        }
                    }
                }
            }
        }
        return moved;
    }

    /**
     * The replacement for {@code s} when it is an inline enum (a reference to the new component) or an array of one
     * (the array, with its items replaced); {@code null} when it is neither.
     */
    @SuppressWarnings("rawtypes")
    private static Schema hoist(Schema s, String name, Map<String, Schema> schemas, Map<String, String> rustNames,
                                Function<String, String> toModelName, String where) {
        if (isInlineEnum(s)) {
            claim(name, schemas, rustNames, toModelName, where);
            schemas.put(name, s);
            return new Schema<>().$ref("#/components/schemas/" + name);
        }
        if (s.get$ref() == null && s.getItems() != null && isInlineEnum(s.getItems())) {
            String item = name + "_item";
            claim(item, schemas, rustNames, toModelName, where);
            schemas.put(item, s.getItems());
            s.setItems(new Schema<>().$ref("#/components/schemas/" + item));
            return s;
        }
        return null;
    }

    private static void claim(String name, Map<String, ?> schemas, Map<String, String> rustNames,
                              Function<String, String> toModelName, String where) {
        String rust = toModelName.apply(name);
        String taken = rustNames.get(rust);
        if (taken != null || schemas.containsKey(name)) {
            throw new IllegalArgumentException(String.format("%s: %s: the enum of %s would be the model %s, which "
                            + "the schema %s already is; rename one of them upstream",
                    TimvwRustCodegen.GENERATOR_NAME, TimvwRustCodegen.TYPED_ENUM_PARAMETERS, where, rust,
                    taken != null ? taken : name));
        }
        rustNames.put(rust, name);
    }

    @SuppressWarnings("rawtypes")
    private static boolean isInlineEnum(Schema s) {
        return s != null && s.get$ref() == null && s.getEnum() != null && !s.getEnum().isEmpty();
    }

    /** A component name from an operation and parameter name: anything but letters, digits and {@code _} is {@code _}. */
    static String componentName(String raw) {
        return raw.replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("_+$", "");
    }

    // ---------------------------------------------------------------- nullable union fields

    /**
     * Makes nullable every field that refers to a nullable union, so it renders as {@code Option<Option<U>>} (optional)
     * or {@code Option<U>} (required) like any other nullable field.
     *
     * @return how many fields changed (counted once per field, not per property list)
     */
    @SuppressWarnings("rawtypes")
    static int nullableUnionFields(Map<String, ModelsMap> objs, Map<String, CodegenModel> unions, OpenAPI openAPI) {
        Map<String, Schema> schemas = SpecRules.schemas(openAPI);
        Set<String> nullable = new TreeSet<>();
        for (CodegenModel cm : unions.values()) {
            Schema s = schemas.get(cm.name);
            if (cm.isNullable || (s != null && (Boolean.TRUE.equals(s.getNullable())
                    || (s.getTypes() != null && s.getTypes().contains("null"))))) {
                nullable.add(cm.classname);
            }
        }
        int n = 0;
        for (CodegenModel cm : models(objs)) {
            for (List<CodegenProperty> vars : propertyLists(cm)) {
                for (CodegenProperty p : vars) {
                    if (p.isNullable || p.isContainer) {
                        continue;
                    }
                    String type = p.complexType != null ? p.complexType
                            : p.dataType == null ? null : p.dataType.replaceFirst("^models::", "");
                    if (type != null && nullable.contains(type)) {
                        p.isNullable = true;
                        if (vars == cm.vars) {
                            n++;
                        }
                    }
                }
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- deprecatedAttributes

    /**
     * Marks deprecated properties ({@link TimvwRustCodegen#DEPRECATED}) for the template.
     *
     * @return the class names of the deprecated models, which {@link #deprecateItem} marks in the written files
     */
    static Set<String> markDeprecated(Map<String, ModelsMap> objs) {
        Set<String> deprecated = new TreeSet<>();
        for (CodegenModel cm : models(objs)) {
            if (cm.isDeprecated) {
                deprecated.add(cm.classname);
            }
            for (List<CodegenProperty> vars : propertyLists(cm)) {
                for (CodegenProperty p : vars) {
                    if (p.deprecated) {
                        p.vendorExtensions.put(TimvwRustCodegen.DEPRECATED, true);
                    }
                }
            }
        }
        return deprecated;
    }

    private static final Pattern ITEM = Pattern.compile("^pub (?:struct|enum) (\\w+)\\b", Pattern.MULTILINE);

    /**
     * Puts {@code #[deprecated]} on the type of every model file whose model is marked, and starts every model and API
     * file with {@code #![allow(deprecated)]}: generated code refers to deprecated items itself (constructors,
     * builders, serde), and only a caller should be warned.
     */
    static void allowDeprecated(Path models, Path apis) throws IOException {
        for (Path dir : List.of(models, apis)) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> list = Files.list(dir)) {
                files = list.filter(f -> f.toString().endsWith(".rs")).sorted().toList();
            }
            for (Path file : files) {
                String text = Files.readString(file);
                if (!text.startsWith("#![allow(deprecated)]")) {
                    Files.writeString(file, "#![allow(deprecated)]\n" + text);
                }
            }
        }
    }

    /** Adds {@code #[deprecated]} above the attributes of {@code pub struct|enum <name>} in {@code text}. */
    static String deprecateItem(String text, String name) {
        Matcher m = ITEM.matcher(text);
        while (m.find()) {
            if (!m.group(1).equals(name)) {
                continue;
            }
            int start = m.start();
            // Walk up over the item's attributes (#[...]), stopping at doc comments or anything else.
            int insert = start;
            while (insert > 0) {
                int lineStart = text.lastIndexOf('\n', insert - 2) + 1;
                String line = text.substring(lineStart, insert).trim();
                if (line.startsWith("#[")) {
                    insert = lineStart;
                } else {
                    break;
                }
            }
            if (text.startsWith("#[deprecated]", insert)) {
                return text;
            }
            return text.substring(0, insert) + "#[deprecated]\n" + text.substring(insert);
        }
        return text;
    }

    // ---------------------------------------------------------------- helpers

    static List<CodegenModel> models(Map<String, ModelsMap> objs) {
        List<CodegenModel> out = new ArrayList<>();
        for (ModelsMap models : objs.values()) {
            for (ModelMap model : models.getModels()) {
                out.add(model.getModel());
            }
        }
        return out;
    }

    static List<List<CodegenProperty>> propertyLists(CodegenModel cm) {
        List<List<CodegenProperty>> out = new ArrayList<>();
        for (List<CodegenProperty> list : Arrays.asList(cm.vars, cm.allVars, cm.requiredVars, cm.optionalVars,
                cm.readOnlyVars, cm.readWriteVars, cm.parentVars, cm.nonNullableVars)) {
            if (list != null) {
                out.add(list);
            }
        }
        return out;
    }
}
