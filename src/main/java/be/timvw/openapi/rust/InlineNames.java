package be.timvw.openapi.rust;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.callbacks.Callback;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gives every inline schema that becomes a model a name derived from where it stands, before the inline-model
 * resolver runs ({@code qualifiedInlineNames}, Milestone 3.3).
 *
 * <p>The resolver names an inline schema after its title when it has one, and after its location otherwise. A title
 * is display text: {@code uniqueName} turns a title that is already taken into {@code Title_1}, a schema nested in
 * one with the same title overwrites its parent (which then references itself), and a title that normalizes to the
 * name of a component ends up in the same file. So the title of such a schema is replaced by the name its location
 * gives it, which the resolver then uses as is:</p>
 *
 * <table>
 *   <caption>The naming scheme</caption>
 *   <tr><th>Inline schema</th><th>Name</th></tr>
 *   <tr><td>property {@code p} of {@code P}</td><td>{@code P_p}</td></tr>
 *   <tr><td>array items of {@code P}</td><td>{@code P_inner}</td></tr>
 *   <tr><td>map values of {@code P}</td><td>{@code P_value}</td></tr>
 *   <tr><td>{@code oneOf}/{@code anyOf} branch of {@code P}</td><td>{@code P_<title>}, or {@code P_oneOf}/{@code P_anyOf}
 *       without a title; branches of one union that would share a name get their position appended</td></tr>
 *   <tr><td>{@code not} of {@code P}</td><td>{@code P_not}</td></tr>
 *   <tr><td>request body, parameter, response of an operation</td><td>as the resolver names them
 *       ({@code <op>_request}, {@code <op>_<param>_parameter}, {@code <op>_<code>_response})</td></tr>
 * </table>
 *
 * <p>The properties of an inline {@code allOf} member are named {@code P_allOf_p}, as the resolver does: the member
 * itself never becomes a model. These are the resolver's own names for an untitled schema, so an untitled schema
 * below a component keeps its name. Rust naming then camel-cases them ({@code Foo_bar} is {@code FooBar}).</p>
 *
 * <p>Different locations give the same name only in odd cases ({@code Foo.a_b} and {@code Foo.a.b}, or {@code Foo.bar}
 * and a component {@code FooBar}). Those are not resolved here: see {@link NameCollisions}.</p>
 *
 * <p>Titles of schemas that do not become models are left alone: a primitive branch's title names its enum variant.</p>
 */
final class InlineNames {

    /** One schema that will become a model: its component key, where it stands, and the schema. */
    record Site(String name, String location, Schema<?> schema) {
    }

    /**
     * What {@link #qualify} did.
     *
     * @param sites   every planned model: the component schemas, then the inline schemas
     * @param renamed how many inline schemas lost a title of their own
     * @param shared  locations holding the same schema instance as an earlier location, whose model they share
     */
    record Plan(List<Site> sites, int renamed, List<String> shared) {
    }

    private static final String COMPONENTS = "#/components/schemas/";

    private final OpenAPI openAPI;
    private final Map<String, String> options;
    private final List<Site> sites = new ArrayList<>();
    private final Set<Schema<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<String> shared = new ArrayList<>();
    private int renamed;

    private InlineNames(OpenAPI openAPI, Map<String, String> options) {
        this.openAPI = openAPI;
        this.options = options;
    }

    /**
     * Replaces the title of every inline schema that will become a model by its qualified name.
     *
     * @param openAPI             the document the resolver is about to flatten
     * @param inlineSchemaOptions the resolver options, for {@code ARRAY_ITEM_SUFFIX} and {@code MAP_ITEM_SUFFIX}
     * @return the component schemas and inline schemas that will be models
     */
    static Plan qualify(OpenAPI openAPI, Map<String, String> inlineSchemaOptions) {
        InlineNames names = new InlineNames(openAPI, inlineSchemaOptions == null ? new HashMap<>() : inlineSchemaOptions);
        names.run();
        return new Plan(names.sites, names.renamed, names.shared);
    }

    /** The resolver's {@code sanitizeName}: the name it derives a component key from. */
    static String sanitize(String name) {
        return name.replaceAll("^[0-9]", "_$0").replaceAll("[^A-Za-z0-9]", "_");
    }

