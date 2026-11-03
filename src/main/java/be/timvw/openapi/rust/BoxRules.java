package be.timvw.openapi.rust;

import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides which model references are rendered as {@code Box<T>} (Milestone 3.1, {@code boxOnlyCycles}).
 *
 * <p>A Rust type that holds itself by value has no finite size, so a reference has to go through a pointer somewhere
 * on every cycle of the model graph. The stock generator boxes every model reference. With {@code boxOnlyCycles} a
 * reference is boxed only when it points into the strongly connected component of the model that holds it, which
 * covers every cycle and nothing else. Only references held by value count as edges: a struct field (required or
 * {@code Option}) and an enum variant payload. {@code Vec} and maps hold their items on the heap already and are never
 * edges.</p>
 *
 * <p>The decision is stored per property as the vendor extension {@value #BOX}, which the model template reads in
 * place of the stock {@code avoidBoxedModels} flag. Without the option every property gets {@code true}, which is the
 * stock rendering.</p>
 */
final class BoxRules {

    /** Vendor extension on a {@link CodegenProperty}: render this model reference as {@code Box<T>}. */
    static final String BOX = "x-rust-box";

    private BoxRules() {
    }

    /** The property lists of a model. Each list holds its own copies of the properties, so all of them are marked. */
    static List<List<CodegenProperty>> propertyLists(CodegenModel cm) {
        List<List<CodegenProperty>> lists = new ArrayList<>(Arrays.asList(cm.vars, cm.allVars, cm.requiredVars,
                cm.optionalVars, cm.readOnlyVars, cm.readWriteVars, cm.parentVars, cm.nonNullableVars));
        if (cm.getComposedSchemas() != null) {
            lists.add(cm.getComposedSchemas().getOneOf());
            lists.add(cm.getComposedSchemas().getAnyOf());
            lists.add(cm.getComposedSchemas().getAllOf());
        }
        lists.removeIf(l -> l == null);
        return lists;
    }

    /**
     * Marks every property of every model with {@value #BOX}.
     *
     * @param boxOnlyCycles {@code false} boxes every model reference (stock); {@code true} only references inside a
     *                      cycle
     * @return the number of model references that are boxed, counted on {@code vars} and {@code oneOf} variants
     */
    static int mark(Map<String, ModelsMap> allModels, boolean boxOnlyCycles) {
        Map<String, CodegenModel> byName = new LinkedHashMap<>();
        for (ModelsMap models : allModels.values()) {
            for (ModelMap mm : models.getModels()) {
                CodegenModel cm = mm.getModel();
                byName.put(cm.classname, cm);
            }
        }

        Map<String, Integer> component = boxOnlyCycles ? components(byName) : Map.of();
        int boxed = 0;
        for (CodegenModel cm : byName.values()) {
            for (List<CodegenProperty> list : propertyLists(cm)) {
                for (CodegenProperty p : list) {
                    boolean box = !boxOnlyCycles || sameComponent(component, cm.classname, target(p, byName));
                    p.vendorExtensions.put(BOX, box);
                    if (box && p.isModel && (list == cm.vars || isOneOf(cm, list))) {
                        boxed++;
                    }
                }
            }
        }
        return boxed;
    }

    private static boolean isOneOf(CodegenModel cm, List<CodegenProperty> list) {
        return cm.getComposedSchemas() != null && list == cm.getComposedSchemas().getOneOf();
    }

    private static boolean sameComponent(Map<String, Integer> component, String from, String to) {
        if (to == null) {
            return false;
        }
        Integer a = component.get(from);
        return a != null && a.equals(component.get(to));
    }

    /** The model a property holds by value, or {@code null} for a container, a primitive or an unknown type. */
    static String target(CodegenProperty p, Map<String, CodegenModel> byName) {
        if (p.isContainer || p.isArray || p.isMap) {
            return null;
        }
        String name = p.complexType;
        if (name == null && p.dataType != null) {
            name = p.dataType.startsWith("models::") ? p.dataType.substring("models::".length()) : p.dataType;
        }
        return name != null && byName.containsKey(name) ? name : null;
    }

    /** Model name to edges: the models it holds by value, through its fields and variants. A discriminated enum without
     * {@code oneOf} renders its own {@code vars} inside each variant, so those are its fields here too. */
    static Map<String, Set<String>> edges(Map<String, CodegenModel> byName) {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (CodegenModel cm : byName.values()) {
            Set<String> out = new LinkedHashSet<>();
            addTargets(out, cm.vars, byName);
            if (cm.getComposedSchemas() != null) {
                addTargets(out, cm.getComposedSchemas().getOneOf(), byName);
                addTargets(out, cm.getComposedSchemas().getAnyOf(), byName);
            }
            edges.put(cm.classname, out);
        }
        return edges;
    }

    private static void addTargets(Set<String> out, Collection<CodegenProperty> properties,
                                   Map<String, CodegenModel> byName) {
        if (properties == null) {
            return;
        }
        for (CodegenProperty p : properties) {
            String t = target(p, byName);
            if (t != null) {
                out.add(t);
            }
        }
    }

    /**
     * Strongly connected components of the model graph (Tarjan, iterative). A model is in a component of its own
     * unless it lies on a cycle; a model that references itself directly forms a cycle on its own, so a self edge
     * keeps its component id unique and {@link #sameComponent} boxes it.
     *
     * @return model name to component id
     */
    static Map<String, Integer> components(Map<String, CodegenModel> byName) {
        Map<String, Set<String>> edges = edges(byName);
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Map<String, Integer> component = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new java.util.HashSet<>();
        int[] counter = {0};
        int[] components = {0};

        for (String root : edges.keySet()) {
            if (index.containsKey(root)) {
                continue;
            }
            // Explicit DFS: each frame is a node and the iterator over its remaining successors.
            Deque<Object[]> work = new ArrayDeque<>();
            visit(root, index, low, stack, onStack, counter);
            work.push(new Object[]{root, edges.get(root).iterator()});
            while (!work.isEmpty()) {
                Object[] frame = work.peek();
                String node = (String) frame[0];
                @SuppressWarnings("unchecked")
                java.util.Iterator<String> it = (java.util.Iterator<String>) frame[1];
                if (it.hasNext()) {
                    String next = it.next();
                    if (!index.containsKey(next)) {
                        visit(next, index, low, stack, onStack, counter);
                        work.push(new Object[]{next, edges.getOrDefault(next, Set.of()).iterator()});
                    } else if (onStack.contains(next)) {
                        low.put(node, Math.min(low.get(node), index.get(next)));
                    }
                    continue;
                }
                work.pop();
                if (!work.isEmpty()) {
                    String parent = (String) work.peek()[0];
                    low.put(parent, Math.min(low.get(parent), low.get(node)));
                }
                if (low.get(node).equals(index.get(node))) {
                    int id = components[0]++;
                    List<String> members = new ArrayList<>();
                    String member;
                    do {
                        member = stack.pop();
                        onStack.remove(member);
                        members.add(member);
                    } while (!member.equals(node));
                    boolean cycle = members.size() > 1 || edges.get(node).contains(node);
                    for (String m : members) {
                        // Acyclic singletons get a negative id of their own, so no edge ever lands in their component.
                        component.put(m, cycle ? id : -1 - id);
                    }
                }
            }
        }
        return component;
    }

    private static void visit(String node, Map<String, Integer> index, Map<String, Integer> low, Deque<String> stack,
                              Set<String> onStack, int[] counter) {
        index.put(node, counter[0]);
        low.put(node, counter[0]);
        counter[0]++;
        stack.push(node);
        onStack.add(node);
    }
}
