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
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Decides the name of every inline schema that becomes a model, before the inline-model resolver runs
 * ({@code qualifiedInlineNames}, Milestone 3.3).
 *
 * <p>The resolver names an inline schema after its title when it has one, and after its location otherwise. Left
 * alone, a title that is already taken becomes {@code Title_1}, a schema nested in one with the same title overwrites
 * its parent (which then references itself), and a title that normalizes to the name of a component ends up in the
 * same file. So a title names a model here only when it is unique:</p>
 *
 * <ul>
 *   <li>its Rust name matches no component and no other model that will be generated, and</li>
 *   <li>every inline schema with that Rust name has the same shape; those share one model.</li>
 * </ul>
 *
 * <p>Otherwise every schema with that title takes the name of its location. The decision is made on the whole document
 * and not schema by schema, so it does not depend on the order of the spec. The title of a schema that takes its
 * location's name, and the {@code Object<i>} titles the plugin gives to untitled union branches, name nothing:</p>
 *
 * <table>
 *   <caption>The name of a location</caption>
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
 * <p>{@code P} is the name of the enclosing schema, whichever way it got it. The properties of an inline {@code allOf}
 * member are named {@code P_allOf_p}, as the resolver does: the member itself never becomes a model. Rust naming then
 * camel-cases all of these ({@code Foo_bar} is {@code FooBar}).</p>
 *
 * <p>The parser hands identical subtrees out as one schema instance. An untitled one stays one model, named after its
 * shortest location (the first by text among equals). Different locations can still give one name ({@code Foo.a_b} and {@code Foo.a.b}, or
 * {@code Foo.bar} and a component {@code FooBar}). Those are not resolved here: see {@link NameCollisions}.</p>
 */
final class InlineNames {

    /** One schema that will become a model: its component key, where it stands, and the schema. */
    record Site(String name, String location, Schema<?> schema) {
    }

    /**
     * What {@link #qualify} did.
     *
     * @param sites     every planned model: the component schemas, then the inline schemas
     * @param kept      title to the locations of the inline schemas it names, for titles that name a model
     * @param qualified title to why it names no model, for titles that do not
     * @param shared    locations holding the same schema instance as the location that names it, whose model they share
     */
    record Plan(List<Site> sites, Map<String, List<String>> kept, Map<String, String> qualified, List<String> shared) {
    }

    private static final String COMPONENTS = "#/components/schemas/";

    /** The title {@code SpecRules.titleUnnamedUnionVariants} gives an untitled object branch: not upstream's name. */
    private static final Pattern GENERATED_TITLE = Pattern.compile("^Object\\d+$");