    @SuppressWarnings("rawtypes")
    private void run() {
        Map<String, Schema> components = SpecRules.schemas(openAPI);
        for (Map.Entry<String, Schema> e : components.entrySet()) {
            sites.add(new Site(e.getKey(), COMPONENTS + e.getKey(), e.getValue()));
        }
        // Components first: a schema a component and an operation share is named after the component.
        for (Map.Entry<String, Schema> e : new LinkedHashMap<>(components).entrySet()) {
            String where = COMPONENTS + e.getKey();
            if (seen.add(e.getValue())) {
                children(e.getValue(), e.getKey(), where);
            }
        }
        if (openAPI.getComponents() != null && openAPI.getComponents().getResponses() != null) {
            for (Map.Entry<String, ApiResponse> e : openAPI.getComponents().getResponses().entrySet()) {
                content(e.getValue().getContent(), e.getKey() + "_response", "#/components/responses/" + e.getKey());
            }
        }
        if (openAPI.getPaths() != null) {
            pathItems(openAPI.getPaths(), "#/paths/");
        }
        if (openAPI.getWebhooks() != null) {
            pathItems(openAPI.getWebhooks(), "#/webhooks/");
        }
    }

    private void pathItems(Map<String, PathItem> items, String prefix) {
        for (Map.Entry<String, PathItem> e : items.entrySet()) {
            String where = prefix + e.getKey();
            PathItem item = e.getValue();
            parameters(item.getParameters(), e.getKey(), where);
            for (Map.Entry<PathItem.HttpMethod, Operation> op : item.readOperationsMap().entrySet()) {
                operation(op.getValue(), e.getKey() + "_" + op.getKey().name().toLowerCase(),
                        where + "/" + op.getKey().name().toLowerCase());
            }
        }
    }

    private void operation(Operation operation, String fallback, String where) {
        String name = operation.getOperationId() == null ? fallback : operation.getOperationId();
        RequestBody body = operation.getRequestBody();
        if (body != null && body.get$ref() != null && openAPI.getComponents() != null
                && openAPI.getComponents().getRequestBodies() != null) {
            body = openAPI.getComponents().getRequestBodies().get(SpecRules.refName(body.get$ref()));
        }
        if (body != null) {
            content(body.getContent(), name + "_request", where + "/requestBody");
        }
        parameters(operation.getParameters(), name, where);
        if (operation.getResponses() != null) {
            for (Map.Entry<String, ApiResponse> r : operation.getResponses().entrySet()) {
                content(r.getValue().getContent(), name + "_" + r.getKey() + "_response",
                        where + "/responses/" + r.getKey());
            }
        }
        if (operation.getCallbacks() != null) {
            for (Map.Entry<String, Callback> c : operation.getCallbacks().entrySet()) {
                for (Map.Entry<String, PathItem> p : c.getValue().entrySet()) {
                    for (Map.Entry<PathItem.HttpMethod, Operation> op : p.getValue().readOperationsMap().entrySet()) {
                        String method = op.getKey().name().toLowerCase();
                        operation(op.getValue(), p.getKey() + "_" + method,
                                where + "/callbacks/" + c.getKey() + "/" + p.getKey() + "/" + method);
                    }
                }
            }
        }
    }

    private void parameters(List<Parameter> parameters, String name, String where) {
        if (parameters == null) {
            return;
        }
        for (Parameter p : parameters) {
            if (p.get$ref() != null && openAPI.getComponents() != null
                    && openAPI.getComponents().getParameters() != null) {
                p = openAPI.getComponents().getParameters().get(SpecRules.refName(p.get$ref()));
            }
            if (p != null) {
                inline(p.getSchema(), name + "_" + p.getName() + "_parameter", where + "/parameters/" + p.getName());
            }
        }
    }

    /** A body or response in one media type keeps the resolver's name; several that need a model name each type. */
    @SuppressWarnings("rawtypes")
    private void content(Content content, String name, String where) {
        if (content == null) {
            return;
        }
        int models = 0;
        for (MediaType m : content.values()) {
            Schema schema = m.getSchema();
            if (schema != null && schema.get$ref() == null && needsModel(schema)) {
                models++;
            }
        }
        for (Map.Entry<String, MediaType> e : content.entrySet()) {
            String qualified = models > 1 ? name + "_" + e.getKey() : name;
            inline(e.getValue().getSchema(), qualified, where + "/content/" + e.getKey() + "/schema");
        }
    }

    /** An inline schema at {@code where}: names it if it becomes a model, then whatever it holds. */
    @SuppressWarnings("rawtypes")
    private void inline(Schema schema, String name, String where) {
        if (schema == null || schema.get$ref() != null) {
            return;
        }
        if (!seen.add(schema)) {
            // The resolving parse shares one instance between identical subtrees: it stays one model.
            if (needsModel(schema)) {
                shared.add(where);
            }
            return;
        }
        String qualified = sanitize(name);
        if (needsModel(schema)) {
            if (!qualified.equals(schema.getTitle())) {
                if (schema.getTitle() != null) {
                    renamed++;
                }
                schema.setTitle(qualified);
            }
            sites.add(new Site(qualified, where, schema));
        }
        children(schema, qualified, where);
    }

