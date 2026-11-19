package be.timvw.openapi.rust;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code splitCrates}: the crate is written as a Cargo workspace of several crates, each small enough to compile (and
 * document) in a fraction of the memory of the whole, behind a facade crate with the same paths and features as the
 * single crate (see docs/guides/split-crates.md).
 *
 * <ul>
 *   <li>{@code <package>-common}: the models more than one crate needs (and the ungated ones), the HTTP glue
 *   ({@code configuration}, {@code Error}, ...) and the helpers;</li>
 *   <li>{@code <package>-<crate>}: per crate of {@code apiFeaturesFile} (a group's {@code crate}, or the group's own
 *   name), the models only its groups need and their API modules (an API module whose operations are in several
 *   crates is split, a part per crate);</li>
 *   <li>{@code <package>-untagged}: the models no operation reaches (feature {@code types} alone), which may use the
 *   models of every other crate;</li>
 *   <li>{@code <package>}: the facade, which re-exports them under {@code models} and {@code apis}.</li>
 * </ul>
 *
 * <p>Every crate keeps the {@code #[cfg(feature = ...)]} of the single crate and defines the features it uses. The
 * generated code is moved, not rewritten: each crate's {@code models} and {@code apis} glob re-export those of
 * {@code common}, so {@code crate::models::X} and {@code super::Error} resolve as before. The helpers other crates call
 * become {@code pub} and {@code #[doc(hidden)]}. The split is checked: a crate that names a model of a crate it does
 * not depend on fails generation.</p>
 */
final class CrateSplit {

    /** The crate of the shared models and the HTTP glue. */
    static final String COMMON = "common";
    /** The crate of the models no operation reaches. */
    static final String UNTAGGED = "untagged";
    /** The directory of the crates, relative to the output (the facade). */
    static final String DIR = "crates";
    /** On an operation: the crate its items go into. */
    static final String OPERATION_CRATE = "x-rust-split-crate";
    /** On a model: it is not in {@code common}, whose {@code models/mod.rs} the template renders. */
    static final String ELSEWHERE = "x-rust-split-elsewhere";
    /** The line api.mustache writes before the items of an operation: they go into the crate it names. */
    static final String MARKER = "// @timvw-rust-split-crate ";

    private static final Pattern MODEL_REF = Pattern.compile("\\bmodels::([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern MODEL_REF_GROUP = Pattern.compile("\\bmodels::\\{([^}]*)}");
    private static final Pattern PUB_CRATE = Pattern.compile("(?m)^([ \\t]*)pub\\(crate\\) ");

    /** A model of the single crate: its file (module), type and cfg. */
    record Model(String file, String type, String cfg, String crate) {
    }

    private final String packageName;
    private final ApiFeatures.Plan plan;
    /** The groups of every crate but {@code common} and {@code untagged}, by crate. */
    private final SortedMap<String, SortedSet<String>> groups = new TreeMap<>();
    /** The models by file. */
    private final Map<String, Model> models = new TreeMap<>();
    /** By API module class name: the features of its operations, by crate. */
    private final Map<String, SortedMap<String, SortedSet<String>>> modules = new TreeMap<>();
    /** The file of every API module, by class name. */
    private final Map<String, String> moduleFiles = new TreeMap<>();

    CrateSplit(String packageName, ApiFeatures.Plan plan) {
        this.packageName = packageName;
        this.plan = plan;
        for (String f : plan.features) {
            String crate = plan.crates.get(f);
            if (crate.equals(COMMON) || crate.equals(UNTAGGED)) {
                throw new IllegalArgumentException(String.format("%s: %s: feature '%s' would go into the crate '%s', "
                        + "the name of the %s crate; give its group another 'crate' in apiFeaturesFile",
                        TimvwRustCodegen.GENERATOR_NAME, TimvwRustCodegen.SPLIT_CRATES, f, crate,
                        crate.equals(COMMON) ? "shared" : "untagged models'"));
            }
            groupsOf(crate).add(f);
        }
    }

    private SortedSet<String> groupsOf(String crate) {
        return groups.computeIfAbsent(crate, k -> new TreeSet<>());
    }

    // ---------------------------------------------------------------- the plan

    /** The crate of an operation, from its features; they must all be in one crate. */
    String operationCrate(String what, SortedSet<String> features) {
        Set<String> crates = features.stream().map(plan.crates::get).collect(Collectors.toCollection(TreeSet::new));
        if (crates.size() != 1) {
            throw new IllegalArgumentException(String.format("%s: %s: operation %s has the features %s, which are "
                    + "in the crates %s; an operation's functions go into one crate: give those groups the same "
                    + "'crate' in apiFeaturesFile", TimvwRustCodegen.GENERATOR_NAME, TimvwRustCodegen.SPLIT_CRATES,
                    what, features, crates));
        }
        return crates.iterator().next();
    }

    /** Records that an API module has operations of {@code features} in {@code crate}. */
    void addOperation(String module, String crate, Set<String> features) {
        modules.computeIfAbsent(module, k -> new TreeMap<>()).computeIfAbsent(crate, k -> new TreeSet<>())
                .addAll(features);
    }

    void moduleFile(String module, String file) {
        moduleFiles.put(module, file);
    }

    /**
     * The crate of a model: {@code untagged} when no operation needs it, {@code common} when every feature does or
     * the features that do are in several crates, otherwise the crate of those features.
     */
    String modelCrate(SortedSet<String> features) {
        if (features == null || features.isEmpty()) {
            return UNTAGGED;
        }
        if (features.equals(plan.features)) {
            return COMMON;
        }
        Set<String> crates = features.stream().map(plan.crates::get).collect(Collectors.toSet());
        return crates.size() == 1 ? crates.iterator().next() : COMMON;
    }

    void addModel(String file, String type, String cfg, String crate) {
        models.put(file, new Model(file, type, cfg, crate));
    }

    boolean hasUntagged() {
        return models.values().stream().anyMatch(m -> m.crate().equals(UNTAGGED));
    }

    String packageOf(String crate) {
        return packageName + "-" + crate;
    }

    private String identOf(String crate) {
        return packageOf(crate).replace('-', '_');
    }

    /** The crates, in the order they depend on each other: common, the groups' crates, untagged. */
    List<String> crates() {
        List<String> out = new ArrayList<>();
        out.add(COMMON);
        for (String c : groups.keySet()) {
            // a crate whose features select only operations of modules in other crates still has its models
            out.add(c);
        }
        if (hasUntagged()) {
            out.add(UNTAGGED);
        }
        return out;
    }

    private static String quoted(String s) {
        return "\"" + s + "\"";
    }

    private static String any(Set<String> features, String suffix) {
        List<String> each = features.stream().map(f -> "feature = \"" + f + suffix + "\"").toList();
        return each.size() == 1 ? each.get(0) : "any(" + String.join(", ", each) + ")";
    }

    // ---------------------------------------------------------------- the facade's Cargo.toml

    /**
     * What the facade's Cargo.toml needs: its dependencies, the members and the workspace's dependencies, and the
     * group features mapped to the crates' features.
     *
     * @param groupDeps the {@code apiFeatureGroups} of the single crate, whose {@code typesDeps} this replaces
     */
    @SuppressWarnings("unchecked")
    void describeFacade(Map<String, Object> objs, List<Map<String, Object>> groupDeps) {
        String common = packageOf(COMMON);
        String version = String.valueOf(objs.get("packageVersion"));
        List<String> facadeDeps = new ArrayList<>();
        List<String> workspaceDeps = new ArrayList<>();
        List<String> members = new ArrayList<>();
        for (String c : crates()) {
            members.add(DIR + "/" + c);
            workspaceDeps.add(packageOf(c) + " = { path = \"" + DIR + "/" + c + "\", version = \"=" + version
                    + "\", default-features = false }");
            facadeDeps.add(packageOf(c) + " = { workspace = true" + (c.equals(COMMON) ? "" : ", optional = true")
                    + " }");
        }
        objs.put("splitCrates", true);
        objs.put("splitCommonPackage", common);
        objs.put("splitMembers", members);
        objs.put("splitWorkspaceDependencies", workspaceDeps);
        objs.put("splitFacadeDependencies", facadeDeps);
        objs.put("apiDeps", quoted(common + "/_api"));
        for (Map<String, Object> g : groupDeps) {
            String f = String.valueOf(g.get("name"));
            String pkg = packageOf(plan.crates.get(f));
            g.put("typesDeps", quoted("dep:" + pkg) + ", " + quoted(pkg + "/" + f + ApiFeatures.TYPES_SUFFIX) + ", "
                    + quoted(common + "/" + f + ApiFeatures.TYPES_SUFFIX));
            g.put("apiFeatureDeps", ", " + quoted(pkg + "/" + f));
        }
        List<String> types = new ArrayList<>(plan.features.stream().map(f -> f + ApiFeatures.TYPES_SUFFIX).toList());
        if (hasUntagged()) {
            types.add("dep:" + packageOf(UNTAGGED));
            types.add(packageOf(UNTAGGED) + "/" + ApiFeatures.TYPES);
        }
        objs.put("apiFeaturesTypes", types);
    }

    // ---------------------------------------------------------------- the files

    /** Renders a template of the generator into a file, with the data given. */
    interface Renderer {
        void render(Map<String, Object> data, String template, Path target) throws IOException;
    }

    /**
     * Moves the written single crate into the workspace: the models and API modules into their crates, the glue into
     * {@code common}, and writes each crate's manifest, README, lib.rs and module files and the facade's modules.
     *
     * @param bundle the data the supporting files were rendered with
     * @param origin the single crate's own feature dependencies: {@code _api} ({@code ""} key), each group's
     *        {@code -types} (by feature), {@code types} ({@code "types"} key)
     */
    void write(Path out, Map<String, Object> bundle, Map<String, String> origin, Renderer renderer,
            BiConsumer<String, Object> log) throws IOException {
        Path srcModels = out.resolve("src").resolve("models");
        Path srcApis = out.resolve("src").resolve("apis");
        Path crates = out.resolve(DIR);
        for (String c : crates()) {
            for (String dir : List.of("models", "apis")) {
                deleteTree(crates.resolve(c).resolve("src").resolve(dir));
            }
        }
        Path commonSrc = crates.resolve(COMMON).resolve("src");
        Files.createDirectories(commonSrc);
        Files.move(srcModels, commonSrc.resolve("models"));
        boolean client = Files.isDirectory(srcApis);
        if (client) {
            Files.move(srcApis, commonSrc.resolve("apis"));
        }

        // the models
        Map<String, List<Model>> byCrate = new TreeMap<>();
        for (Model m : models.values()) {
            byCrate.computeIfAbsent(m.crate(), k -> new ArrayList<>()).add(m);
            if (!m.crate().equals(COMMON)) {
                Path from = commonSrc.resolve("models").resolve(m.file() + ".rs");
                Path to = crates.resolve(m.crate()).resolve("src").resolve("models").resolve(m.file() + ".rs");
                if (Files.exists(from)) {
                    Files.createDirectories(to.getParent());
                    Files.move(from, to);
                }
            }
        }

        // the API modules, a part per crate
        Map<String, Map<String, String>> apiParts = new TreeMap<>();
        Map<String, SortedMap<String, String>> apiModulesByCrate = new TreeMap<>();
        Map<String, List<String>> splitModules = new TreeMap<>();
        if (client) {
            Path commonApis = commonSrc.resolve("apis");
            for (Map.Entry<String, SortedMap<String, SortedSet<String>>> e : modules.entrySet()) {
                String file = moduleFiles.get(e.getKey());
                if (file == null) {
                    throw new IllegalStateException(TimvwRustCodegen.GENERATOR_NAME + ": " + TimvwRustCodegen.SPLIT_CRATES
                            + ": no file for API module " + e.getKey());
                }
                Path from = commonApis.resolve(file + ".rs");
                if (!Files.exists(from)) {
                    continue;
                }
                Map<String, String> parts = partition(Files.readString(from), file);
                Files.delete(from);
                for (Map.Entry<String, String> p : parts.entrySet()) {
                    Path to = crates.resolve(p.getKey()).resolve("src").resolve("apis").resolve(file + ".rs");
                    Files.createDirectories(to.getParent());
                    Files.writeString(to, p.getValue());
                    SortedSet<String> features = e.getValue().get(p.getKey());
                    if (features == null) {
                        throw new IllegalStateException(TimvwRustCodegen.GENERATOR_NAME + ": "
                                + TimvwRustCodegen.SPLIT_CRATES + ": " + file + " has items of crate " + p.getKey()
                                + ", which none of its operations is in");
                    }
                    apiModulesByCrate.computeIfAbsent(p.getKey(), k -> new TreeMap<>()).put(file, any(features, ""));
                }
                if (parts.size() > 1) {
                    splitModules.put(file, new ArrayList<>(parts.keySet()));
                }
                apiParts.put(file, parts);
            }
        }

        // the helpers other crates call: public, hidden from the docs
        try (Stream<Path> files = Files.walk(commonSrc)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".rs")).toList()) {
                String name = f.getFileName().toString().replaceFirst("\\.rs$", "");
                boolean model = f.getParent().getFileName().toString().equals("models") && models.containsKey(name);
                if (model) {
                    continue;
                }
                String text = Files.readString(f);
                String exposed = PUB_CRATE.matcher(text).replaceAll("$1#[doc(hidden)]\n$1pub ");
                exposed = exposed.replace("\nenum ContentType {", "\n#[doc(hidden)]\npub enum ContentType {");
                if (!exposed.equals(text)) {
                    Files.writeString(f, exposed);
                }
            }
        }

        // the references between crates: each crate uses common's models and its own, untagged any crate's
        Map<String, String> owner = new TreeMap<>();
        for (Model m : models.values()) {
            owner.put(m.file(), m.crate());
            owner.put(m.type(), m.crate());
        }
        Map<String, SortedSet<String>> uses = new TreeMap<>();
        List<String> violations = new ArrayList<>();
        for (String c : crates()) {
            Path src = crates.resolve(c).resolve("src");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(src)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".rs")).sorted().toList()) {
                    for (String name : references(Files.readString(f))) {
                        String other = owner.get(name);
                        if (other == null || other.equals(c) || other.equals(COMMON)) {
                            continue;
                        }
                        if (c.equals(UNTAGGED)) {
                            uses.computeIfAbsent(c, k -> new TreeSet<>()).add(other);
                        } else {
                            violations.add(String.format("%s (crate %s) uses models::%s (crate %s)",
                                    src.relativize(f), c, name, other));
                        }
                    }
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException(String.format("%s: %s: a crate uses a model of a crate it does not depend "
                    + "on (%d): %s", TimvwRustCodegen.GENERATOR_NAME, TimvwRustCodegen.SPLIT_CRATES, violations.size(),
                    violations.subList(0, Math.min(10, violations.size()))));
        }

        // each crate's files
        String common = packageOf(COMMON);
        for (String c : crates()) {
            Path dir = crates.resolve(c);
            Path src = dir.resolve("src");
            Files.createDirectories(src.resolve("models"));
            boolean apis = c.equals(COMMON) ? client : apiModulesByCrate.containsKey(c);
            SortedSet<String> deps = uses.getOrDefault(c, new TreeSet<>());
            if (!c.equals(COMMON)) {
                Files.writeString(src.resolve("models").resolve("mod.rs"), modelsMod(byCrate.getOrDefault(c, List.of()),
                        deps));
                if (apis) {
                    Files.writeString(src.resolve("apis").resolve("mod.rs"), apisMod(apiModulesByCrate.get(c)));
                }
            }
            Map<String, Object> data = new LinkedHashMap<>(bundle);
            data.put("splitCratePackage", packageOf(c));
            data.put("splitFacadeIdent", packageName.replace('-', '_'));
            data.put("splitCrateApis", apis);
            data.put("splitCrateWhat", what(c));
            data.put("splitCrateDescription", "Internal part of " + packageName + ": " + what(c)
                    + ". Depend on " + packageName + " instead");
            List<String> dependencies = new ArrayList<>();
            List<String> features = new ArrayList<>();
            String apiDeps = origin.getOrDefault("", "");
            if (c.equals(COMMON)) {
                if (client) {
                    features.add("_api = [" + apiDeps + "]");
                }
                for (String tls : List.of("native-tls", "rustls", "retry", "tracing")) {
                    if (client) {
                        features.add(tls + " = [\"timvw-rust-runtime" + ("true".equals(String.valueOf(
                                bundle.get("runtimeOptional"))) ? "?" : "") + "/" + tls + "\"]");
                    }
                }
                for (String f : plan.features) {
                    features.add(f + ApiFeatures.TYPES_SUFFIX + " = [" + origin.getOrDefault(f, "") + "]");
                }
            } else {
                dependencies.add(common + " = { workspace = true }");
                if (apis) {
                    features.add("_api = [" + join(apiDeps, quoted(common + "/_api")) + "]");
                }
                if (c.equals(UNTAGGED)) {
                    List<String> typeDeps = new ArrayList<>();
                    if (!origin.getOrDefault(ApiFeatures.TYPES, "").isEmpty()) {
                        typeDeps.add(origin.get(ApiFeatures.TYPES));
                    }
                    for (String f : plan.features) {
                        typeDeps.add(quoted(common + "/" + f + ApiFeatures.TYPES_SUFFIX));
                    }
                    for (String d : deps) {
                        dependencies.add(packageOf(d) + " = { workspace = true }");
                        for (String f : groupsOf(d)) {
                            typeDeps.add(quoted(packageOf(d) + "/" + f + ApiFeatures.TYPES_SUFFIX));
                        }
                    }
                    features.add(ApiFeatures.TYPES + " = [" + String.join(", ", typeDeps) + "]");
                } else {
                    for (String f : groupsOf(c)) {
                        if (apis) {
                            features.add(f + " = [\"" + f + ApiFeatures.TYPES_SUFFIX + "\", \"_api\"]");
                        }
                        features.add(f + ApiFeatures.TYPES_SUFFIX + " = [" + join(origin.getOrDefault(f, ""),
                                quoted(common + "/" + f + ApiFeatures.TYPES_SUFFIX)) + "]");
                    }
                }
            }
            data.put("splitCrateDependencies", dependencies);
            data.put("splitCrateFeatures", features);
            renderer.render(data, "split_crate_Cargo.mustache", dir.resolve("Cargo.toml"));
            renderer.render(data, "split_crate_README.mustache", dir.resolve("README.md"));
            renderer.render(data, "split_crate_lib.mustache", src.resolve("lib.rs"));
            log.accept(packageOf(c), byCrate.getOrDefault(c, List.of()).size() + " models, "
                    + (apiModulesByCrate.containsKey(c) ? apiModulesByCrate.get(c).size() : 0) + " API modules");
        }

        // the facade
        Files.createDirectories(srcModels);
        Files.writeString(srcModels.resolve("mod.rs"), facadeModels());
        if (client) {
            Files.createDirectories(srcApis);
            Files.writeString(srcApis.resolve("mod.rs"), facadeApis(apiModulesByCrate, splitModules));
        }
    }

    private static String join(String a, String b) {
        return a.isEmpty() ? b : a + ", " + b;
    }

    private String what(String c) {
        if (c.equals(COMMON)) {
            return "the models several feature groups use, the configuration, errors and helpers";
        }
        if (c.equals(UNTAGGED)) {
            return "the models no operation uses, which the feature `types` compiles";
        }
        SortedSet<String> g = groupsOf(c);
        return "the models and API functions of the feature" + (g.size() == 1 ? " " : "s ")
                + g.stream().map(f -> "`" + f + "`").collect(Collectors.joining(", "));
    }

    /**
     * The parts of an API module by crate: what precedes the first marker is in every part, each marker's items are
     * in the part of its crate.
     */
    static Map<String, String> partition(String text, String file) {
        int first = text.indexOf(MARKER);
        Map<String, StringBuilder> parts = new TreeMap<>();
        if (first < 0) {
            throw new IllegalStateException(TimvwRustCodegen.GENERATOR_NAME + ": " + TimvwRustCodegen.SPLIT_CRATES
                    + ": " + file + ".rs has no operation");
        }
        String prelude = text.substring(0, first);
        String crate = null;
        for (String line : text.substring(first).split("\n", -1)) {
            if (line.startsWith(MARKER)) {
                crate = line.substring(MARKER.length()).trim();
                parts.computeIfAbsent(crate, k -> new StringBuilder(prelude));
                continue;
            }
            parts.get(crate).append(line).append('\n');
        }
        Map<String, String> out = new TreeMap<>();
        parts.forEach((c, b) -> out.put(c, b.toString().replaceAll("\n{3,}", "\n\n").replaceAll("\n+$", "\n")));
        return out;
    }

    /** The names a file uses as {@code models::<name>}, outside comments. */
    static Set<String> references(String text) {
        StringBuilder code = new StringBuilder();
        for (String line : text.split("\n")) {
            int at = line.indexOf("//");
            // a URL in a string is not a comment
            while (at >= 0 && at > 0 && line.charAt(at - 1) == ':') {
                at = line.indexOf("//", at + 2);
            }
            code.append(at >= 0 ? line.substring(0, at) : line).append('\n');
        }
        Set<String> out = new TreeSet<>();
        Matcher m = MODEL_REF.matcher(code);
        while (m.find()) {
            out.add(m.group(1));
        }
        Matcher g = MODEL_REF_GROUP.matcher(code);
        while (g.find()) {
            for (String part : g.group(1).split(",")) {
                String name = part.trim().split("::")[0].split("\\s")[0];
                if (!name.isEmpty()) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    private String modelsMod(List<Model> crateModels, SortedSet<String> deps) {
        StringBuilder b = new StringBuilder();
        b.append("// The models of the crates this one uses, under the same paths: `crate::models::X` names any of them.\n");
        b.append("#[doc(no_inline)]\npub use ").append(identOf(COMMON)).append("::models::*;\n");
        for (String d : deps) {
            b.append("#[doc(no_inline)]\npub use ").append(identOf(d)).append("::models::*;\n");
        }
        b.append('\n');
        List<Model> sorted = new ArrayList<>(crateModels);
        sorted.sort(Comparator.comparing(Model::file));
        for (Model m : sorted) {
            String cfg = m.cfg() == null ? "" : "#[cfg(" + m.cfg() + ")]\n";
            b.append(cfg).append("pub mod ").append(m.file()).append(";\n");
            b.append(cfg).append("pub use self::").append(m.file()).append("::").append(m.type()).append(";\n");
        }
        return b.toString();
    }

    private String apisMod(SortedMap<String, String> crateModules) {
        StringBuilder b = new StringBuilder();
        b.append("// The configuration, errors and helpers, under the same paths: `super::Error` names them.\n");
        b.append("#[doc(no_inline)]\npub use ").append(identOf(COMMON)).append("::apis::*;\n\n");
        crateModules.forEach((file, cfg) -> b.append("#[cfg(").append(cfg).append(")]\npub mod ").append(file)
                .append(";\n"));
        return b.toString();
    }

    private String facadeModels() {
        StringBuilder b = new StringBuilder();
        b.append("// splitCrates: the models of every crate of the workspace, under one path.\n");
        b.append("#![allow(unused_imports)]\n\n");
        b.append("pub use ").append(identOf(COMMON)).append("::models::*;\n");
        for (String c : crates()) {
            if (c.equals(COMMON)) {
                continue;
            }
            String cfg = c.equals(UNTAGGED) ? "feature = \"" + ApiFeatures.TYPES + "\""
                    : any(groupsOf(c), ApiFeatures.TYPES_SUFFIX);
            b.append("#[cfg(").append(cfg).append(")]\npub use ").append(identOf(c)).append("::models::*;\n");
        }
        return b.toString();
    }

    private String facadeApis(Map<String, SortedMap<String, String>> byCrate,
            Map<String, List<String>> split) {
        StringBuilder b = new StringBuilder();
        b.append("// splitCrates: the API modules of every crate of the workspace, under one path.\n");
        b.append("#![allow(unused_imports)]\n\n");
        b.append("pub use ").append(identOf(COMMON)).append("::apis::*;\n");
        for (String c : byCrate.keySet()) {
            b.append("#[cfg(").append(any(groupsOf(c), "")).append(")]\npub use ").append(identOf(c))
                    .append("::apis::*;\n");
        }
        // a module with a part in several crates: one module re-exporting every part (it shadows the globs above)
        split.forEach((file, crates) -> {
            SortedSet<String> all = new TreeSet<>();
            StringBuilder body = new StringBuilder();
            for (String c : crates) {
                String cfg = byCrate.get(c).get(file);
                body.append("    #[cfg(").append(cfg).append(")]\n    pub use ").append(identOf(c))
                        .append("::apis::").append(file).append("::*;\n");
            }
            for (String c : crates) {
                Matcher m = Pattern.compile("feature = \"([^\"]+)\"").matcher(byCrate.get(c).get(file));
                while (m.find()) {
                    all.add(m.group(1));
                }
            }
            b.append("\n#[cfg(").append(any(all, "")).append(")]\npub mod ").append(file).append(" {\n")
                    .append(body).append("}\n");
        });
        return b.toString();
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

}
