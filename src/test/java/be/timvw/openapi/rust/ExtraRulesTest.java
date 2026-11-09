package be.timvw.openapi.rust;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.ClientOptInput;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The renderings from the generator comparison (docs/comparison): the options typedEnumParameters and
 * deprecatedAttributes, which are off by default and leave the output as it was, and nullable union fields and schema
 * defaults in the documentation, which are always on.
 */
class ExtraRulesTest {

    @TempDir
    static Path on;

    @TempDir
    static Path off;

    private static final Map<String, Object> BASE = Map.of(TimvwRustCodegen.MULTIPART_HELPER, "true");

    @BeforeAll
    static void generate() {
        Map<String, Object> all = new LinkedHashMap<>(BASE);
        all.put(TimvwRustCodegen.TYPED_ENUM_PARAMETERS, "true");
        all.put(TimvwRustCodegen.DEPRECATED_ATTRIBUTES, "true");
        generate(on, all, "src/test/resources/extras.yaml");
        generate(off, BASE, "src/test/resources/extras.yaml");
    }

    private static void generate(Path output, Map<String, Object> options, String spec) {
        Map<String, Object> props = new LinkedHashMap<>(options);
        props.put("packageName", "extras");
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(spec).toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props);
        ClientOptInput input = configurator.toClientOptInput();
        new DefaultGenerator().opts(input).generate();
    }

    private static String read(Path output, String relative) throws IOException {
        return Files.readString(output.resolve(relative));
    }

    private static String function(Path output, String name) throws IOException {
        String api = read(output, "src/apis/default_api.rs");
        int start = api.indexOf("pub async fn " + name + "(");
        assertTrue(start >= 0, api);
        return api.substring(start, api.indexOf('{', start));
    }

    private static void assertContains(String text, String needle) {
        assertTrue(text.contains(needle), needle + " not in:\n" + text);
    }

    // ---------------------------------------------------------------- typedEnumParameters

    @Test
    void enumParametersTakeTheEnum() throws IOException {
        String list = function(on, "list_things");
        assertContains(list, "order: Option<models::ListThingsOrder>");
        assertContains(list, "include_left_square_bracket_right_square_bracket: Option<Vec<models::ListThingsIncludeItem>>");
        assertContains(read(on, "src/models/list_things_order.rs"), "pub enum ListThingsOrder {");
        assertContains(read(on, "src/models/list_things_include_item.rs"), "pub enum ListThingsIncludeItem {");
    }

    @Test
    void arrayHeadersOfEnumsJoinTheirDisplay() throws IOException {
        assertContains(function(on, "list_things"), "beta_features: Option<Vec<models::ListThingsBetaFeaturesItem>>");
        String api = read(on, "src/apis/default_api.rs");
        assertContains(api, "param_value.iter().map(|p| p.to_string()).collect::<Vec<String>>().join(\",\")");
        // an array of strings renders as before
        assertContains(api, "req_builder.header(\"Trace-Ids\", param_value.join(\",\").to_string())");
        assertContains(read(off, "src/apis/default_api.rs"),
                "req_builder.header(\"Beta-Features\", param_value.join(\",\").to_string())");
    }

    @Test
    void multipartEnumPartsTakeTheEnumAndSendTheWireValue() throws IOException {
        String upload = function(on, "upload");
        assertContains(upload, "purpose: models::UploadPurpose");
        assertContains(upload, "mode: Option<models::UploadMode>");
        String api = read(on, "src/apis/default_api.rs");
        assertContains(api, "multipart_form.text(\"purpose\", p_form_purpose.to_string())");
        assertFalse(api.contains("serde_json::to_string(&p_form_purpose)"), api);
    }

    @Test
    void enumParametersStayStringsWithoutTheOption() throws IOException {
        assertContains(function(off, "list_things"), "order: Option<&str>");
        assertContains(function(off, "upload"), "purpose: &str");
        assertFalse(Files.exists(off.resolve("src/models/list_things_order.rs")));
    }

    @Test
    void aTakenNameFailsGeneration(@TempDir Path out) throws IOException {
        Path spec = out.resolve("taken.yaml");
        Files.writeString(spec, Files.readString(Path.of("src/test/resources/extras.yaml"))
                + "    ListThingsOrder:\n      type: string\n");
        Map<String, Object> options = new LinkedHashMap<>(BASE);
        options.put(TimvwRustCodegen.TYPED_ENUM_PARAMETERS, "true");
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> generate(out.resolve("gen"), options, spec.toString()));
        assertTrue(rootMessage(e).contains("ListThingsOrder"), rootMessage(e));
    }

    private static String rootMessage(Throwable e) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }

    // ---------------------------------------------------------------- nullable union fields

    @Test
    void nullableUnionFieldsCanHoldNull() throws IOException {
        String create = read(on, "src/models/create_thing.rs");
        assertContains(create, "pub stop: Option<models::CreateThingStop>,");
        assertContains(create, "pub hint: Option<Option<models::CreateThingHint>>,");
        assertContains(create, "#[serde(rename = \"hint\", default, with = \"::serde_with::rust::double_option\"");
    }

    // ---------------------------------------------------------------- schema defaults

    @Test
    void defaultsAreDocumented() throws IOException {
        String create = read(on, "src/models/create_thing.rs");
        assertContains(create, "/// Stream the answer.  Defaults to `false`.");
        assertContains(read(on, "docs/DefaultApi.md"), "Defaults to `20`.");
    }

    // ---------------------------------------------------------------- deprecatedAttributes

    @Test
    void deprecatedFieldsAndModelsAreMarked() throws IOException {
        String thing = read(on, "src/models/thing.rs");
        assertContains(thing, "    #[deprecated]\n    #[serde(rename = \"label\"");
        assertTrue(thing.startsWith("#![allow(deprecated)]\n"), thing);
        String old = read(on, "src/models/old_thing.rs");
        assertContains(old, "#[deprecated]\n#[derive(");
        assertTrue(read(on, "src/apis/default_api.rs").startsWith("#![allow(deprecated)]\n"));
    }

    @Test
    void nothingIsDeprecatedWithoutTheOption() throws IOException {
        assertFalse(read(off, "src/models/thing.rs").contains("deprecated"));
        assertFalse(read(off, "src/models/old_thing.rs").contains("deprecated"));
    }

    // ---------------------------------------------------------------- helper pruning (always on)

    @Test
    void unusedHelpersAreLeftOut() throws IOException {
        for (Path out : List.of(on, off)) {
            String dispatch = read(out, "src/models/union_dispatch.rs");
            assertFalse(dispatch.contains("fn first_of"), dispatch);
            assertFalse(dispatch.contains("type Attempt"), dispatch);
            assertContains(dispatch, "pub(crate) fn decode");
            String helper = read(out, "src/apis/multipart_helper.rs");
            assertContains(helper, "pub fn add_file_to_form");
            // kept on purpose
            assertContains(helper, "pub fn file_part_from_bytes");
        }
    }

    @Test
    void pruneAllHelpersAlsoDropsHelpersKeptOnPurpose(@TempDir Path out) throws IOException {
        Map<String, Object> options = new LinkedHashMap<>(BASE);
        options.put(TimvwRustCodegen.PRUNE_ALL_HELPERS, "true");
        generate(out, options, "src/test/resources/extras.yaml");
        String helper = read(out, "src/apis/multipart_helper.rs");
        assertContains(helper, "pub fn add_file_to_form");
        assertFalse(helper.contains("file_part_from_bytes"), helper);
        assertFalse(helper.contains("add_pathbuf_to_form"), helper);
        assertContains(helper, "use std::path::Path;");
        assertFalse(helper.contains("PathBuf"), helper);
    }

    @Test
    void enumsWithoutADescriptionHaveNoEmptyDocComment() throws IOException {
        List<String> empty = new ArrayList<>();
        try (Stream<Path> files = Files.walk(on.resolve("src/models"))) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".rs"))::iterator) {
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i + 1 < lines.size(); i++) {
                    if (lines.get(i).trim().equals("///") && !lines.get(i + 1).trim().startsWith("///")) {
                        empty.add(f.getFileName() + ":" + (i + 1));
                    }
                }
            }
        }
        assertEquals(List.of(), empty);
    }

    @Test
    void pruningKeepsWhatItemsCallAndDropsUnusedImports() {
        String module = String.join("\n",
                "//! Helpers.",
                "",
                "use std::fmt::Write;",
                "use std::path::Path;",
                "",
                "/// Used by callers.",
                "pub(crate) fn used(p: &Path) -> String {",
                "    inner()",
                "}",
                "",
                "fn inner() -> String {",
                "    String::new()",
                "}",
                "",
                "/// Nobody calls it.",
                "pub(crate) fn unused(w: &mut String) {",
                "    let _ = write!(w, \"x\");",
                "",
                "    let _ = w;",
                "}",
                "");
        List<String> dropped = new ArrayList<>();
        String pruned = HelperPruning.prune(module, "helpers::used(x)", dropped);
        assertEquals(List.of("unused"), dropped);
        assertContains(pruned, "fn inner()");
        assertContains(pruned, "use std::path::Path;");
        assertFalse(pruned.contains("use std::fmt::Write;"), pruned);
        assertFalse(pruned.contains("let _ = w;"), pruned);
    }
}
