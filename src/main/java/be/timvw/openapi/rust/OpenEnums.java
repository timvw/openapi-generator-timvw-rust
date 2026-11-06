package be.timvw.openapi.rust;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.utils.ModelUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Milestone 3.5 ({@code openEnums}): enums and tagged unions a response can hold get a catch-all, so that one new value
 * from the API does not fail the whole response.
 *
 * <p><b>Reachable.</b> Everything a response body can hold, in any media type (so server-sent events too), of any
 * status, and what a webhook delivers: its request body, which the API sends. The walk follows the spec's schemas, not
 * the models: a {@code jsonValueSchemas} schema is a {@code serde_json::Value} in the models, so the model graph ends
 * there, while the value still holds what the spec says, for a caller to decode. {@link #keepRefs} keeps those edges.
 * A schema a response takes in through {@code allOf} counts too, since its fields are in that response.</p>
 *
 * <p><b>Guarded.</b> Decoding that tries variants in turn (an untagged union, or the variants of a tagged union that
 * share a value) relies on an earlier variant rejecting what a later one is for. Often only an enum rejects it: a
 * {@code role} field, or a closed enum in front of a free string. A catch-all there would take the later variant's values
 * and decode them to the wrong type, so such an enum or union stays closed. {@link Guards} finds them; an unknown value
 * then goes on to the later variant, or fails where nothing takes it, as it does without the option.</p>
 */
final class OpenEnums {

    /** On a value enum, or a property holding an inline one: its catch-all, a {@link CatchAll}. */
    static final String CATCH_ALL = "x-rust-open-enum";

    /**
     * On a {@code jsonValueSchemas} schema: the schemas below it before the rule replaced it. An extension, since the
     * parser that runs after the rules copies a free-form object into what refers to it, which leaves no reference.
     */
    static final String BEHIND_VALUE = "x-rust-behind-value";

    private static final String VEC = "Vec<";
    private static final String SET = "std::collections::HashSet<";
    private static final String MAP = "std::collections::HashMap<";
    private static final String MODELS = "models::";

    /** The catch-all variant of a value enum, for the template. */
    public static final class CatchAll {
        public final String name;
        public final String wire;

        CatchAll(String name, String wire) {
            this.name = name;
            this.wire = wire;
        }
    }

    private OpenEnums() {
    }

    /** {@code Unknown}, or the first of {@code UnknownValue}, {@code UnknownValue2}... that no variant is called. */
    static String variantName(Collection<String> taken) {
        return unique("Unknown", "UnknownValue", taken);
    }

    private static String unique(String first, String second, Collection<String> taken) {
        if (!taken.contains(first)) {
            return first;
        }
        String name = second;
        for (int n = 2; taken.contains(name); n++) {
            name = second + n;
        }
        return name;
    }

    /** The catch-all of an enum: not called, and not serializing as, anything the spec lists. */
    @SuppressWarnings("unchecked")
    private static CatchAll catchAll(Map<String, Object> allowableValues) {
        List<String> names = new ArrayList<>();
        List<String> wires = new ArrayList<>();
        Object vars = allowableValues == null ? null : allowableValues.get("enumVars");
        if (vars instanceof List) {
            for (Map<String, Object> var : (List<Map<String, Object>>) vars) {
                names.add(String.valueOf(var.get("name")));
                wires.add(String.valueOf(var.get("value")));
            }
        }
        return new CatchAll(variantName(names), unique("unknown", "unknown_value", wires));
    }

    /**
     * What each {@code jsonValueSchemas} schema refers to, read before the rule replaces it: schema name to the schemas
     * below it, under the names they end up with. {@link #keepRefs} stores it.
     */
    @SuppressWarnings("rawtypes")
    static Map<String, List<String>> refsBehind(OpenAPI openAPI, Collection<String> names) {
        Map<String, Schema> schemas = SpecRules.schemas(openAPI);
        Map<String, List<String>> behind = new LinkedHashMap<>();
        for (String name : names) {
            Schema<?> schema = schemas.get(name);
            if (schema != null) {
                Walk walk = new Walk(Map.of(), false);
                walk.visit(schema);
                behind.put(name, walk.reached.stream().map(SpecRules::withoutHyphens).toList());
            }
        }
        return behind;
    }

    /** Puts what {@link #refsBehind} read on the schemas the rule left in place. */
    @SuppressWarnings("rawtypes")
    static void keepRefs(OpenAPI openAPI, Map<String, List<String>> behind) {
        Map<String, Schema> schemas = SpecRules.schemas(openAPI);
        behind.forEach((name, refs) -> schemas.get(name).addExtension(BEHIND_VALUE, refs));
    }

    /** The schemas a response body or a webhook payload can hold, by schema name. */
    static Set<String> reachable(OpenAPI openAPI, boolean behindValues) {
        Walk walk = new Walk(SpecRules.schemas(openAPI), behindValues);
        if (openAPI.getPaths() != null) {
            for (PathItem item : openAPI.getPaths().values()) {
                for (Operation operation : item.readOperationsMap().values()) {
                    responses(openAPI, operation, walk);
                }
            }
        }
        if (openAPI.getWebhooks() != null) {
            for (PathItem item : openAPI.getWebhooks().values()) {
                for (Operation operation : item.readOperationsMap().values()) {
                    responses(openAPI, operation, walk);
                    RequestBody body = ModelUtils.getReferencedRequestBody(openAPI, operation.getRequestBody());
                    if (body != null) {
                        walk.content(body.getContent());
                    }
                }
            }
        }
        return walk.reached;
    }

    private static void responses(OpenAPI openAPI, Operation operation, Walk walk) {
        if (operation.getResponses() != null) {
            for (ApiResponse response : operation.getResponses().values()) {
                ApiResponse resolved = ModelUtils.getReferencedApiResponse(openAPI, response);
                if (resolved != null) {
                    walk.content(resolved.getContent());
                }
            }
        }
    }

    /** Follows references, properties, items, map values and branches. */
    @SuppressWarnings("rawtypes")
    private static final class Walk {
        final Set<String> reached = new LinkedHashSet<>();
        private final Map<String, Schema> schemas;
        private final boolean behindValues;
        private final Set<Schema> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        Walk(Map<String, Schema> schemas, boolean behindValues) {
            this.schemas = schemas;
            this.behindValues = behindValues;
        }

        void content(Content content) {
            if (content != null) {
                for (MediaType media : content.values()) {
                    visit(media.getSchema());
                }
            }
        }

        private void reach(String name) {
            if (reached.add(name)) {
                visit(schemas.get(name));
            }
        }

        @SuppressWarnings("unchecked")
        void visit(Schema<?> schema) {
            if (schema == null || !seen.add(schema)) {
                return;
            }
            if (schema.get$ref() != null) {
                reach(SpecRules.refName(schema.get$ref()));
            }
            if (behindValues && schema.getExtensions() != null && schema.getExtensions().get(BEHIND_VALUE) instanceof List) {
                ((List<String>) schema.getExtensions().get(BEHIND_VALUE)).forEach(this::reach);
            }
            visit(schema.getItems());
            if (schema.getAdditionalProperties() instanceof Schema) {
                visit((Schema<?>) schema.getAdditionalProperties());
            }
            if (schema.getProperties() != null) {
                schema.getProperties().values().forEach(p -> visit((Schema<?>) p));
            }
            for (List<Schema> branches : Arrays.asList(schema.getOneOf(), schema.getAnyOf(), schema.getAllOf())) {
                if (branches != null) {
                    branches.forEach(b -> visit((Schema<?>) b));
                }
            }
        }
    }

    /** What {@link #mark} found, by Rust name; an inline enum is {@code Struct.field}. */
    record Census(Set<String> enums, Set<String> reachableEnums, Set<String> openEnums, Set<String> guardedEnums,
                  Set<String> taggedUnions, Set<String> reachableTagged, Set<String> openUnions,
                  Set<String> guardedUnions, Set<String> untaggedUnions, Set<String> reachableUntagged,
                  int reachableSchemas, int reachableWithoutValueSchemas) {

        Map<String, Object> describe() {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("enums", enums.size());
            plan.put("reachableEnums", reachableEnums.size());
            plan.put("openEnums", openEnums);
            plan.put("guardedEnums", guardedEnums);
            plan.put("closedEnums", minus(enums, reachableEnums));
            plan.put("taggedUnions", taggedUnions.size());
            plan.put("reachableTaggedUnions", reachableTagged.size());
            plan.put("openUnions", openUnions);
            plan.put("guardedUnions", guardedUnions);
            plan.put("closedUnions", minus(taggedUnions, reachableTagged));
            plan.put("untaggedUnions", untaggedUnions.size());
            plan.put("reachableUntaggedUnions", reachableUntagged);
            plan.put("schemasReachable", reachableSchemas);
            plan.put("schemasReachableWithoutValueSchemas", reachableWithoutValueSchemas);
            return plan;
        }

        private static Set<String> minus(Set<String> all, Set<String> some) {
            Set<String> rest = new TreeSet<>(all);
            rest.removeAll(some);
            return rest;
        }
    }

    /**
     * Gives every reachable enum and tagged union its catch-all, unless {@link Guards} keeps it closed. Runs after
     * {@link UnionRules#dispatch}, since the order of the variants and the tags decide what is guarded.
     */
    static Census mark(Map<String, ModelsMap> allModels, Map<String, CodegenModel> unions, OpenAPI openAPI) {
        Map<String, CodegenModel> byName = UnionRules.byName(allModels);
        Set<String> reachable = reachable(openAPI, true);
        Guards guards = new Guards(byName, unions);
        guards.run();

        Set<String> enums = new TreeSet<>();
        Set<String> reachableEnums = new TreeSet<>();
        Set<String> openEnums = new TreeSet<>();
        Set<String> guardedEnums = new TreeSet<>();
        Set<String> tagged = new TreeSet<>();
        Set<String> reachableTagged = new TreeSet<>();
        Set<String> openUnions = new TreeSet<>();
        Set<String> guardedUnions = new TreeSet<>();
        Set<String> untagged = new TreeSet<>();
        Set<String> reachableUntagged = new TreeSet<>();
        for (CodegenModel cm : byName.values()) {
            boolean reached = reachable.contains(cm.name);
            if (cm.isEnum) {
                sort(cm.classname, reached, !guards.closedModels.contains(cm.classname), enums, reachableEnums, openEnums,
                        guardedEnums);
                if (reached && !guards.closedModels.contains(cm.classname)) {
                    cm.vendorExtensions.put(CATCH_ALL, catchAll(cm.allowableValues));
                }
            } else if (unions.containsKey(cm.classname)) {
                UnionRules.Dispatch dispatch = (UnionRules.Dispatch) cm.vendorExtensions.get(UnionRules.DISPATCH);
                if (dispatch == null) {
                    untagged.add(cm.classname);
                    if (reached) {
                        reachableUntagged.add(cm.classname);
                    }
                    continue;
                }
                boolean open = !guards.closedModels.contains(cm.classname);
                sort(cm.classname, reached, open, tagged, reachableTagged, openUnions, guardedUnions);
                if (reached && open) {
                    List<String> names = new ArrayList<>();
                    UnionRules.variantsOf(cm).forEach(v -> names.add(v.name));
                    dispatch.catchAll = variantName(names);
                }
            } else {
                for (CodegenProperty field : cm.vars) {
                    if (field.isEnum) {
                        String key = guards.field(cm, field);
                        sort(key, reached, !guards.closedFields.contains(key), enums, reachableEnums, openEnums,
                                guardedEnums);
                    }
                }
                if (reached) {
                    for (List<CodegenProperty> list : BoxRules.propertyLists(cm)) {
                        for (CodegenProperty field : list) {
                            if (field.isEnum && !guards.closedFields.contains(guards.field(cm, field))) {
                                field.vendorExtensions.put(CATCH_ALL, catchAll(field.allowableValues));
                            }
                        }
                    }
                }
            }
        }
        return new Census(enums, reachableEnums, openEnums, guardedEnums, tagged, reachableTagged, openUnions,
                guardedUnions, untagged, reachableUntagged, reachable.size(), reachable(openAPI, false).size());
    }

    private static void sort(String name, boolean reached, boolean open, Set<String> all, Set<String> reachable,
                             Set<String> opened, Set<String> guarded) {
        all.add(name);
        if (reached) {
            reachable.add(name);
            (open ? opened : guarded).add(name);
        }
    }

    /**
     * Finds what must stay closed. For every place where decoding tries variants in order, and every variant with a later
     * one to fall through to, this asks: what in the earlier variant rejects a value of the later one only because it is
     * closed? A catch-all there would change what known input decodes to. The answer does not depend on what is opened,
     * so one pass settles it, and a pair is looked at once.
     */
    private static final class Guards {
        final Set<String> closedModels = new TreeSet<>();
        final Set<String> closedFields = new TreeSet<>();
        private final Map<String, CodegenModel> byName;
        private final Map<String, CodegenModel> unions;
        private final UnionRules.Shapes shapes;
        private final Set<String> seen = new HashSet<>();
        private final Set<String> closing = new HashSet<>();

        Guards(Map<String, CodegenModel> byName, Map<String, CodegenModel> unions) {
            this.byName = byName;
            this.unions = unions;
            this.shapes = UnionRules.shapes(byName, unions);
        }

        /** What a later variant accepts: its type, and the fixed values of an enum if it is one. */
        private record Later(String type, List<?> values) {
            boolean anything() {
                return type == null || type.equals(TimvwRustCodegen.JSON_VALUE);
            }
        }

        void run() {
            for (CodegenModel union : unions.values()) {
                List<CodegenProperty> variants = UnionRules.variantsOf(union);
                UnionRules.Dispatch dispatch = (UnionRules.Dispatch) union.vendorExtensions.get(UnionRules.DISPATCH);
                if (dispatch == null) {
                    tried(variants);
                    continue;
                }
                for (UnionRules.Arm arm : dispatch.arms) {
                    if (arm.attempts != null) {
                        List<CodegenProperty> attempts = new ArrayList<>();
                        for (UnionRules.Variant attempt : arm.attempts) {
                            variants.stream().filter(v -> v.name.equals(attempt.name)).forEach(attempts::add);
                        }
                        tried(attempts);
                    }
                }
            }
        }

        String field(CodegenModel holder, CodegenProperty field) {
            return holder.classname + "." + field.baseName;
        }

        private void tried(List<CodegenProperty> inOrder) {
            for (int i = 0; i < inOrder.size(); i++) {
                for (int j = i + 1; j < inOrder.size(); j++) {
                    against(inOrder.get(i).dataType, later(inOrder.get(j)));
                }
            }
        }

        private Later later(CodegenProperty property) {
            return new Later(property.dataType, shapes.values(property));
        }

        /** Whether a value of {@code later} that {@code earlier} rejects only for being closed can reach it. */
        private void against(String earlier, Later later) {
            if (earlier == null || !seen.add(earlier + " <- " + later)) {
                return;
            }
            for (Later alternative : alternatives(later, new HashSet<>())) {
                one(earlier, alternative);
            }
        }

        /** A later union counts through its arms. */
        private List<Later> alternatives(Later later, Set<String> visited) {
            CodegenModel model = model(later.type);
            if (later.values != null || model == null || !unions.containsKey(model.classname)) {
                return List.of(later);
            }
            List<Later> all = new ArrayList<>();
            if (visited.add(model.classname)) {
                for (CodegenProperty arm : UnionRules.variantsOf(model)) {
                    all.addAll(alternatives(later(arm), visited));
                }
            }
            return all;
        }

        private CodegenModel model(String type) {
            return type != null && type.startsWith(MODELS) ? byName.get(type.substring(MODELS.length())) : null;
        }

        private void one(String earlier, Later later) {
            if (isSequence(earlier)) {
                if (isSequence(later.type)) {
                    against(inner(earlier), new Later(inner(later.type), later.values));
                } else if (later.anything()) {
                    closeInside(earlier);
                }
                return;
            }
            if (isMap(earlier)) {
                if (isMap(later.type)) {
                    against(mapValue(earlier), new Later(mapValue(later.type), later.values));
                } else if (later.anything() || isStruct(later.type)) {
                    closeInside(earlier);
                }
                return;
            }
            CodegenModel model = model(earlier);
            if (model == null) {
                return;
            }
            if (model.isEnum) {
                if (swallows(earlier, shapes.values(model), later)) {
                    closedModels.add(model.classname);
                }
            } else if (unions.containsKey(model.classname)) {
                UnionRules.Dispatch dispatch = (UnionRules.Dispatch) model.vendorExtensions.get(UnionRules.DISPATCH);
                if (dispatch != null && tagSwallows(dispatch, later)) {
                    closedModels.add(model.classname);
                }
                for (CodegenProperty arm : UnionRules.variantsOf(model)) {
                    against(arm.dataType, later);
                }
            } else if (later.anything() || isMap(later.type)) {
                closeInside(earlier);
            } else if (isStruct(later.type)) {
                fields(model, model(later.type));
            }
        }

        /** Whether a catch-all on an enum with these values would take a value {@code later} is there for. */
        private boolean swallows(String type, List<?> values, Later later) {
            if (later.anything()) {
                return true;
            }
            if (Collections.disjoint(shapes.accepts(type).keySet(), shapes.accepts(later.type).keySet())) {
                return false;
            }
            CodegenModel enumModel = model(later.type);
            List<?> theirs = later.values != null ? later.values
                    : enumModel != null && enumModel.isEnum ? shapes.values(enumModel) : null;
            return theirs == null || !strings(values).containsAll(strings(theirs));
        }

        private static Set<String> strings(List<?> values) {
            Set<String> all = new HashSet<>();
            if (values != null) {
                values.forEach(v -> all.add(String.valueOf(v)));
            }
            return all;
        }

        /** Whether {@code Unknown(Value)} on a tagged union would take an object of {@code later}. */
        private boolean tagSwallows(UnionRules.Dispatch dispatch, Later later) {
            if (later.anything() || isMap(later.type)) {
                return true;
            }
            if (!isStruct(later.type)) {
                return false;
            }
            for (CodegenProperty field : model(later.type).vars) {
                if (field.baseName.equals(dispatch.tagName)) {
                    List<?> values = shapes.values(field);
                    return values == null || !dispatch.values.keySet().containsAll(strings(values));
                }
            }
            return false;
        }

        /**
         * Two structs: only when the earlier one could decode a value of the later one do its fields matter, and one
         * field that rejects every value of the later struct is enough, which leaves the others free to open.
         */
        private void fields(CodegenModel earlier, CodegenModel later) {
            if (earlier == later) {
                return;
            }
            Map<String, CodegenProperty> theirs = new LinkedHashMap<>();
            later.vars.forEach(f -> theirs.put(f.baseName, f));
            for (CodegenProperty f : earlier.vars) {
                CodegenProperty g = theirs.get(f.baseName);
                if (g == null ? f.required : (f.required || g.required) && Collections.disjoint(
                        shapes.accepts(f.dataType).keySet(), shapes.accepts(g.dataType).keySet())) {
                    return;
                }
            }
            CodegenProperty decider = decider(earlier, theirs);
            if (decider != null) {
                if (decider.isEnum) {
                    closedFields.add(field(earlier, decider));
                } else {
                    closedModels.add(model(decider.dataType).classname);
                }
                return;
            }
            for (CodegenProperty f : earlier.vars) {
                CodegenProperty g = theirs.get(f.baseName);
                if (g == null) {
                    continue;
                }
                if (f.isEnum) {
                    for (Later alternative : alternatives(later(g), new HashSet<>())) {
                        if (swallows(f.dataType, shapes.values(f), alternative)) {
                            closedFields.add(field(earlier, f));
                        }
                    }
                } else {
                    against(f.dataType, later(g));
                }
            }
        }

        /**
         * A field whose fixed values share none with the later struct's, and that a value of it cannot leave out. An
         * inline enum goes first: closing it touches no other type.
         */
        private CodegenProperty decider(CodegenModel earlier, Map<String, CodegenProperty> theirs) {
            CodegenProperty decider = null;
            for (CodegenProperty f : earlier.vars) {
                CodegenProperty g = theirs.get(f.baseName);
                if (g == null || f.isContainer || g.isContainer || !(present(f) || present(g))) {
                    continue;
                }
                List<?> ours = shapes.values(f);
                List<?> others = shapes.values(g);
                if (ours != null && others != null && Collections.disjoint(strings(ours), strings(others))
                        && (decider == null || (f.isEnum && !decider.isEnum))) {
                    decider = f;
                }
            }
            return decider;
        }

        private static boolean present(CodegenProperty field) {
            return field.required && !field.isNullable;
        }

        /** Everything below a type: a later variant that takes anything falls through for any of it. */
        private void closeInside(String type) {
            if (isSequence(type)) {
                closeInside(inner(type));
            } else if (isMap(type)) {
                closeInside(mapValue(type));
            } else {
                CodegenModel model = model(type);
                if (model == null || !closing.add(model.classname)) {
                    return;
                }
                if (model.isEnum) {
                    closedModels.add(model.classname);
                } else if (unions.containsKey(model.classname)) {
                    if (model.vendorExtensions.get(UnionRules.DISPATCH) != null) {
                        closedModels.add(model.classname);
                    }
                    UnionRules.variantsOf(model).forEach(arm -> closeInside(arm.dataType));
                } else {
                    for (CodegenProperty f : model.vars) {
                        if (f.isEnum) {
                            closedFields.add(field(model, f));
                        } else {
                            closeInside(f.dataType);
                        }
                    }
                }
            }
        }

        private boolean isStruct(String type) {
            CodegenModel model = model(type);
            return model != null && !model.isEnum && !unions.containsKey(model.classname);
        }

        private static boolean isSequence(String type) {
            return type != null && (type.startsWith(VEC) || type.startsWith(SET));
        }

        private static boolean isMap(String type) {
            return type != null && type.startsWith(MAP);
        }

        private static String inner(String sequence) {
            return sequence.substring(sequence.indexOf('<') + 1, sequence.length() - 1);
        }

        private static String mapValue(String map) {
            return map.substring(map.indexOf(", ") + 2, map.length() - 1);
        }
    }
}