    /** Which location names a schema that stands in several: the shortest, then the first by text, never by document order. */
    private static final Comparator<String> LOCATION_ORDER =
            Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder());

    /** Where a schema stands: below {@code parent}, or at a root name when {@code parent} is null. */
    private record Use(Node parent, String suffix, String segment, Consumer<Schema<?>> replace) {
    }

    /** A schema instance of the document: a component, an inline model, or something inline that only holds those. */
    private static final class Node {
        final Schema<?> schema;
        final boolean component;
        final boolean model;
        final List<Use> uses = new ArrayList<>();
        final List<Node> children = new ArrayList<>();
        Use primary;
        String location;

        Node(Schema<?> schema, boolean component, boolean model) {
            this.schema = schema;
            this.component = component;
            this.model = model;
        }
    }

    /** All inline schemas whose titles have one Rust name, in location order. */
    private static final class TitleGroup {
        final String title;
        final List<Node> members;

        TitleGroup(String title, List<Node> members) {
            this.title = title;
            this.members = members;
        }

        Node canonical() {
            return members.get(0);
        }
    }

    private final OpenAPI openAPI;
    private final Map<String, String> options;
    private final Function<String, String> modelName;
    private final Function<String, String> fileName;
    private final Map<Schema<?>, Node> nodes = new IdentityHashMap<>();
    private final List<Node> order = new ArrayList<>();
    private final List<Node> roots = new ArrayList<>();

    private InlineNames(OpenAPI openAPI, Map<String, String> options, Function<String, String> modelName,
                        Function<String, String> fileName) {
        this.openAPI = openAPI;
        this.options = options;
        this.modelName = modelName;
        this.fileName = fileName;
    }

    /**
     * Gives every inline schema that will become a model the title the resolver then names it after, and lets inline
     * schemas that share a unique title share one instance.
     *
     * @param openAPI             the document the resolver is about to flatten
     * @param inlineSchemaOptions the resolver options, for {@code ARRAY_ITEM_SUFFIX} and {@code MAP_ITEM_SUFFIX}
     * @param modelName           the generator's {@code toModelName}
     * @param fileName            the generator's {@code toModelFilename}
     * @return the component schemas and inline schemas that will be models
     */
    static Plan qualify(OpenAPI openAPI, Map<String, String> inlineSchemaOptions, Function<String, String> modelName,
                        Function<String, String> fileName) {
        InlineNames names = new InlineNames(openAPI, inlineSchemaOptions == null ? new HashMap<>() : inlineSchemaOptions,
                modelName, fileName);
        names.walk();
        return names.decide();
    }

    /** The resolver's {@code sanitizeName}: the name it derives a component key from. */
    static String sanitize(String name) {
        return name.replaceAll("^[0-9]", "_$0").replaceAll("[^A-Za-z0-9]", "_");
    }

    // ---------------------------------------------------------------- deciding

    private Plan decide() {
        for (Node node : order) {
            locate(node);
        }
        Map<String, String> qualified = new TreeMap<>();
        Map<String, TitleGroup> kept = candidates(qualified);
        // Dropping a title renames what lies below its schemas, which can take the name of another title. Every title
        // that collides in a round goes in that round, so the result is a function of the document, not of its order.
        boolean changed = true;
        while (changed) {
            changed = false;
            Map<Node, TitleGroup> byMember = byMember(kept);
            Map<String, List<Node>> byRustName = new HashMap<>();
            for (Node node : live(byMember)) {
                String name = name(node, byMember, new IdentityHashMap<>());
                byRustName.computeIfAbsent("model " + modelName.apply(name), k -> new ArrayList<>()).add(node);
                byRustName.computeIfAbsent("file " + fileName.apply(name), k -> new ArrayList<>()).add(node);
            }
            Map<String, String> colliding = new LinkedHashMap<>();
            for (Map.Entry<String, TitleGroup> e : kept.entrySet()) {
                TitleGroup group = e.getValue();
                for (String key : List.of("model " + modelName.apply(group.title), "file " + fileName.apply(group.title))) {
                    for (Node other : byRustName.getOrDefault(key, List.of())) {
                        if (byMember.get(other) != group) {
                            colliding.putIfAbsent(e.getKey(), "the model at " + other.location + " has that "
                                    + key.substring(0, key.indexOf(' ')) + " name");
                        }
                    }
                }
            }
            for (Map.Entry<String, String> e : colliding.entrySet()) {
                qualified.put(kept.remove(e.getKey()).title, e.getValue());
                changed = true;
            }
        }
        return apply(kept, qualified);
    }

    /** The titles that can name a model before looking at inline names: same shape, and not the name of a component. */
    private Map<String, TitleGroup> candidates(Map<String, String> qualified) {
        Map<String, List<Node>> byRustName = new TreeMap<>();
        for (Node node : order) {
            if (node.model && !node.component && upstreamTitle(node.schema)) {
                byRustName.computeIfAbsent(modelName.apply(sanitize(node.schema.getTitle())), k -> new ArrayList<>())
                        .add(node);
            }
        }
        Map<String, Node> components = new HashMap<>();
        for (Node node : order) {
            if (node.component) {
                components.put("model " + modelName.apply(node.uses.get(0).suffix()), node);
                components.put("file " + fileName.apply(node.uses.get(0).suffix()), node);
            }
        }
        NameCollisions shapes = new NameCollisions(openAPI);
        Map<String, TitleGroup> groups = new TreeMap<>();
        for (Map.Entry<String, List<Node>> e : byRustName.entrySet()) {
            List<Node> members = e.getValue();
            members.sort(Comparator.comparing(n -> n.location, LOCATION_ORDER));
            // The titles of one Rust name may be spelled differently: which one names the file must not depend on order.
            String title = members.stream().map(n -> sanitize(n.schema.getTitle())).min(Comparator.naturalOrder())
                    .orElseThrow();
            Node component = components.get("model " + modelName.apply(title));
            if (component == null) {
                component = components.get("file " + fileName.apply(title));
            }
            if (component != null) {
                qualified.put(title, "it is the name of the component at " + component.location);
                continue;
            }
            String difference = null;
            for (Node other : members.subList(1, members.size())) {
                String found = shapes.difference(members.get(0).schema, other.schema);
                if (found != null) {
                    difference = members.get(0).location + " and " + other.location + " differ: " + found;
                    break;
                }
            }
            if (difference != null) {
                qualified.put(title, difference);
            } else {
                groups.put(e.getKey(), new TitleGroup(title, members));
            }
        }
        return groups;
    }

    private static boolean upstreamTitle(Schema<?> schema) {
        String title = schema.getTitle();
        return title != null && !sanitize(title).replace("_", "").isEmpty() && !GENERATED_TITLE.matcher(title).matches();
    }

    private static Map<Node, TitleGroup> byMember(Map<String, TitleGroup> kept) {
        Map<Node, TitleGroup> byMember = new IdentityHashMap<>();
        for (TitleGroup group : kept.values()) {
            for (Node member : group.members) {
                byMember.put(member, group);
            }
        }
        return byMember;
    }

    /** The name of a schema: the kept title of its group, or else the name its first location gives it. */
    private String name(Node node, Map<Node, TitleGroup> byMember, Map<Node, String> names) {
        String known = names.get(node);
        if (known != null) {
            return known;
        }
        TitleGroup group = byMember.get(node);
        String name;
        if (node.component) {
            name = node.uses.get(0).suffix();
        } else if (group != null) {
            name = group.title;
        } else if (node.primary.parent() == null) {
            name = sanitize(node.primary.suffix());
        } else {
            name = sanitize(name(node.primary.parent(), byMember, names) + node.primary.suffix());
        }
        names.put(node, name);
        return name;
    }

    /** The models that will exist: below a title that is shared, only the first schema with it stays in the document. */
    private List<Node> live(Map<Node, TitleGroup> byMember) {
        List<Node> live = new ArrayList<>();
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Node root : roots) {
            visit(root, byMember, seen, live);
        }
        return live;
    }

    private void visit(Node node, Map<Node, TitleGroup> byMember, Set<Node> seen, List<Node> live) {
        TitleGroup group = byMember.get(node);
        Node stays = group == null ? node : group.canonical();
        if (!seen.add(stays)) {
            return;
        }
        if (stays.model) {
            live.add(stays);
        }
        for (Node child : stays.children) {
            visit(child, byMember, seen, live);
        }
    }

    /** Writes the decision into the document, for the resolver to read. */
    private Plan apply(Map<String, TitleGroup> kept, Map<String, String> qualified) {
        Map<Node, TitleGroup> byMember = byMember(kept);
        Map<Node, String> names = new IdentityHashMap<>();
        for (Node node : order) {
            names.put(node, name(node, byMember, names));
        }
        List<Node> live = live(byMember);
        List<Site> sites = new ArrayList<>();
        List<String> shared = new ArrayList<>();
        for (Node node : live) {
            sites.add(new Site(names.get(node), node.location, node.schema));
            for (Use use : node.uses) {
                String where = use.parent() == null ? use.segment() : use.parent().location + use.segment();
                if (!where.equals(node.location)) {
                    shared.add(where);
                }
            }
        }
        for (Node node : order) {
            if (node.model && !node.component) {
                node.schema.setTitle(names.get(node));
            }
        }
        Map<String, List<String>> titles = new TreeMap<>();
        for (TitleGroup group : kept.values()) {
            Node canonical = group.canonical();
            List<String> where = new ArrayList<>();
            for (Node member : group.members) {
                where.add(member.location);
                if (member != canonical) {
                    for (Use use : member.uses) {
                        use.replace().accept(canonical.schema);
                    }
                }
            }
            titles.put(group.title, where);
        }
        Collections.sort(shared);
        return new Plan(sites, titles, qualified, shared);
    }

    private void locate(Node node) {
        if (node.location != null) {
            return;
        }
        if (node.component) {
            node.primary = node.uses.get(0);
            node.location = node.primary.segment();
            return;
        }
        for (Use use : node.uses) {
            String where = use.segment();
            if (use.parent() != null) {
                locate(use.parent());
                where = use.parent().location + where;
            }
            if (node.location == null || LOCATION_ORDER.compare(where, node.location) < 0) {
                node.location = where;
                node.primary = use;
            }
        }
    }

    // ---------------------------------------------------------------- walking

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void walk() {
        for (Map.Entry<String, Schema> e : SpecRules.schemas(openAPI).entrySet()) {
            Node node = new Node(e.getValue(), true, true);
            node.uses.add(new Use(null, e.getKey(), COMPONENTS + e.getKey(), s -> { }));
            nodes.put(e.getValue(), node);
            order.add(node);
            roots.add(node);
        }
        for (Node component : new ArrayList<>(roots)) {
            children(component);
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
                Parameter parameter = p;
                inline(p.getSchema(), null, name + "_" + p.getName() + "_parameter",
                        where + "/parameters/" + p.getName(), parameter::setSchema);
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
            MediaType mediaType = e.getValue();
            inline(mediaType.getSchema(), null, models > 1 ? name + "_" + e.getKey() : name,
                    where + "/content/" + e.getKey() + "/schema", mediaType::setSchema);
        }
    }

    /**
     * An inline schema: below {@code parent} with this suffix, or at a root name when {@code parent} is null. The
     * schema is walked once, however many locations hold the instance.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void inline(Schema schema, Node parent, String suffix, String segment, Consumer<Schema> replace) {
        if (schema == null || schema.get$ref() != null) {
            return;
        }
        Use use = new Use(parent, suffix, segment, s -> replace.accept(s));
        Node node = nodes.get(schema);
        if (node != null) {
            node.uses.add(use);
            if (parent != null) {
                parent.children.add(node);
            } else {
                roots.add(node);
            }
            return;
        }
        node = new Node(schema, false, needsModel(schema));
        node.uses.add(use);
        nodes.put(schema, node);
        order.add(node);
        if (parent != null) {
            parent.children.add(node);
        } else {
            roots.add(node);
        }
        children(node);
    }

    /** The resolver's {@code gatherInlineModels}, recording instead of moving. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void children(Node node) {
        Schema<?> schema = node.schema;
        if (schema.getType() == null || "object".equals(schema.getType())) {
            if (schema.getProperties() != null) {
                Map<String, Schema> properties = (Map<String, Schema>) (Map) schema.getProperties();
                for (Map.Entry<String, Schema> p : properties.entrySet()) {
                    String key = p.getKey();
                    inline(p.getValue(), node, "_" + key, "/properties/" + key, s -> properties.put(key, s));
                }
            }
            if (schema.getAdditionalProperties() instanceof Schema) {
                inline((Schema) schema.getAdditionalProperties(), node, options.getOrDefault("MAP_ITEM_SUFFIX", "_value"),
                        "/additionalProperties", schema::setAdditionalProperties);
            }
        } else if (schema.getProperties() != null || schema.getAdditionalProperties() != null) {
            return; // the resolver reports these and leaves them alone
        }
        if (schema.getItems() != null) {
            inline(schema.getItems(), node, options.getOrDefault("ARRAY_ITEM_SUFFIX", "_inner"), "/items",
                    s -> ((Schema) schema).setItems(s));
        }
        if (schema.getAllOf() != null) {
            for (int i = 0; i < schema.getAllOf().size(); i++) {
                Schema member = schema.getAllOf().get(i);
                if (member != null && member.get$ref() == null && !nodes.containsKey(member)) {
                    // A member never becomes a model and keeps its title; it only prefixes what it holds.
                    Node holder = new Node(member, false, false);
                    holder.uses.add(new Use(node, "_allOf", "/allOf/" + i, s -> { }));
                    nodes.put(member, holder);
                    order.add(holder);
                    node.children.add(holder);
                    children(holder);
                }
            }
        }
        branches(schema.getOneOf(), node, "oneOf");
        branches(schema.getAnyOf(), node, "anyOf");
        if (schema.getNot() != null) {
            inline(schema.getNot(), node, "_not", "/not", s -> ((Schema) schema).setNot(s));
        }
    }

    @SuppressWarnings("rawtypes")
    private void branches(List<Schema> branches, Node parent, String keyword) {
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
                int index = i;
                String qualified = uses.getOrDefault(suffix, 0) > 1 ? suffix + i : suffix;
                // The branch keeps the title of a schema that is no model: that title names its variant.
                inline(branches.get(i), parent, "_" + qualified, "/" + keyword + "/" + i, s -> branches.set(index, s));
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
