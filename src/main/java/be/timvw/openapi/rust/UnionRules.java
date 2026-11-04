package be.timvw.openapi.rust;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Decides how a union renders ({@code modelUnions}, Milestone 3.3): which variants it has, what they are called and in
 * which order they are tried. The decisions are stored as {@code x-rust-enum*} vendor extensions, which the model
 * template renders; nothing looks at the rendered text.
 *
 * <p><b>What is a union.</b> A model with branches under {@code oneOf} or {@code anyOf}, with or without a discriminator.
 * {@code anyOf} counts the same: the stock templates only read {@code oneOf}. So does a model that is {@code allOf} one
 * union, which gets that union's variants. A {@code type: null} branch is not a variant, the normalizer already made the
 * schema nullable, and a nullable union is an {@code Option} where it is used. Fields of the union's own are not rendered
 * (a warning names one that its branches do not carry).</p>
 *
 * <p><b>Variant names</b> come from the branch: a name the rules pinned, then the discriminator mapping key, the
 * referenced schema, the title, and last the branch's type. An inline branch named after its union loses that prefix.</p>
 *
 * <p><b>A string enum branch</b> becomes a model of its own, {@code <Union><Variant>Enum}, so that it has a
 * {@code Display}, a {@code FromStr} and a path in {@code models::} like any other enum. The union's module exports it
 * too, as {@code models::<union>::<Union><Variant>Enum}.</p>
 *
 * <p><b>Order.</b> Every union renders {@code #[serde(untagged)]}, so decoding takes the first variant that accepts the
 * value, and a struct accepts any object that has its required fields. A variant goes before another when both accept
 * the same JSON kind (object, array, string, number, boolean) and it is the narrower one for that kind:</p>
 * <ul>
 *   <li>number: an integer before a float, which accepts integers too;</li>
 *   <li>string: a closed enum before a free string;</li>
 *   <li>object: a struct before one that would decode its values when it would not decode theirs. A struct would decode
 *       a value when the value has every field the struct requires and no required field contradicts: a fixed tag with
 *       other values, or a type of another kind. When both would, more required fields first, then more fields, which
 *       lose less; when that ties, a union first, since its other arms fall through. Maps follow the structs;</li>
 *   <li>array: by the same rules applied to the items;</li>
 *   <li>{@code serde_json::Value}, which accepts everything, comes last.</li>
 * </ul>
 * <p>A union counts through its arms. Variants this says nothing about keep their order from the spec, so a tag that
 * tells two variants apart leaves them where they are. Two variants that accept the same values remain ambiguous: only
 * tag dispatch can tell them apart.</p>
 *
 * <p><b>Tag dispatch</b> ({@code unionDispatch}) decodes a union with a tag by that tag; see {@link #dispatch}. It does
 * not touch the variants, their payloads, their order or how they serialize.</p>
 */
final class UnionRules {

    /** On a model: this model is a union, and {@value #VARIANTS} holds its variants. */
    static final String ENUM = "x-rust-enum";
    /** On a model: the variants, in decoding order. */
    static final String VARIANTS = "x-rust-enum-variants";
    /** On a variant: the Rust type it holds. */
    static final String PAYLOAD = "x-rust-enum-payload";
    /** On a variant: its {@code Display} prints the value itself, not its JSON. */
    static final String PLAIN_DISPLAY = "x-rust-enum-plain-display";
    /** On a variant holding an enum made from its branch: the model, which the union's module also exports. */
    static final String ENUM_MODEL = "x-rust-enum-model";
    /** On a variant holding a {@code Vec}: the name of its constructor. */
    static final String NEW_FN = "x-rust-enum-new-fn";
    /** On a variant: its position among the branches of the schema. */
    static final String BRANCH = "x-rust-enum-branch";
    /** On a model: the free-string variant that {@code new_text} and {@code From<String>} build. */
    static final String TEXT_VARIANT = "x-rust-enum-text";
    /** On a model: {@code oneOf} or {@code anyOf}. */
    static final String KEYWORD = "x-rust-enum-keyword";
    /** On a model that is {@code allOf} one union: the schema that has the branches. */
    static final String SCHEMA = "x-rust-enum-schema";
    /** On a form parameter of union type: a text part is the union's {@code Display}, not its JSON. */
    static final String UNION_TEXT = "x-rust-union-text";
    /** On a branch schema: the variant name the rules chose before the schema lost the title it came from. */
    static final String VARIANT_NAME = "x-rust-variant-name";
    /** On a model with {@code unionDispatch} and a tag: what its {@code Deserialize} does, for the template. */
    static final String DISPATCH = "x-rust-enum-dispatch";
    /** The module holding what every generated {@code Deserialize} of {@code unionDispatch} shares. */
    static final String DISPATCH_MODULE = "union_dispatch";

    private UnionRules() {
    }

    /**
     * Pins the title of every array branch as its variant name: {@code titleNaming=false} removes the titles of arrays
     * before naming, and the title is the better name ({@code InputItemList}, not {@code ArrayVecInputItem}).
     */
    static void pinArrayTitles(OpenAPI openAPI) {
        SpecRules.forEachSchema(openAPI, s -> {
            for (List<Schema> branches : Arrays.asList(s.getOneOf(), s.getAnyOf())) {
                if (branches != null) {
                    for (Schema<?> b : branches) {
                        if (b.get$ref() == null && b.getTitle() != null && MultipartRules.hasType(b, "array")) {
                            b.addExtension(VARIANT_NAME, b.getTitle());
                        }
                    }
                }
            }
        });
    }

    /**
     * An inline schema with the title of the schema it stands in gets a title of its own. The inline-model resolver names
     * both after the title, and the outer one overwrites the inner one, which then refers to itself: a union holding
     * itself, or a struct requiring itself. {@code qualifiedInlineNames} needs no such help.
     *
     * @return what was renamed
     */
    @SuppressWarnings("rawtypes")
    static List<String> retitleNestedTwins(OpenAPI openAPI, Function<String, String> modelName) {
        List<String> renamed = new ArrayList<>();
        SpecRules.forEachSchema(openAPI, s -> {
            if (s.getTitle() == null) {
                return;
            }
            String own = modelName.apply(s.getTitle());
            for (List<Schema> branches : Arrays.asList(s.getOneOf(), s.getAnyOf())) {
                if (branches != null) {
                    for (Schema<?> b : branches) {
                        retitle(b, own, "object", modelName, renamed);
                    }
                }
            }
            if (s.getProperties() != null) {
                Map<String, Schema> properties = s.getProperties();
                properties.forEach((name, p) -> retitle(p, own, name, modelName, renamed));
            }
        });
        return renamed;
    }

    private static void retitle(Schema<?> inner, String outerModel, String suffix, Function<String, String> modelName,
                                List<String> renamed) {
        if (inner.get$ref() == null && inner.getTitle() != null && outerModel.equals(modelName.apply(inner.getTitle()))) {
            renamed.add("'" + inner.getTitle() + "' -> '" + inner.getTitle() + " " + suffix + "'");
            inner.setTitle(inner.getTitle() + " " + suffix);
        }
    }

    /**
     * Moves every inline string enum branch of a union into a component {@code <Union><Variant>Enum} and references it.
     * Runs after the inline-model resolver, when every union is a component.
     *
     * @throws IllegalArgumentException when the name is taken by a different schema; the generator does not pick one
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static int hoistEnumBranches(OpenAPI openAPI, Function<String, String> modelName) {
        Map<String, Schema> schemas = SpecRules.schemas(openAPI);
        Map<String, Schema> added = new LinkedHashMap<>();
        for (Map.Entry<String, Schema> e : schemas.entrySet()) {
            for (List<Schema> branches : Arrays.asList(e.getValue().getOneOf(), e.getValue().getAnyOf())) {
                if (branches == null) {
                    continue;
                }
                Set<String> taken = new HashSet<>();
                for (Schema<?> b : branches) {
                    if (!isStringEnum(b)) {
                        String title = b.getTitle() != null ? b.getTitle()
                                : b.get$ref() != null ? SpecRules.refName(b.get$ref()) : null;
                        if (title != null) {
                            taken.add(modelName.apply(title));
                        }
                    }
                }
                for (int i = 0; i < branches.size(); i++) {
                    Schema<?> b = branches.get(i);
                    if (!isStringEnum(b)) {
                        continue;
                    }
                    String variant = modelName.apply(b.getTitle() != null ? b.getTitle() : "Text");
                    if (variant.equals("Text") || taken.contains(variant)) {
                        variant += "Variant";
                    }
                    String base = variant;
                    for (int n = 2; !taken.add(variant); n++) {
                        variant = base + n;
                    }
                    String name = modelName.apply(e.getKey()) + variant + "Enum";
                    Schema existing = schemas.containsKey(name) ? schemas.get(name) : added.get(name);
                    if (existing != null && !b.getEnum().equals(existing.getEnum())) {
                        throw new IllegalArgumentException(String.format("%s: %s: the string enum branch of %s would "
                                        + "be the model %s, which a different schema already is; fix this upstream",
                                TimvwRustCodegen.GENERATOR_NAME, TimvwRustCodegen.MODEL_UNIONS, e.getKey(), name));
                    }
                    if (existing == null) {
                        StringSchema values = new StringSchema();
                        values.setEnum((List) b.getEnum());
                        values.setDescription(b.getDescription());
                        added.put(name, values);
                    }
                    Schema ref = new Schema().$ref("#/components/schemas/" + name);
                    ref.addExtension(VARIANT_NAME, variant);
                    branches.set(i, ref);
                }
            }
        }
        schemas.putAll(added);
        return added.size();
    }

    private static boolean isStringEnum(Schema<?> s) {
        return s.get$ref() == null && s.getEnum() != null && !s.getEnum().isEmpty()
                && s.getEnum().stream().allMatch(String.class::isInstance)
                && (SpecRules.singleType(s) == null || "string".equals(SpecRules.singleType(s)));
    }

    /** Names the variants of a union from its schema. Called for every model, while the schema is at hand. */
    @SuppressWarnings("rawtypes")
    static void nameVariants(CodegenModel cm, String schemaName, Schema<?> schema, Function<String, String> modelName) {
        if (cm.getComposedSchemas() == null) {
            return;
        }
        name(cm.getComposedSchemas().getOneOf(), schema.getOneOf(), schemaName, schema, modelName);
        name(cm.getComposedSchemas().getAnyOf(), schema.getAnyOf(), schemaName, schema, modelName);
    }

    @SuppressWarnings("rawtypes")
    private static void name(List<CodegenProperty> variants, List<Schema> branches, String schemaName, Schema<?> schema,
                             Function<String, String> modelName) {
        if (variants == null || variants.isEmpty()) {
            return;
        }
        if (branches == null || branches.size() != variants.size()) {
            throw new IllegalStateException(String.format("%s: %s: %s has %d variants for %s branches",
                    TimvwRustCodegen.GENERATOR_NAME, TimvwRustCodegen.MODEL_UNIONS, schemaName, variants.size(),
                    branches == null ? "no" : String.valueOf(branches.size())));
        }
        Map<String, List<String>> mapped = new LinkedHashMap<>();
        if (schema.getDiscriminator() != null && schema.getDiscriminator().getMapping() != null) {
            schema.getDiscriminator().getMapping().forEach(
                    (key, ref) -> mapped.computeIfAbsent(ref, r -> new ArrayList<>()).add(key));
        }
        for (int i = 0; i < variants.size(); i++) {
            CodegenProperty variant = variants.get(i);
            Schema<?> branch = branches.get(i);
            variant.vendorExtensions.put(BRANCH, i);
            variant.name = modelName.apply(variantName(variant, branch, schemaName, mapped));
        }
    }

    private static String variantName(CodegenProperty variant, Schema<?> branch, String schemaName,
                                      Map<String, List<String>> mapped) {
        Object pinned = branch.getExtensions() == null ? null : branch.getExtensions().get(VARIANT_NAME);
        if (pinned != null) {
            return pinned.toString();
        }
        String ref = branch.get$ref();
        if (ref != null) {
            List<String> keys = mapped.get(ref);
            if (keys != null && !keys.isEmpty()) {
                return keys.remove(0);
            }
            String target = SpecRules.refName(ref);
            String prefix = schemaName + "_";
            return target.length() > prefix.length() && target.startsWith(prefix)
                    && Character.isLetter(target.charAt(prefix.length())) ? target.substring(prefix.length()) : target;
        }
        if (branch.getTitle() != null) {
            return branch.getTitle();
        }
        if (variant.dataType != null && variant.dataType.startsWith("Vec<")) {
            return "ArrayOf" + itemsName(variant.dataType);
        }
        String type = SpecRules.singleType(branch);
        return type != null ? type : "Value";
    }

    /**
     * Finds the unions and settles their variants and order. {@link #payloads} finishes the job once {@link BoxRules}
     * has decided the boxes.
     *
     * @return the unions by model name
     */
    static Map<String, CodegenModel> plan(Map<String, ModelsMap> allModels, Consumer<String> warn) {
        Map<String, CodegenModel> byName = byName(allModels);
        Map<String, CodegenModel> unions = new LinkedHashMap<>();
        Map<String, List<CodegenProperty>> branches = new LinkedHashMap<>();
        for (CodegenModel cm : byName.values()) {
            List<CodegenProperty> variants = branchesOf(cm);
            if (variants != null && !cm.isEnum) {
                cm.vendorExtensions.put(ENUM, true);
                cm.vendorExtensions.put(KEYWORD, cm.getComposedSchemas().getOneOf() == variants ? "oneOf" : "anyOf");
                branches.put(cm.classname, distinctNames(cm, variants, warn));
                unions.put(cm.classname, cm);
            }
        }
        addAliases(byName, unions, branches);
        for (CodegenModel cm : byName.values()) {
            if (cm.discriminator != null && !unions.containsKey(cm.classname) && !cm.isEnum) {
                warn.accept(cm.classname + ": a discriminator without oneOf, anyOf or a union in allOf renders an "
                        + "empty enum");
            }
        }
        Shapes shapes = new Shapes(byName, branches);
        for (Map.Entry<String, CodegenModel> e : unions.entrySet()) {
            CodegenModel cm = e.getValue();
            warnAboutOwnFields(cm, branches.get(e.getKey()), byName, warn);
            clearFields(cm);
            List<CodegenProperty> ordered = shapes.order(branches.get(e.getKey()));
            cm.vendorExtensions.put(VARIANTS, ordered);
            if (ordered.size() >= 2) {
                for (CodegenProperty v : ordered) {
                    if ("String".equals(v.dataType)) {
                        cm.vendorExtensions.put(TEXT_VARIANT, v.name);
                        break;
                    }
                }
            }
        }
        return unions;
    }

    /** The variants of a union, in decoding order. */
    @SuppressWarnings("unchecked")
    static List<CodegenProperty> variants(CodegenModel union) {
        return (List<CodegenProperty>) union.vendorExtensions.get(VARIANTS);
    }

    /** Sets what depends on the boxes: the payload of every variant, and how it prints. */
    static void payloads(Map<String, CodegenModel> unions, Map<String, ModelsMap> allModels) {
        Map<String, CodegenModel> byName = byName(allModels);
        for (CodegenModel cm : unions.values()) {
            @SuppressWarnings("unchecked")
            List<CodegenProperty> variants = (List<CodegenProperty>) cm.vendorExtensions.get(VARIANTS);
            Set<String> functions = new HashSet<>(List.of("new_text"));
            for (CodegenProperty v : variants) {
                String target = BoxRules.target(v, byName);
                CodegenModel model = target == null ? null : byName.get(target);
                boolean box = model != null && !model.isEnum && Boolean.TRUE.equals(v.vendorExtensions.get(BoxRules.BOX));
                v.vendorExtensions.put(PAYLOAD, box ? "Box<" + v.dataType + ">" : v.dataType);
                boolean plain = "String".equals(v.dataType) || (model != null && (model.isEnum || unions.containsKey(target)));
                if (plain) {
                    v.vendorExtensions.put(PLAIN_DISPLAY, true);
                }
                if (model != null && model.isEnum && target.equals(cm.classname + v.name + "Enum")) {
                    v.vendorExtensions.put(ENUM_MODEL, target);
                }
                String function = "new_" + v.name.toLowerCase(Locale.ROOT);
                if (v.dataType.startsWith("Vec<") && functions.add(function)) {
                    v.vendorExtensions.put(NEW_FN, function);
                }
            }
        }
    }

    private static Map<String, CodegenModel> byName(Map<String, ModelsMap> allModels) {
        Map<String, CodegenModel> byName = new LinkedHashMap<>();
        for (ModelsMap models : allModels.values()) {
            for (ModelMap mm : models.getModels()) {
                byName.put(mm.getModel().classname, mm.getModel());
            }
        }
        return byName;
    }

    /** Whether {@link #plan} made this model a union. */
    static boolean isUnion(CodegenModel cm) {
        return cm.vendorExtensions.containsKey(ENUM);
    }

    /**
     * A model that is {@code allOf} one union is that union under another name, and gets the same variants. It could not
     * hold the other by value and stay a union of its own branches: stock renders it empty.
     */
    private static void addAliases(Map<String, CodegenModel> byName, Map<String, CodegenModel> unions,
                                   Map<String, List<CodegenProperty>> branches) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (CodegenModel cm : byName.values()) {
                List<CodegenProperty> allOf = cm.getComposedSchemas() == null ? null : cm.getComposedSchemas().getAllOf();
                if (unions.containsKey(cm.classname) || cm.isEnum || allOf == null || allOf.size() != 1) {
                    continue;
                }
                String target = BoxRules.target(allOf.get(0), byName);
                if (target == null || !unions.containsKey(target)) {
                    continue;
                }
                List<CodegenProperty> variants = new ArrayList<>();
                branches.get(target).forEach(v -> variants.add(v.clone()));
                cm.getComposedSchemas().setOneOf(variants);
                cm.vendorExtensions.put(ENUM, true);
                cm.vendorExtensions.put(KEYWORD, unions.get(target).vendorExtensions.get(KEYWORD));
                Object schema = unions.get(target).vendorExtensions.get(SCHEMA);
                cm.vendorExtensions.put(SCHEMA, schema != null ? schema : unions.get(target).name);
                branches.put(cm.classname, variants);
                unions.put(cm.classname, cm);
                changed = true;
            }
        }
    }

    private static List<CodegenProperty> branchesOf(CodegenModel cm) {
        if (cm.getComposedSchemas() == null) {
            return null;
        }
        List<CodegenProperty> oneOf = cm.getComposedSchemas().getOneOf();
        if (oneOf != null && !oneOf.isEmpty()) {
            return oneOf;
        }
        List<CodegenProperty> anyOf = cm.getComposedSchemas().getAnyOf();
        return anyOf != null && !anyOf.isEmpty() ? anyOf : null;
    }

    /**
     * Drops a variant that repeats an earlier one. Arrays that share a name (the spec titles them all {@code array}) are
     * named after their items instead, and any other variants that share one get a number.
     */
    private static List<CodegenProperty> distinctNames(CodegenModel cm, List<CodegenProperty> variants,
                                                       Consumer<String> warn) {
        List<CodegenProperty> kept = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> seen = new HashSet<>();
        Map<String, Long> uses = new LinkedHashMap<>();
        for (CodegenProperty v : variants) {
            if (v.dataType.startsWith("Vec<")) {
                uses.merge(v.name, 1L, Long::sum);
            }
        }
        for (CodegenProperty v : variants) {
            if (!seen.add(v.name + "(" + v.dataType + ")")) {
                warn.accept(cm.classname + ": dropped duplicate variant " + v.name + "(" + v.dataType + ")");
                continue;
            }
            if (v.dataType.startsWith("Vec<") && uses.getOrDefault(v.name, 0L) > 1) {
                v.name = "ArrayOf" + itemsName(v.dataType);
            }
            String name = v.name;
            for (int i = 2; !names.add(name); i++) {
                name = v.name + i;
            }
            v.name = name;
            kept.add(v);
        }
        return kept;
    }

    /** {@code Vec<String>} is {@code Strings}, {@code Vec<Vec<i32>>} is {@code IntegerArrays}, {@code Vec<models::X>} is {@code Xs}. */
    private static String itemsName(String vec) {
        String item = vec.substring("Vec<".length(), vec.length() - 1);
        if (item.startsWith("Vec<")) {
            return itemsName(item).replaceAll("s$", "") + "Arrays";
        }
        if (item.equals("String")) {
            return "Strings";
        }
        if (item.matches("[iu](8|16|32|64|128|size)")) {
            return "Integers";
        }
        if (item.equals("f32") || item.equals("f64")) {
            return "Numbers";
        }
        if (item.equals("bool")) {
            return "Booleans";
        }
        return item.replaceAll("^.*::", "").replaceAll("[^A-Za-z0-9]", "").replaceAll("s$", "") + "s";
    }

    private static void warnAboutOwnFields(CodegenModel cm, List<CodegenProperty> variants,
                                           Map<String, CodegenModel> byName, Consumer<String> warn) {
        if (cm.vars == null) {
            return;
        }
        for (CodegenProperty field : cm.vars) {
            for (CodegenProperty v : variants) {
                String target = BoxRules.target(v, byName);
                CodegenModel model = target == null ? null : byName.get(target);
                if (model == null || model.vars.stream().noneMatch(f -> f.baseName.equals(field.baseName))) {
                    warn.accept(cm.classname + "." + field.baseName + ": a field of the union itself, which " + v.name
                            + " does not carry, is not rendered");
                    break;
                }
            }
        }
    }

    private static void clearFields(CodegenModel cm) {
        for (List<CodegenProperty> list : Arrays.asList(cm.vars, cm.allVars, cm.requiredVars, cm.optionalVars,
                cm.readOnlyVars, cm.readWriteVars, cm.parentVars, cm.nonNullableVars)) {
            if (list != null) {
                list.clear();
            }
        }
        cm.vendorExtensions.remove("x-rust-has-byte-array");
        cm.vendorExtensions.remove("x-rust-has-integer-property-enum");
    }

    /** The JSON kinds a value can have, as far as decoding tells them apart. */
    enum Kind { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN }

    /**
     * What a type accepts of one kind. {@code rank} separates the classes of a kind, greater is narrower ({@link #ANY}
     * below {@link #MAP} below {@link #FREE} below {@link #CLOSED}). For objects {@code structs} are the structs that
     * could decode it, through the arms of a union if {@code union} is set; for arrays {@code items} is what the items
     * accept.
     */
    record Accepts(int rank, List<CodegenModel> structs, boolean union, Map<Kind, Accepts> items) {
        static final int ANY = -2;
        static final int MAP = -1;
        static final int FREE = 0;
        static final int CLOSED = 1;

        static Accepts of(int rank) {
            return new Accepts(rank, List.of(), false, Map.of());
        }

        /** Two arms of a union together: the weaker rank, since that arm decides what the union would swallow. */
        static Accepts merge(Accepts a, Accepts b) {
            List<CodegenModel> structs = new ArrayList<>(a.structs);
            b.structs.stream().filter(s -> !structs.contains(s)).forEach(structs::add);
            Map<Kind, Accepts> items = new HashMap<>(a.items);
            b.items.forEach((kind, item) -> items.merge(kind, item, Accepts::merge));
            return new Accepts(Math.min(a.rank, b.rank), structs, true, items);
        }
    }

    /** Computes what each type accepts and orders variants by it. */
    private static final class Shapes {
        private final Map<String, CodegenModel> byName;
        private final Map<String, List<CodegenProperty>> unions;
        private final Map<String, Map<Kind, Accepts>> done = new LinkedHashMap<>();
        private final Set<String> active = new HashSet<>();

        Shapes(Map<String, CodegenModel> byName, Map<String, List<CodegenProperty>> unions) {
            this.byName = byName;
            this.unions = unions;
        }

        /** Stable topological order: the first remaining variant that no remaining variant must precede. */
        List<CodegenProperty> order(List<CodegenProperty> variants) {
            int n = variants.size();
            List<Map<Kind, Accepts>> accepts = new ArrayList<>();
            for (CodegenProperty v : variants) {
                accepts.add(accepts(v.dataType));
            }
            boolean[][] before = new boolean[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    before[i][j] = i != j && compare(accepts.get(i), accepts.get(j)) > 0;
                }
            }
            List<CodegenProperty> ordered = new ArrayList<>();
            boolean[] placed = new boolean[n];
            for (int round = 0; round < n; round++) {
                int pick = -1;
                for (int i = 0; i < n && pick < 0; i++) {
                    boolean free = !placed[i];
                    for (int j = 0; j < n && free; j++) {
                        free = placed[j] || !before[j][i];
                    }
                    if (free) {
                        pick = i;
                    }
                }
                // A cycle: three variants, each narrower than the next on a different arm. The spec order breaks it.
                for (int i = 0; i < n && pick < 0; i++) {
                    if (!placed[i]) {
                        pick = i;
                    }
                }
                placed[pick] = true;
                ordered.add(variants.get(pick));
            }
            return ordered;
        }

        /**
         * Which of two types goes first: above 0 when {@code a} is narrower on the kinds both accept. The object kind
         * decides when the kinds disagree, since that is where decoding loses data.
         */
        int compare(Map<Kind, Accepts> a, Map<Kind, Accepts> b) {
            Integer object = null;
            int sign = 0;
            for (Kind kind : Kind.values()) {
                Accepts x = a.get(kind);
                Accepts y = b.get(kind);
                if (x == null || y == null) {
                    continue;
                }
                int c = compare(x, y);
                if (kind == Kind.OBJECT) {
                    object = c;
                }
                if (c != 0 && sign == 0) {
                    sign = Integer.signum(c);
                } else if (c != 0 && sign != Integer.signum(c)) {
                    return object != null ? object : sign;
                }
            }
            return sign;
        }

        private int compare(Accepts a, Accepts b) {
            int c = Integer.compare(a.rank, b.rank);
            if (c != 0) {
                return c;
            }
            c = compareStructs(a, b);
            return c != 0 ? c : compare(a.items, b.items);
        }

        /**
         * Two sets of structs: a struct goes first when the other would decode its values and not the other way round.
         * When both would, the one with more required fields, then more fields. A union goes first when nothing else
         * tells, since its other arms fall through.
         */
        private int compareStructs(Accepts x, Accepts y) {
            int strict = 0;
            int ties = 0;
            boolean overlap = false;
            for (CodegenModel a : x.structs) {
                for (CodegenModel b : y.structs) {
                    if (a == b) {
                        continue;
                    }
                    boolean bTakesA = decodes(b, a);
                    boolean aTakesB = decodes(a, b);
                    overlap |= bTakesA || aTakesB;
                    if (bTakesA != aTakesB) {
                        strict += bTakesA ? 1 : -1;
                    } else if (bTakesA) {
                        int c = Integer.compare(required(a), required(b));
                        ties += Integer.signum(c != 0 ? c : Integer.compare(a.vars.size(), b.vars.size()));
                    }
                }
            }
            if (strict != 0) {
                return strict;
            }
            if (ties != 0) {
                return ties;
            }
            return overlap ? Boolean.compare(x.union, y.union) : 0;
        }

        private static int required(CodegenModel cm) {
            return (int) cm.vars.stream().filter(f -> f.required).count();
        }

        /**
         * Whether struct {@code decoder} could decode a value of struct {@code value}: the value has every field the
         * decoder requires, and no field that one of them requires holds something the other does not accept.
         */
        private boolean decodes(CodegenModel decoder, CodegenModel value) {
            Map<String, CodegenProperty> fields = new LinkedHashMap<>();
            value.vars.forEach(f -> fields.put(f.baseName, f));
            for (CodegenProperty f : decoder.vars) {
                CodegenProperty other = fields.get(f.baseName);
                if (other == null) {
                    if (f.required) {
                        return false;
                    }
                } else if ((f.required || other.required) && contradict(f, other)) {
                    return false;
                }
            }
            return true;
        }

        private boolean contradict(CodegenProperty a, CodegenProperty b) {
            List<?> x = values(a);
            List<?> y = values(b);
            if (x != null && y != null && Collections.disjoint(x, y)) {
                return true;
            }
            Set<Kind> kinds = EnumSet.noneOf(Kind.class);
            kinds.addAll(accepts(a.dataType).keySet());
            Set<Kind> others = accepts(b.dataType).keySet();
            return !kinds.isEmpty() && !others.isEmpty() && Collections.disjoint(kinds, others);
        }

        /** The values of a field holding a closed enum, inline or by reference; otherwise {@code null}. */
        private List<?> values(CodegenProperty field) {
            if (field.isEnum && field.allowableValues != null && field.allowableValues.get("values") instanceof List) {
                return (List<?>) field.allowableValues.get("values");
            }
            CodegenModel model = BoxRules.target(field, byName) == null ? null : byName.get(BoxRules.target(field, byName));
            if (model != null && model.isEnum && model.allowableValues != null
                    && model.allowableValues.get("values") instanceof List) {
                return (List<?>) model.allowableValues.get("values");
            }
            return null;
        }

        /** What a Rust type accepts, per kind. */
        Map<Kind, Accepts> accepts(String type) {
            Map<Kind, Accepts> result = new EnumMap<>(Kind.class);
            if (type == null || type.equals("serde_json::Value")) {
                for (Kind kind : Kind.values()) {
                    result.put(kind, Accepts.of(Accepts.ANY));
                }
                return result;
            }
            if (type.startsWith("Vec<") || type.startsWith("std::collections::HashSet<")) {
                String item = type.substring(type.indexOf('<') + 1, type.length() - 1);
                result.put(Kind.ARRAY, new Accepts(item.equals("serde_json::Value") ? Accepts.MAP : Accepts.FREE,
                        List.of(), false, accepts(item)));
                return result;
            }
            if (type.startsWith("std::collections::HashMap<")) {
                result.put(Kind.OBJECT, Accepts.of(Accepts.MAP));
                return result;
            }
            if (type.startsWith("models::")) {
                return model(type.substring("models::".length()));
            }
            switch (type) {
                case "bool":
                    result.put(Kind.BOOLEAN, Accepts.of(Accepts.FREE));
                    break;
                case "f32":
                case "f64":
                    result.put(Kind.NUMBER, Accepts.of(Accepts.FREE));
                    break;
                default:
                    if (type.matches("[iu](8|16|32|64|128|size)")) {
                        result.put(Kind.NUMBER, Accepts.of(Accepts.CLOSED));
                    } else {
                        // String, PathBuf, Uuid and the like: whatever else is a string on the wire.
                        result.put(Kind.STRING, Accepts.of(Accepts.FREE));
                    }
            }
            return result;
        }

        private Map<Kind, Accepts> model(String name) {
            Map<Kind, Accepts> cached = done.get(name);
            if (cached != null) {
                return cached;
            }
            CodegenModel cm = byName.get(name);
            if (cm == null || !active.add(name)) {
                return Collections.emptyMap();
            }
            Map<Kind, Accepts> result = new EnumMap<>(Kind.class);
            if (cm.isEnum) {
                result.put(cm.isInteger || cm.isLong || cm.isNumber ? Kind.NUMBER : Kind.STRING,
                        Accepts.of(Accepts.CLOSED));
            } else if (unions.containsKey(name)) {
                for (CodegenProperty arm : unions.get(name)) {
                    accepts(arm.dataType).forEach((kind, a) -> result.merge(kind,
                            new Accepts(a.rank, a.structs, true, a.items), Accepts::merge));
                }
            } else {
                result.put(Kind.OBJECT, new Accepts(Accepts.FREE, List.of(cm), false, Map.of()));
            }
            active.remove(name);
            done.put(name, result);
            return result;
        }
    }

    /**
     * What a tagged union's {@code Deserialize} does, read by the template and written to the plan. An arm is one thing
     * the union can find where it looks for its tag, and what to decode then: one variant, or several to try in order.
     */
    public static final class Dispatch {
        public final String tagName;
        public final String tagLiteral;
        public final String source;
        public final List<Arm> arms;
        public final boolean allowsMissing;
        public final boolean acceptsNonObject;
        final Map<String, List<String>> values;
        final List<String> missing;
        final List<String> nonObject;

        Dispatch(String tag, String source, Map<String, List<String>> values, List<String> missing,
                 List<String> nonObject) {
            this.tagName = tag;
            this.tagLiteral = rustString(tag);
            this.source = source;
            this.values = values;
            this.missing = missing;
            this.nonObject = nonObject;
            this.allowsMissing = !missing.isEmpty();
            this.acceptsNonObject = !nonObject.isEmpty();
            // Values that select the same variants share an arm.
            Map<List<String>, List<String>> grouped = new LinkedHashMap<>();
            values.forEach((value, variants) -> grouped.computeIfAbsent(variants, v -> new ArrayList<>()).add(value));
            List<Arm> all = new ArrayList<>();
            grouped.forEach((variants, patterns) -> {
                List<String> literals = new ArrayList<>();
                patterns.forEach(p -> literals.add(rustString(p)));
                all.add(new Arm("Tag::Value(" + String.join(" | ", literals) + ")", variants));
            });
            if (allowsMissing) {
                all.add(new Arm("Tag::Missing", missing));
            }
            if (acceptsNonObject) {
                all.add(new Arm("Tag::NotObject", nonObject));
            }
            this.arms = all;
        }

        Map<String, Object> describe() {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("tag", tagName);
            plan.put("source", source);
            plan.put("values", values);
            plan.put("missing", missing);
            plan.put("nonObject", nonObject);
            return plan;
        }

        boolean sharesAValue() {
            return values.values().stream().anyMatch(variants -> variants.size() > 1);
        }
    }

    /** One arm of the {@code match} on the tag; exactly one of {@code single} and {@code attempts} is set. */
    public static final class Arm {
        public final String pattern;
        public final Variant single;
        public final List<Variant> attempts;

        Arm(String pattern, List<String> variants) {
            this.pattern = pattern;
            this.single = variants.size() == 1 ? new Variant(variants.get(0)) : null;
            this.attempts = variants.size() == 1 ? null : variants.stream().map(Variant::new).toList();
        }
    }

    /** A variant an arm decodes. */
    public static final class Variant {
        public final String name;

        Variant(String name) {
            this.name = name;
        }
    }

    static String rustString(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** The counts of {@link #dispatch}. */
    record DispatchCounts(int explicit, int implicit, int untagged, int sharedValue, int optional) {
    }

    /**
     * Tag dispatch ({@code unionDispatch}): finds the tag of every union in its schema and sets what its
     * {@code Deserialize} does. A union is tagged when
     * <ul>
     *   <li>it has a {@code discriminator}, on {@code oneOf} or {@code anyOf}; or</li>
     *   <li>every branch requires one property with fixed string values, and that property tells two variants apart.
     *       A branch that is a union has it when all its arms do. When several properties qualify, the one with the
     *       fewest values on two variants wins, then {@code type}, then the first by name.</li>
     * </ul>
     * <p>Decoding a tagged union reads the JSON value and decodes the variants its tag selects, each of which reads the
     * tag again from its own field. Nothing strips a field, so the bytes a value serializes to do not change. Variants
     * sharing a value are tried in the order of {@link #plan}, for that value only; so are the ones that allow a missing
     * tag when it is missing or null, and the ones that accept something other than an object when the value is one.</p>
     *
     * @throws IllegalArgumentException when a tagged union has a variant without that field in its struct, or a
     *     discriminator's variant has no value to be selected by. That is a fault of the spec or of this rule, and a
     *     guess would decode the wrong variant
     */
    @SuppressWarnings("rawtypes")
    static DispatchCounts dispatch(Map<String, CodegenModel> unions, Map<String, ModelsMap> allModels, OpenAPI openAPI) {
        Map<String, CodegenModel> byName = byName(allModels);
        Map<String, List<CodegenProperty>> variants = new LinkedHashMap<>();
        unions.forEach((name, cm) -> variants.put(name, variantsOf(cm)));
        Shapes shapes = new Shapes(byName, variants);
        Tags tags = new Tags(SpecRules.schemas(openAPI));
        int explicit = 0;
        int implicit = 0;
        int untagged = 0;
        int shared = 0;
        int optional = 0;
        for (CodegenModel cm : unions.values()) {
            Object aliased = cm.vendorExtensions.get(SCHEMA);
            Schema<?> schema = tags.schemas.get(aliased != null ? aliased : cm.name);
            List<Schema> branches = schema == null ? null : branchesOf(cm, schema);
            String tag = schema == null ? null : tags.tagOf(schema, branches);
            if (tag == null) {
                untagged++;
                continue;
            }
            checkStructs(cm, tag, unions, byName, shapes);
            Dispatch table = table(cm, schema, tag, branches, tags, shapes);
            cm.vendorExtensions.put(DISPATCH, table);
            if ("discriminator".equals(table.source)) {
                explicit++;
            } else {
                implicit++;
            }
            shared += table.sharesAValue() ? 1 : 0;
            optional += table.allowsMissing ? 1 : 0;
        }
        return new DispatchCounts(explicit, implicit, untagged, shared, optional);
    }

    /** The branch of every variant, in the order of the variants. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Schema> branchesOf(CodegenModel cm, Schema<?> schema) {
        List<Schema> branches = "oneOf".equals(cm.vendorExtensions.get(KEYWORD)) ? schema.getOneOf() : schema.getAnyOf();
        List<Schema> own = new ArrayList<>();
        for (CodegenProperty v : variantsOf(cm)) {
            Object index = v.vendorExtensions.get(BRANCH);
            own.add(branches != null && index != null ? branches.get((Integer) index) : null);
        }
        return own;
    }

    private static boolean discriminated(Schema<?> schema) {
        return schema.getDiscriminator() != null && schema.getDiscriminator().getPropertyName() != null;
    }

    @SuppressWarnings("rawtypes")
    private static Dispatch table(CodegenModel cm, Schema<?> schema, String tag, List<Schema> own, Tags tags, Shapes shapes) {
        List<CodegenProperty> variants = variantsOf(cm);
        boolean discriminated = discriminated(schema);
        Map<String, String> mapping = discriminated && schema.getDiscriminator().getMapping() != null
                ? schema.getDiscriminator().getMapping() : Map.of();
        Map<String, List<String>> values = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> nonObject = new ArrayList<>();
        for (int i = 0; i < variants.size(); i++) {
            CodegenProperty v = variants.get(i);
            Set<Kind> kinds = shapes.accepts(v.dataType).keySet();
            if (kinds.stream().anyMatch(kind -> kind != Kind.OBJECT)) {
                nonObject.add(v.name);
            }
            if (!kinds.contains(Kind.OBJECT)) {
                continue;
            }
            Tagged tagged = own.get(i) == null ? null : tags.tagged(own.get(i), tag, identitySet());
            Set<String> selected = new LinkedHashSet<>(tagged == null ? List.of() : tagged.values);
            if (own.get(i) != null && own.get(i).get$ref() != null) {
                String target = SpecRules.refName(own.get(i).get$ref());
                mapping.forEach((key, ref) -> {
                    if (SpecRules.refName(ref).equals(target)) {
                        selected.add(key);
                    }
                });
            }
            if (selected.isEmpty()) {
                throw new IllegalArgumentException(String.format("%s: %s: %s is tagged on '%s', but no value selects "
                                + "its variant %s (%s): the spec's branch needs fixed values or a discriminator mapping; "
                                + "fix this upstream", TimvwRustCodegen.GENERATOR_NAME, TimvwRustCodegen.UNION_DISPATCH,
                        cm.classname, tag, v.name, v.dataType));
            }
            for (String value : selected) {
                values.computeIfAbsent(value, x -> new ArrayList<>()).add(v.name);
            }
            if (tagged != null && tagged.optional) {
                missing.add(v.name);
            }
        }
        return new Dispatch(tag, discriminated ? "discriminator" : "implicit", values, missing, nonObject);
    }

    /** The check that the tag the schema gave is one the structs have, whatever the rules did on the way to them. */
    private static void checkStructs(CodegenModel cm, String tag, Map<String, CodegenModel> unions,
                                     Map<String, CodegenModel> byName, Shapes shapes) {
        for (CodegenProperty v : variantsOf(cm)) {
            if (shapes.accepts(v.dataType).containsKey(Kind.OBJECT)) {
                checkStruct(cm, v.name, v, tag, unions, byName, shapes, new HashSet<>());
            }
        }
    }

    private static void checkStruct(CodegenModel union, String variant, CodegenProperty payload, String tag,
                                    Map<String, CodegenModel> unions, Map<String, CodegenModel> byName, Shapes shapes,
                                    Set<String> seen) {
        // The type as rendered: the complexType of an inline branch is its schema's name, not its model's.
        String target = payload.dataType.replaceFirst("^models::", "");
        CodegenModel model = byName.get(target);
        if (model != null && unions.containsKey(target)) {
            if (seen.add(target)) {
                for (CodegenProperty arm : variantsOf(model)) {
                    if (shapes.accepts(arm.dataType).containsKey(Kind.OBJECT)) {
                        checkStruct(union, variant, arm, tag, unions, byName, shapes, seen);
                    }
                }
            }
            return;
        }
        if (model == null || model.vars.stream().noneMatch(f -> tag.equals(f.baseName))) {
            throw new IllegalArgumentException(String.format("%s: %s: %s is tagged on '%s', but its variant %s holds %s, "
                            + "which has no such field: decoding by the tag would drop it. Fix this upstream",
                    TimvwRustCodegen.GENERATOR_NAME, TimvwRustCodegen.UNION_DISPATCH, union.classname, tag, variant,
                    payload.dataType));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<CodegenProperty> variantsOf(CodegenModel cm) {
        return (List<CodegenProperty>) cm.vendorExtensions.get(VARIANTS);
    }

    /** A schema's hash and equals walk everything below it, and these only ever need to tell schemas apart. */
    private static <T> Set<T> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /** What a branch has of a tag: the values that select it, and whether it allows the tag to be missing or null. */
    private record Tagged(Set<String> values, boolean optional) {
    }

    /** Reads tags off the schemas. */
    @SuppressWarnings("rawtypes")
    private static final class Tags {
        final Map<String, Schema> schemas;

        Tags(Map<String, Schema> schemas) {
            this.schemas = schemas;
        }

        private Schema<?> resolve(Schema<?> schema) {
            Set<String> seen = new HashSet<>();
            Schema<?> s = schema;
            while (s != null && s.get$ref() != null) {
                String name = SpecRules.refName(s.get$ref());
                s = seen.add(name) ? schemas.get(name) : null;
            }
            return s;
        }

        /** The arms of a branch that is a union, by itself or as {@code allOf} one union; otherwise {@code null}. */
        @SuppressWarnings("unchecked")
        private List<Schema> arms(Schema<?> schema) {
            for (List<Schema> arms : Arrays.asList(schema.getOneOf(), schema.getAnyOf())) {
                if (arms != null && !arms.isEmpty()) {
                    return arms.stream().filter(a -> !"null".equals(SpecRules.singleType(a))).toList();
                }
            }
            if (schema.getAllOf() != null && schema.getAllOf().size() == 1) {
                Schema<?> only = resolve((Schema<?>) schema.getAllOf().get(0));
                return only == null ? null : arms(only);
            }
            return null;
        }

        /** A branch's own properties and required names, with what it inherits through {@code allOf}. */
        @SuppressWarnings("unchecked")
        private void fields(Schema<?> schema, Map<String, Schema> properties, Set<String> required, Set<Schema> seen) {
            Schema<?> s = resolve(schema);
            if (s == null || !seen.add(s)) {
                return;
            }
            if (s.getAllOf() != null) {
                for (Schema part : (List<Schema>) (List<?>) s.getAllOf()) {
                    fields(part, properties, required, seen);
                }
            }
            if (s.getProperties() != null) {
                properties.putAll(s.getProperties());
            }
            if (s.getRequired() != null) {
                required.addAll(s.getRequired());
            }
        }

        /** The string values a tag field is fixed to, through a nullable wrapper; {@code null} when it is not. */
        private Set<String> fixedValues(Schema<?> field) {
            Schema<?> s = resolve(field);
            if (s == null) {
                return null;
            }
            List<?> values = s.getConst() != null ? List.of(s.getConst()) : s.getEnum();
            if (values != null && !values.isEmpty()) {
                Set<String> strings = new LinkedHashSet<>();
                for (Object value : values) {
                    if (value instanceof String text) {
                        strings.add(text);
                    } else if (value != null) {
                        return null;
                    }
                }
                return strings.isEmpty() ? null : strings;
            }
            List<Schema> arms = arms(s);
            return arms != null && arms.size() == 1 ? fixedValues(arms.get(0)) : null;
        }

        /**
         * What a branch has of a tag, through the arms if it is a union; an arm that holds no object has no say.
         * {@code null} when an object in it has no field with that name.
         */
        Tagged tagged(Schema<?> branch, String tag, Set<Schema<?>> stack) {
            Schema<?> s = resolve(branch);
            if (s == null || !stack.add(s)) {
                return null;
            }
            try {
                List<Schema> arms = arms(s);
                if (arms != null) {
                    Set<String> values = new LinkedHashSet<>();
                    boolean optional = false;
                    for (Schema arm : arms) {
                        if (!holdsObject(arm)) {
                            continue;
                        }
                        Tagged tagged = tagged(arm, tag, stack);
                        if (tagged == null || tagged.values.isEmpty()) {
                            // Not one arm can go unselected: no values for all of them, so the caller decides.
                            return tagged;
                        }
                        values.addAll(tagged.values);
                        optional |= tagged.optional;
                    }
                    return new Tagged(values, optional);
                }
                Map<String, Schema> properties = new LinkedHashMap<>();
                Set<String> required = new HashSet<>();
                fields(s, properties, required, identitySet());
                Schema field = properties.get(tag);
                if (field == null) {
                    return null;
                }
                Set<String> values = fixedValues(field);
                return new Tagged(values == null ? Set.of() : values, !required.contains(tag) || nullable(field));
            } finally {
                stack.remove(s);
            }
        }

        private boolean nullable(Schema<?> field) {
            Schema<?> s = resolve(field);
            return s != null && (Boolean.TRUE.equals(s.getNullable()) || (s.getTypes() != null && s.getTypes().contains("null"))
                    || (s.getEnum() != null && s.getEnum().contains(null)));
        }

        private boolean holdsObject(Schema<?> branch) {
            Schema<?> s = resolve(branch);
            if (s == null) {
                return false;
            }
            if (arms(s) != null) {
                return true;
            }
            if (s.getTypes() != null && !s.getTypes().isEmpty()) {
                return s.getTypes().contains("object");
            }
            return s.getType() == null ? s.getEnum() == null && s.getConst() == null : s.getType().equals("object");
        }

        /** The property names a branch has, those all its arms have if it is a union. */
        private Set<String> names(Schema<?> branch, Set<Schema<?>> stack) {
            Schema<?> s = resolve(branch);
            if (s == null || !stack.add(s)) {
                return Set.of();
            }
            try {
                List<Schema> arms = arms(s);
                if (arms != null) {
                    Set<String> common = null;
                    for (Schema arm : arms) {
                        Set<String> names = new TreeSet<>(names(arm, stack));
                        if (common != null) {
                            names.retainAll(common);
                        }
                        common = names;
                    }
                    return common == null ? Set.of() : common;
                }
                Map<String, Schema> properties = new LinkedHashMap<>();
                fields(s, properties, new HashSet<>(), identitySet());
                return properties.keySet();
            } finally {
                stack.remove(s);
            }
        }

        /** A union's discriminator, or the property that makes its branches an implicitly tagged union; or {@code null}. */
        String tagOf(Schema<?> schema, List<Schema> branches) {
            if (discriminated(schema)) {
                return schema.getDiscriminator().getPropertyName();
            }
            if (branches.size() < 2 || branches.stream().anyMatch(Objects::isNull)) {
                return null;
            }
            Set<String> common = null;
            for (Schema branch : branches) {
                Set<String> names = new TreeSet<>(names(branch, identitySet()));
                if (common != null) {
                    names.retainAll(common);
                }
                common = names;
            }
            String best = null;
            long fewest = Long.MAX_VALUE;
            for (String name : common) {
                Map<String, Integer> uses = new HashMap<>();
                boolean all = true;
                for (Schema branch : branches) {
                    Tagged tagged = holdsObject(branch) ? tagged(branch, name, identitySet()) : null;
                    if (tagged == null || tagged.values.isEmpty() || tagged.optional) {
                        all = false;
                        break;
                    }
                    tagged.values.forEach(value -> uses.merge(value, 1, Integer::sum));
                }
                if (!all || uses.values().stream().allMatch(n -> n == branches.size())) {
                    continue;
                }
                long shared = uses.values().stream().filter(n -> n > 1).count();
                if (shared < fewest || (shared == fewest && name.equals("type"))) {
                    best = name;
                    fewest = shared;
                }
            }
            return best;
        }
    }

    /**
     * Writes {@code src/models/union_dispatch.rs} and declares it in {@code mod.rs}.
     *
     * @throws IllegalStateException when a schema already takes the module's name
     */
    static void writeDispatchModule(java.nio.file.Path models) throws java.io.IOException {
        java.nio.file.Path mod = models.resolve("mod.rs");
        String declarations = java.nio.file.Files.readString(mod);
        if (java.util.regex.Pattern.compile("\\b" + DISPATCH_MODULE + "\\b").matcher(declarations).find()) {
            throw new IllegalStateException(TimvwRustCodegen.GENERATOR_NAME + ": " + TimvwRustCodegen.UNION_DISPATCH
                    + " needs the module " + DISPATCH_MODULE + ", which a schema already has");
        }
        java.nio.file.Files.writeString(models.resolve(DISPATCH_MODULE + ".rs"), dispatchModule());
        java.nio.file.Files.writeString(mod, declarations + "pub(crate) mod " + DISPATCH_MODULE + ";\n");
    }

    /** {@code src/models/union_dispatch.rs}: what the generated {@code Deserialize} of every tagged union shares. */
    static String dispatchModule() {
        return """
                //! Decoding of tagged unions: read the JSON value, look at the tag, decode only what it selects. A union
                //! calls these from the `dispatch` function next to its `Deserialize`.
                //!
                //! Decoding goes through `serde_json::Value`, like `serde(untagged)`, whose buffer holds numbers the same
                //! way: an integer up to 64 bits stays that integer, anything larger and every float is an `f64`.
                //! A key that appears twice in an object is the last one, where a struct alone would reject it.

                use serde::de::DeserializeOwned;
                use serde_json::Value;

                /// What a union finds where it looks for its tag.
                pub(crate) enum Tag<'a> {
                    /// The tag's value.
                    Value(&'a str),
                    /// An object without the tag, or with `null` for it.
                    Missing,
                    /// Not an object at all.
                    NotObject,
                }

                /// Looks at the tag. A tag of another kind than a string is an error here, whatever the variants say.
                pub(crate) fn tag<'a>(value: &'a Value, union: &str, tag: &str) -> Result<Tag<'a>, String> {
                    let Value::Object(fields) = value else {
                        return Ok(Tag::NotObject);
                    };
                    match fields.get(tag) {
                        None | Some(Value::Null) => Ok(Tag::Missing),
                        Some(Value::String(found)) => Ok(Tag::Value(found)),
                        Some(other) => Err(format!(
                            "{union}: tag `{tag}` must be a string, got {}",
                            kind(other)
                        )),
                    }
                }

                /// Decodes the one variant selected, and keeps its error.
                pub(crate) fn decode<T: DeserializeOwned>(
                    value: Value,
                    union: &str,
                    variant: &str,
                ) -> Result<T, String> {
                    serde_json::from_value(value).map_err(|e| format!("{union}::{variant}: {e}"))
                }

                /// A variant to try: its name, and how to decode it.
                pub(crate) type Attempt<U> = (&'static str, fn(Value) -> Result<U, serde_json::Error>);

                /// Tries the variants selected in order, and keeps the error of each when none decodes.
                pub(crate) fn first_of<U>(
                    mut value: Value,
                    union: &str,
                    tag: &str,
                    attempts: &[Attempt<U>],
                ) -> Result<U, String> {
                    let found = match value.get(tag) {
                        Some(Value::String(found)) => format!("`{tag}` {found:?}"),
                        _ if value.is_object() => format!("no `{tag}`"),
                        _ => kind(&value).to_owned(),
                    };
                    let mut errors = Vec::with_capacity(attempts.len());
                    for (index, (variant, decode)) in attempts.iter().enumerate() {
                        let input = if index + 1 == attempts.len() {
                            std::mem::take(&mut value)
                        } else {
                            value.clone()
                        };
                        match decode(input) {
                            Ok(decoded) => return Ok(decoded),
                            Err(e) => errors.push(format!("{variant}: {e}")),
                        }
                    }
                    Err(format!("{union}: no variant decodes {found}: {}", errors.join("; ")))
                }

                /// The error of a tag no variant has.
                pub(crate) fn unknown_tag(union: &str, tag: &str, value: &str) -> String {
                    format!("{union}: unknown `{tag}` value {value:?}")
                }

                /// The error of no tag, when every variant requires it.
                pub(crate) fn missing_tag(union: &str, tag: &str) -> String {
                    format!("{union}: tag `{tag}` is missing or null")
                }

                /// The error of something other than an object, when every variant is one.
                pub(crate) fn not_an_object(value: &Value, union: &str, tag: &str) -> String {
                    format!("{union}: expected an object with tag `{tag}`, got {}", kind(value))
                }

                fn kind(value: &Value) -> &'static str {
                    match value {
                        Value::Null => "null",
                        Value::Bool(_) => "a boolean",
                        Value::Number(_) => "a number",
                        Value::String(_) => "a string",
                        Value::Array(_) => "an array",
                        Value::Object(_) => "an object",
                    }
                }
                """;
    }

    /** The plan as data ({@code unionPlanFile}), for tools that hold a generated crate against the spec. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static Map<String, Object> describe(Map<String, CodegenModel> unions, OpenAPI openAPI, boolean dispatched) {
        Map<String, Schema> schemas = SpecRules.schemas(openAPI);
        List<Map<String, Object>> described = new ArrayList<>();
        for (CodegenModel cm : unions.values()) {
            Schema<?> own = schemas.get(cm.name);
            Object aliased = cm.vendorExtensions.get(SCHEMA);
            Schema<?> schema = aliased != null ? schemas.get(aliased) : own;
            List<Schema> branches = schema == null ? null
                    : "oneOf".equals(cm.vendorExtensions.get(KEYWORD)) ? schema.getOneOf() : schema.getAnyOf();
            List<Map<String, Object>> variants = new ArrayList<>();
            for (CodegenProperty v : (List<CodegenProperty>) cm.vendorExtensions.get(VARIANTS)) {
                Map<String, Object> variant = new LinkedHashMap<>();
                variant.put("name", v.name);
                variant.put("payload", v.vendorExtensions.get(PAYLOAD));
                Object index = v.vendorExtensions.get(BRANCH);
                variant.put("branch", branches != null && index != null ? branches.get((Integer) index) : null);
                variants.add(variant);
            }
            Map<String, Object> union = new LinkedHashMap<>();
            union.put("union", cm.classname);
            union.put("schema", cm.name);
            union.put("keyword", cm.vendorExtensions.get(KEYWORD));
            union.put("tag", schema == null || schema.getDiscriminator() == null ? null
                    : schema.getDiscriminator().getPropertyName());
            union.put("nullable", own != null && (Boolean.TRUE.equals(own.getNullable())
                    || (own.getTypes() != null && own.getTypes().contains("null"))));
            union.put("variants", variants);
            if (dispatched) {
                Object table = cm.vendorExtensions.get(DISPATCH);
                union.put("dispatch", table == null ? null : ((Dispatch) table).describe());
            }
            described.add(union);
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("unions", described);
        plan.put("schemas", schemas);
        return plan;
    }
}