    /** The resolver's {@code gatherInlineModels}, naming instead of moving. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void children(Schema<?> schema, String name, String where) {
        if (schema.getType() == null || "object".equals(schema.getType())) {
            if (schema.getProperties() != null) {
                for (Map.Entry<String, Schema> p : ((Map<String, Schema>) (Map) schema.getProperties()).entrySet()) {
                    inline(p.getValue(), name + "_" + p.getKey(), where + "/properties/" + p.getKey());
                }
            }
            if (schema.getAdditionalProperties() instanceof Schema) {
                inline((Schema) schema.getAdditionalProperties(),
                        name + options.getOrDefault("MAP_ITEM_SUFFIX", "_value"), where + "/additionalProperties");
            }
        } else if (schema.getProperties() != null || schema.getAdditionalProperties() != null) {
            return; // the resolver reports these and leaves them alone
        }
        if (schema.getItems() != null) {
            inline(schema.getItems(), name + options.getOrDefault("ARRAY_ITEM_SUFFIX", "_inner"), where + "/items");
        }
        if (schema.getAllOf() != null) {
            for (int i = 0; i < schema.getAllOf().size(); i++) {
                Schema member = schema.getAllOf().get(i);
                if (member != null && member.get$ref() == null && seen.add(member)) {
                    children(member, sanitize(name + "_allOf"), where + "/allOf/" + i);
                }
            }
        }
        branches(schema.getOneOf(), name, "oneOf", where);
        branches(schema.getAnyOf(), name, "anyOf", where);
        if (schema.getNot() != null) {
            inline(schema.getNot(), name + "_not", where + "/not");
        }
    }

    @SuppressWarnings("rawtypes")
    private void branches(List<Schema> branches, String name, String keyword, String where) {
        if (branches == null) {
            return;
        }
        Map<String, Integer> uses = new HashMap<>();
        List<String> suffixes = new ArrayList<>();
        for (Schema branch : branches) {
            String suffix = null;
            if (branch != null && branch.get$ref() == null) {
                suffix = branch.getTitle() == null || sanitize(branch.getTitle()).replace("_", "").isEmpty()
                        ? keyword : sanitize(branch.getTitle());
                if (needsModelOrHoldsOne(branch)) {
                    uses.merge(suffix, 1, Integer::sum);
                }
            }
            suffixes.add(suffix);
        }
        for (int i = 0; i < branches.size(); i++) {
            String suffix = suffixes.get(i);
            if (suffix != null) {
                String qualified = uses.getOrDefault(suffix, 0) > 1 ? suffix + i : suffix;
                // The branch keeps the title of a schema that is no model: that title names its variant.
                inline(branches.get(i), name + "_" + qualified, where + "/" + keyword + "/" + i);
            }
        }
    }

    /** Arrays and maps of inline models are no models themselves, but the branch's name prefixes theirs. */
    @SuppressWarnings("rawtypes")
    private boolean needsModelOrHoldsOne(Schema schema) {
        if (schema == null || schema.get$ref() != null) {
            return false;
        }
        if (needsModel(schema)) {
            return true;
        }
        if (schema.getItems() != null && needsModelOrHoldsOne(schema.getItems())) {
            return true;
        }
        return schema.getAdditionalProperties() instanceof Schema
                && needsModelOrHoldsOne((Schema) schema.getAdditionalProperties());
    }

    /** The resolver's private {@code isModelNeeded}: decides whether an inline schema becomes a model. */
    @SuppressWarnings("rawtypes")
    private boolean needsModel(Schema schema) {
        return needsModel(schema, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    @SuppressWarnings("rawtypes")
    private boolean needsModel(Schema schema, Set<Schema> visited) {
        if (!visited.add(schema)) {
            return true;
        }
        if ("true".equalsIgnoreCase(options.get("RESOLVE_INLINE_ENUMS")) && schema.getEnum() != null
                && !schema.getEnum().isEmpty()) {
            return true;
        }
        if ((schema.getType() == null || "object".equals(schema.getType()))
                && schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            return true;
        }
        if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
            if (schema.getAllOf().size() == 1 && ((Schema) schema.getAllOf().get(0)).get$ref() != null) {
                return false;
            }
            for (Object member : schema.getAllOf()) {
                Schema target = (Schema) member;
                if (target.get$ref() != null) {
                    target = SpecRules.schemas(openAPI).get(SpecRules.refName(target.get$ref()));
                }
                if (target != null && needsModel(target, visited)) {
                    return true;
                }
            }
            return false;
        }
        return (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty())
                || (schema.getOneOf() != null && !schema.getOneOf().isEmpty());
    }
}
