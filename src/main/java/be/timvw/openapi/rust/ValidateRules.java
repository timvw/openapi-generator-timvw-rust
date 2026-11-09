package be.timvw.openapi.rust;

import org.openapitools.codegen.CodegenDiscriminator;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The generated {@code Validate} trait (decisions 9 and 8): every model implements
 * {@code crate::models::validation::Validate} (in {@code src/models/validation.rs}), whose {@code validate()} checks the validation keywords of the model's schema
 * and of every model, list, map and union variant it holds, and returns the first value that breaks one.
 *
 * <p>Checked: {@code minLength} and {@code maxLength} of a {@code String} (in characters), {@code pattern} (with the
 * {@code regex} crate), {@code minimum}, {@code maximum}, {@code exclusiveMinimum}, {@code exclusiveMaximum} and
 * {@code multipleOf} of a number, {@code minItems}, {@code maxItems} and {@code uniqueItems} (by equality) of a
 * {@code Vec}, {@code minProperties} and {@code maxProperties} of a map. An optional or nullable field is checked
 * when it holds a value. Enums, {@code serde_json::Value}, byte arrays, files, dates and other typed scalars have
 * nothing to check. A pattern {@link RustRegex} does not accept (lookaround, backreferences, ...) is skipped with a
 * warning.</p>
 *
 * <p>The body of every {@code validate()} is computed here and stored on the model as {@value #BODY}; the patterns it
 * uses as {@value #PATTERNS}. A model whose {@code validate()} can never fail (an enum, a struct without constraints
 * and without such models) still implements the trait, but the models holding it do not call it.</p>
 *
 * <p>The constraints are written into every field's documentation, whether or not the trait is generated.</p>
 */
final class ValidateRules {

    /** Vendor extension on a {@link CodegenModel}: the statements of its {@code validate()}, before {@code Ok(())}. */
    static final String BODY = "x-rust-validate";
    /** Vendor extension on a {@link CodegenModel}: the regexes its {@code validate()} uses ({@code name}, {@code literal}). */
    static final String PATTERNS = "x-rust-validate-patterns";
    /** Vendor extension on a {@link CodegenProperty}: its constraints, for its documentation. */
    static final String NOTE = "x-rust-validation-note";

    private static final String V = "crate::models::validation::";

    private static final Set<String> FLOATS = Set.of("f32", "f64");
    private static final Map<String, BigInteger[]> INTEGERS = new LinkedHashMap<>();

    static {
        for (int bits : new int[]{8, 16, 32, 64}) {
            BigInteger two = BigInteger.TWO;
            INTEGERS.put("i" + bits, new BigInteger[]{two.pow(bits - 1).negate(), two.pow(bits - 1).subtract(BigInteger.ONE)});
            INTEGERS.put("u" + bits, new BigInteger[]{BigInteger.ZERO, two.pow(bits).subtract(BigInteger.ONE)});
        }
    }


    private ValidateRules() {
    }

    /** The module of the trait, {@code src/models/validation.rs}. */
    static final String MODULE = "validation";

    /** Fails when a model would be written to the module of the trait. */
    static void checkModuleName(Map<String, ModelsMap> allModels) {
        for (ModelsMap mm : allModels.values()) {
            for (ModelMap m : mm.getModels()) {
                CodegenModel cm = m.getModel();
                if (MODULE.equals(cm.classFilename)) {
                    throw new IllegalArgumentException(TimvwRustCodegen.GENERATOR_NAME + ": the schema "
                            + cm.name + " would be the module models::" + MODULE + ", which the generated Validate "
                            + "trait needs; set " + TimvwRustCodegen.VALIDATE_METHODS + "=false or rename the schema");
                }
            }
        }
    }

    /** What {@link #apply} did. */
    record Result(int models, int checking, int patterns, List<String> skippedPatterns) {
    }

    /**
     * Documents the constraints of every field and, when {@code generate}, stores the body of every model's
     * {@code validate()}.
     */
    static Result apply(Map<String, ModelsMap> allModels, boolean generate, Consumer<String> warn) {
        List<CodegenModel> models = new ArrayList<>();
        for (ModelsMap mm : allModels.values()) {
            for (ModelMap m : mm.getModels()) {
                models.add(m.getModel());
            }
        }
        for (CodegenModel cm : models) {
            for (CodegenProperty cp : cm.vars) {
                String note = note(cp);
                if (note != null) {
                    cp.vendorExtensions.put(NOTE, note);
                }
            }
        }
        if (!generate) {
            return new Result(models.size(), 0, 0, List.of());
        }
        List<String> skipped = new ArrayList<>();
        for (CodegenModel cm : models) {
            for (CodegenProperty cp : cm.vars) {
                skippedPatterns(cp, cm.classname + "." + cp.baseName, skipped);
            }
            if (Boolean.TRUE.equals(cm.vendorExtensions.get(UnionRules.ENUM))) {
                for (CodegenProperty v : UnionRules.variants(cm)) {
                    skippedPatterns(v, cm.classname + "::" + v.name, skipped);
                }
            }
        }
        skipped.forEach(warn);
        // which models have something to check, directly or through a model they hold: a fixed point, since models
        // can hold each other
        Set<String> checking = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (CodegenModel cm : models) {
                if (!checking.contains(cm.classname) && !new Writer(checking, null).body(cm).isEmpty()) {
                    checking.add(cm.classname);
                    changed = true;
                }
            }
        }
        int patterns = 0;
        for (CodegenModel cm : models) {
            Writer writer = new Writer(checking, cm.classname);
            String body = writer.body(cm);
            cm.vendorExtensions.put(BODY, body);
            List<Map<String, String>> statics = new ArrayList<>();
            writer.patterns.forEach((pattern, name) -> statics.add(Map.of("name", name, "literal", rawString(pattern))));
            cm.vendorExtensions.put(PATTERNS, statics);
            patterns += statics.size();
        }
        return new Result(models.size(), checking.size(), patterns, skipped);
    }

    /** Reports every {@code pattern} of {@code cp} and its items that {@link RustRegex} does not accept. */
    private static void skippedPatterns(CodegenProperty cp, String where, List<String> out) {
        for (int depth = 0; cp != null && depth < 8; depth++, cp = cp.isEnum ? null : cp.items) {
            String why = cp.getPattern() == null ? null : RustRegex.unsupported(cp.getPattern());
            if (why != null) {
                out.add(where + ": pattern " + cp.getPattern() + " is not checked: it holds " + why);
            }
        }
    }

    // ---------------------------------------------------------------- the documentation

    /** {@code Validation: `minLength: 1`; each item: `maxLength: 5`.}, or {@code null} without constraints. */
    static String note(CodegenProperty cp) {
        String constraints = constraints(cp, "");
        return constraints == null ? null : "Validation: " + constraints + ".";
    }

    /** The constraints of {@code cp}, then those of its items ({@code each item: }, {@code each item of an item: }). */
    private static String constraints(CodegenProperty cp, String of) {
        if (cp == null || of.length() > 100) {
            return null;
        }
        List<String> own = new ArrayList<>();
        add(own, "minLength", cp.getMinLength());
        add(own, "maxLength", cp.getMaxLength());
        if (cp.getPattern() != null) {
            own.add("`pattern: " + cp.getPattern().replace('`', '\'') + "`");
        }
        add(own, cp.getExclusiveMinimum() ? "exclusiveMinimum" : "minimum", cp.getMinimum());
        add(own, cp.getExclusiveMaximum() ? "exclusiveMaximum" : "maximum", cp.getMaximum());
        add(own, "multipleOf", cp.getMultipleOf());
        if (cp.isMap) {
            add(own, "minProperties", cp.getMinProperties());
            add(own, "maxProperties", cp.getMaxProperties());
        } else {
            add(own, "minItems", cp.getMinItems());
            add(own, "maxItems", cp.getMaxItems());
            if (Boolean.TRUE.equals(cp.getUniqueItems())) {
                own.add("`uniqueItems: true`");
            }
        }
        String text = own.isEmpty() ? null : String.join(", ", own);
        if ((cp.isArray || cp.isMap) && !cp.isEnum) {
            String inner = (cp.isMap ? "each value" : "each item") + (of.isEmpty() ? "" : " of " + of);
            String items = constraints(cp.items, inner.replace("each ", "an "));
            if (items != null) {
                String own2 = items.startsWith("each ") ? items : inner + ": " + items;
                text = text == null ? own2 : text + "; " + own2;
            }
        }
        return text;
    }

    private static void add(List<String> out, String keyword, Object value) {
        if (value != null) {
            String text = value instanceof Number n ? number(n.toString()) : value.toString();
            out.add("`" + keyword + ": " + text + "`");
        }
    }

    private static String number(String text) {
        try {
            return new BigDecimal(text).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return text;
        }
    }

    // ---------------------------------------------------------------- the Rust

    /** A Rust type, as far as validation is concerned. */
    private record Type(String name, List<Type> args) {

        static Type parse(String text) {
            int[] at = {0};
            return parse(text.replace(" ", ""), at);
        }

        private static Type parse(String s, int[] at) {
            int start = at[0];
            while (at[0] < s.length() && "<>,".indexOf(s.charAt(at[0])) < 0) {
                at[0]++;
            }
            String name = s.substring(start, at[0]);
            List<Type> args = new ArrayList<>();
            if (at[0] < s.length() && s.charAt(at[0]) == '<') {
                at[0]++;
                args.add(parse(s, at));
                while (at[0] < s.length() && s.charAt(at[0]) == ',') {
                    at[0]++;
                    args.add(parse(s, at));
                }
                at[0]++; // '>'
            }
            return new Type(name, args);
        }

        boolean option() {
            return name.equals("Option") && args.size() == 1;
        }

        boolean box() {
            return name.equals("Box") && args.size() == 1;
        }

        boolean list() {
            return (name.equals("Vec") || set()) && args.size() == 1;
        }

        boolean set() {
            return name.endsWith("HashSet") || name.endsWith("BTreeSet");
        }

        boolean map() {
            return name.endsWith("HashMap") && args.size() == 2;
        }

        String model() {
            return name.startsWith("models::") && args.isEmpty() ? name.substring("models::".length()) : null;
        }

        static Type of(String name, Type... args) {
            return new Type(name, List.of(args));
        }
    }

    /** How the generated code reaches a value: by reference, as a method receiver, and by value. */
    private record Expr(String ref, String recv, String val) {

        static Expr field(String name) {
            return new Expr("&self." + name, "self." + name, "self." + name);
        }

        static Expr binding(String name) {
            return new Expr(name, name, "*" + name);
        }
    }

    /** Writes the body of one model's {@code validate()}; collects the regexes it uses. */
    private static final class Writer {

        private final Set<String> checking;
        /** The model written, or {@code null} for a dry run that only asks whether there is something to check. */
        private final String model;
        final Map<String, String> patterns = new LinkedHashMap<>();
        private String where = "";

        Writer(Set<String> checking, String model) {
            this.checking = checking;
            this.model = model;
        }

        String body(CodegenModel cm) {
            StringBuilder out = new StringBuilder();
            if (cm.isEnum) {
                return "";
            }
            if (Boolean.TRUE.equals(cm.vendorExtensions.get(UnionRules.ENUM))) {
                List<CodegenProperty> variants = UnionRules.variants(cm);
                List<String> arms = new ArrayList<>();
                for (CodegenProperty v : variants) {
                    where = cm.classname + "::" + v.name;
                    List<String> lines = value(Type.parse(v.dataType), v, Expr.binding("value"), "", 0);
                    if (!lines.isEmpty()) {
                        arms.add(arm("Self::" + v.name + "(value)", lines));
                    }
                }
                Object dispatch = cm.vendorExtensions.get(UnionRules.DISPATCH);
                boolean catchAll = dispatch instanceof UnionRules.Dispatch d && d.catchAll != null;
                match(out, arms, arms.size() == variants.size() && !catchAll);
                return out.toString();
            }
            if (cm.discriminator != null) {
                List<String> arms = new ArrayList<>();
                int all = 0;
                boolean inline = Boolean.TRUE.equals(cm.discriminator.getVendorExtensions().get("x-rust-inline-variants"));
                for (CodegenDiscriminator.MappedModel m : cm.discriminator.getMappedModels()) {
                    all++;
                    where = cm.classname + "::" + m.getModelName();
                    if (inline) {
                        List<String> lines = new ArrayList<>();
                        List<String> bound = new ArrayList<>();
                        for (CodegenProperty cp : cm.vars) {
                            List<String> field = field(cp, Expr.binding(cp.name), true);
                            if (!field.isEmpty()) {
                                bound.add(cp.name);
                                lines.addAll(field);
                            }
                        }
                        if (!lines.isEmpty()) {
                            arms.add(arm("Self::" + m.getModelName() + " { " + String.join(", ", bound) + ", .. }",
                                    lines));
                        }
                    } else {
                        String target = m.getModel() != null ? m.getModel().classname : m.getModelName();
                        if (checking.contains(target)) {
                            arms.add(arm("Self::" + m.getModelName() + "(value)", List.of(call("value", ""))));
                        }
                    }
                }
                match(out, arms, arms.size() == all);
                return out.toString();
            }
            for (CodegenProperty cp : cm.vars) {
                where = cm.classname + "." + cp.baseName;
                for (String line : field(cp, Expr.field(cp.name), false)) {
                    out.append(line).append('\n');
                }
            }
            return indent(out.toString(), 8);
        }

        /** The arms, as {@code if let} for one and as a {@code match} for more. */
        private void match(StringBuilder out, List<String> arms, boolean exhaustive) {
            if (arms.isEmpty()) {
                return;
            }
            StringBuilder code = new StringBuilder();
            if (arms.size() == 1 && !exhaustive) {
                String arm = arms.get(0);
                int arrow = arm.indexOf(" => {\n");
                code.append("if let ").append(arm, 0, arrow).append(" = self {\n")
                        .append(arm.substring(arrow + " => {\n".length()));
            } else {
                code.append("match self {\n");
                for (String arm : arms) {
                    code.append(indent(arm, 4));
                }
                if (!exhaustive) {
                    code.append("    _ => {}\n");
                }
                code.append("}\n");
            }
            out.append(indent(code.toString(), 8));
        }

        private static String arm(String pattern, List<String> lines) {
            StringBuilder arm = new StringBuilder(pattern).append(" => {\n");
            for (String line : lines) {
                arm.append("    ").append(line).append('\n');
            }
            return arm.append("}\n").toString();
        }

        /**
         * The checks of a field, as the model template types it: a struct field is {@code Option<Option<T>>} when
         * optional and nullable, a field of an inline variant only {@code Option<T>}.
         */
        private List<String> field(CodegenProperty cp, Expr expr, boolean variant) {
            Type type;
            if (cp.isEnum) {
                // An inline enum, alone, in a list or as the values of a map: the enum itself has nothing to check.
                type = cp.isArray ? Type.of(Boolean.TRUE.equals(cp.getUniqueItems())
                        ? "std::collections::HashSet" : "Vec", Type.of("enum"))
                        : cp.isMap ? Type.of("std::collections::HashMap", Type.of("String"), Type.of("enum"))
                        : Type.of("enum");
            } else if (cp.isByteArray) {
                type = Type.of("Vec<u8>");
            } else {
                type = Type.parse(cp.dataType);
            }
            if (!cp.required) {
                type = Type.of("Option", type);
            }
            if (cp.isNullable && !(variant && !cp.required)) {
                type = Type.of("Option", type);
            }
            return value(type, cp, expr, ".at(" + quote(cp.baseName) + ")", 0);
        }

        /** The checks of a value of {@code type}, constrained by {@code cp}, whose errors map through {@code chain}. */
        private List<String> value(Type type, CodegenProperty cp, Expr expr, String chain, int depth) {
            List<String> lines = new ArrayList<>();
            if (type.option()) {
                int options = 0;
                Type inner = type;
                while (inner.option()) {
                    options++;
                    inner = inner.args().get(0);
                }
                if (!explicit(inner, cp)) {
                    if (holdsChecking(inner)) {
                        lines.add(call(expr.ref(), chain));
                    }
                    return lines;
                }
                String name = "value" + depth;
                List<String> inside = value(inner, cp, Expr.binding(name), chain, depth + 1);
                if (!inside.isEmpty()) {
                    lines.add("if let " + "Some(".repeat(options) + name + ")".repeat(options) + " = " + expr.ref()
                            + " {");
                    inside.forEach(l -> lines.add("    " + l));
                    lines.add("}");
                }
                return lines;
            }
            if (type.box() || type.model() != null) {
                if (holdsChecking(type)) {
                    lines.add(call(expr.ref(), chain));
                }
                return lines;
            }
            if (type.list()) {
                if (cp != null && (cp.getMinItems() != null || cp.getMaxItems() != null)) {
                    lines.add(V + "items(" + expr.recv() + ".len(), " + some(cp.getMinItems()) + ", "
                            + some(cp.getMaxItems()) + ")" + mapErr(chain) + "?;");
                }
                if (cp != null && Boolean.TRUE.equals(cp.getUniqueItems()) && !type.set()) {
                    lines.add(V + "unique_items(" + expr.ref() + ")" + mapErr(chain) + "?;");
                }
                Type item = type.args().get(0);
                CodegenProperty items = cp == null || cp.isEnum ? null : cp.items;
                if (explicit(item, items)) {
                    String index = "index" + depth;
                    String name = "item" + depth;
                    List<String> inside = value(item, items, Expr.binding(name), ".at_index(" + index + ")" + chain,
                            depth + 1);
                    if (!inside.isEmpty()) {
                        lines.add("for (" + index + ", " + name + ") in " + expr.recv() + ".iter().enumerate() {");
                        inside.forEach(l -> lines.add("    " + l));
                        lines.add("}");
                    }
                } else if (!type.set() && holdsChecking(item)) {
                    lines.add(call(expr.ref(), chain));
                }
                return lines;
            }
            if (type.map()) {
                if (cp != null && (cp.getMinProperties() != null || cp.getMaxProperties() != null)) {
                    lines.add(V + "properties(" + expr.recv() + ".len(), " + some(cp.getMinProperties()) + ", "
                            + some(cp.getMaxProperties()) + ")" + mapErr(chain) + "?;");
                }
                Type item = type.args().get(1);
                CodegenProperty items = cp == null || cp.isEnum ? null : cp.items;
                if (explicit(item, items)) {
                    String key = "key" + depth;
                    String name = "entry" + depth;
                    List<String> inside = value(item, items, Expr.binding(name), ".at_key(" + key + ")" + chain,
                            depth + 1);
                    if (!inside.isEmpty()) {
                        lines.add("for (" + key + ", " + name + ") in " + expr.ref() + " {");
                        inside.forEach(l -> lines.add("    " + l));
                        lines.add("}");
                    }
                } else if (holdsChecking(item)) {
                    lines.add(call(expr.ref(), chain));
                }
                return lines;
            }
            if (cp == null) {
                return lines;
            }
            if (type.name().equals("String")) {
                if (cp.getMinLength() != null || cp.getMaxLength() != null) {
                    lines.add(V + "length(" + expr.ref() + ", " + some(cp.getMinLength()) + ", "
                            + some(cp.getMaxLength()) + ")" + mapErr(chain) + "?;");
                }
                if (cp.getPattern() != null) {
                    String pattern = cp.getPattern();
                    if (RustRegex.unsupported(pattern) == null) {
                        String name = patterns.computeIfAbsent(pattern, p -> "PATTERN_" + patterns.size());
                        lines.add(V + "pattern(" + expr.ref() + ", &" + name + ", " + rawString(pattern) + ")"
                                + mapErr(chain) + "?;");
                    }
                }
                return lines;
            }
            boolean floating = FLOATS.contains(type.name());
            if (floating || INTEGERS.containsKey(type.name())) {
                bound(lines, "minimum", cp.getExclusiveMinimum() ? "exclusive_minimum" : "minimum", cp.getMinimum(),
                        type.name(), expr, chain);
                bound(lines, "maximum", cp.getExclusiveMaximum() ? "exclusive_maximum" : "maximum", cp.getMaximum(),
                        type.name(), expr, chain);
                if (cp.getMultipleOf() != null) {
                    bound(lines, "multipleOf", "multiple_of", cp.getMultipleOf().toString(), type.name(), expr, chain);
                }
            }
            return lines;
        }

        private void bound(List<String> lines, String keyword, String function, String text, String rustType,
                           Expr expr, String chain) {
            if (text == null) {
                return;
            }
            BigDecimal bound;
            try {
                bound = new BigDecimal(text);
            } catch (NumberFormatException e) {
                return;
            }
            String value = expr.val();
            String literal;
            BigInteger[] range = INTEGERS.get(rustType);
            if (range == null) {
                literal = floatLiteral(bound, rustType.equals("f32"));
                if (literal == null) {
                    // beyond f32: compared as f64
                    value = value + " as f64";
                    literal = floatLiteral(bound, false);
                }
            } else if (isIntegral(bound) && !function.equals("multiple_of")
                    && always(function, bound.toBigInteger(), range)) {
                // every value of the type meets it (the int64 range itself, say)
                return;
            } else if (isIntegral(bound) && bound.toBigInteger().compareTo(range[0]) >= 0
                    && bound.toBigInteger().compareTo(range[1]) <= 0) {
                literal = bound.toBigInteger().toString();
            } else {
                // a fractional bound or one outside the type: compared as f64
                value = value + " as f64";
                literal = floatLiteral(bound, false);
            }
            if (literal == null) {
                // beyond f64: every finite value meets it, or none does
                return;
            }
            lines.add(V + function + "(" + value + ", " + literal + ")" + mapErr(chain) + "?;");
        }

        /** Whether every value of an integer type in {@code range} meets the bound. */
        private static boolean always(String function, BigInteger bound, BigInteger[] range) {
            return switch (function) {
                case "minimum" -> bound.compareTo(range[0]) <= 0;
                case "exclusive_minimum" -> bound.compareTo(range[0]) < 0;
                case "maximum" -> bound.compareTo(range[1]) >= 0;
                case "exclusive_maximum" -> bound.compareTo(range[1]) > 0;
                default -> false;
            };
        }

        /** Whether checking a value of {@code type} needs more than calling {@code validate()} on it. */
        private boolean explicit(Type type, CodegenProperty cp) {
            if (type.option()) {
                return explicit(type.args().get(0), cp);
            }
            if (type.box() || type.model() != null) {
                return false;
            }
            if (cp == null) {
                return false;
            }
            if (type.list()) {
                return cp.getMinItems() != null || cp.getMaxItems() != null
                        || (Boolean.TRUE.equals(cp.getUniqueItems()) && !type.set())
                        || (!cp.isEnum && explicit(type.args().get(0), cp.items));
            }
            if (type.map()) {
                return cp.getMinProperties() != null || cp.getMaxProperties() != null
                        || (!cp.isEnum && explicit(type.args().get(1), cp.items));
            }
            if (type.name().equals("String")) {
                return cp.getMinLength() != null || cp.getMaxLength() != null
                        || (cp.getPattern() != null && RustRegex.unsupported(cp.getPattern()) == null);
            }
            if (FLOATS.contains(type.name()) || INTEGERS.containsKey(type.name())) {
                return cp.getMinimum() != null || cp.getMaximum() != null || cp.getMultipleOf() != null;
            }
            return false;
        }

        /** Whether a value of {@code type} holds a model whose {@code validate()} can fail. */
        private boolean holdsChecking(Type type) {
            String model = type.model();
            if (model != null) {
                return checking.contains(model);
            }
            if (type.set()) {
                return false;
            }
            return type.args().stream().anyMatch(this::holdsChecking);
        }

        private static String call(String ref, String chain) {
            return V + "Validate::validate(" + ref + ")" + mapErr(chain) + "?;";
        }

        private static String mapErr(String chain) {
            return chain.isEmpty() ? "" : ".map_err(|e| e" + chain + ")";
        }
    }

    private static String some(Integer value) {
        return value == null ? "None" : "Some(" + value + ")";
    }

    private static boolean isIntegral(BigDecimal value) {
        return value.signum() == 0 || value.stripTrailingZeros().scale() <= 0;
    }

    /**
     * The shortest Rust float literal of {@code value} as an {@code f32} or an {@code f64}, so clippy sees no excessive
     * precision; {@code null} when the value is beyond the type's range.
     */
    static String floatLiteral(BigDecimal value, boolean f32) {
        String text;
        if (f32) {
            float f = value.floatValue();
            if (Float.isInfinite(f)) {
                return null;
            }
            text = Float.toString(f);
        } else {
            double d = value.doubleValue();
            if (Double.isInfinite(d)) {
                return null;
            }
            text = Double.toString(d);
        }
        return text.replace("E", "e");
    }

    /** A Rust (raw) string literal of {@code text}. */
    static String rawString(String text) {
        String hashes = "";
        while (text.contains("\"" + hashes)) {
            hashes += "#";
        }
        return "r" + hashes + "\"" + text + "\"" + hashes;
    }

    /** A Rust string literal of {@code text}. */
    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static String indent(String text, int spaces) {
        if (text.isEmpty()) {
            return text;
        }
        String pad = " ".repeat(spaces);
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (!line.isEmpty()) {
                out.append(pad).append(line);
            }
            out.append('\n');
        }
        // split keeps the empty string after the last newline
        return out.substring(0, out.length() - 1);
    }
}
