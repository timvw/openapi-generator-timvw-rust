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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Schema Object rules of docs/mapping/schema.md that no option test covers: scalars and formats, properties,
 * nullability, maps, arrays, enums and {@code allOf} of objects, rendered without options from
 * {@code src/test/resources/schema_mapping.yaml}.
 */
class SchemaMappingTest {

    @TempDir
    static Path out;

    @TempDir
    static Path noChrono;

    /**
     * Options every mapping test starts from. The former baseline options are always on and cannot be set, so it is
     * empty: the mapping is what the generator renders without options.
     */
    static final Map<String, Object> BASELINE = Map.of();

    @BeforeAll
    static void generate() {
        generate(out, BASELINE);
        Map<String, Object> strings = new LinkedHashMap<>(BASELINE);
        strings.put("useChrono", "false");
        generate(noChrono, strings);
    }

    private static void generate(Path output, Map<String, Object> options) {
        Map<String, Object> props = new LinkedHashMap<>(options);
        props.put("packageName", "schema_mapping");
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/schema_mapping.yaml").toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props);
        ClientOptInput input = configurator.toClientOptInput();
        new DefaultGenerator().opts(input).generate();
    }

    private static String model(Path output, String file) throws IOException {
        return Files.readString(output.resolve("src/models/" + file + ".rs"));
    }

    private static void assertContains(String text, String needle) {
        assertTrue(text.contains(needle), needle + " not in:\n" + text);
    }

    @Test
    void scalarsAndFormats() throws IOException {
        String thing = model(out, "thing");
        assertContains(thing, "pub name: String,");
        assertContains(thing, "pub email: Option<String>,");
        assertContains(thing, "pub id: uuid::Uuid,");
        assertContains(thing, "pub count: i64,");
        assertContains(thing, "pub small: Option<i32>,");
        assertContains(thing, "pub ratio: f64,");
        assertContains(thing, "pub weight: Option<f32>,");
        assertContains(thing, "pub active: bool,");
        assertContains(thing, "#[serde_as(as = \"Option<serde_with::base64::Base64>\")]");
        assertContains(thing, "pub blob: Option<Vec<u8>>,");
    }

    @Test
    void datesAreChronoUnlessUseChronoIsOff() throws IOException {
        assertContains(model(out, "thing"), "pub created_at: Option<chrono::DateTime<chrono::FixedOffset>>,");
        assertContains(model(out, "thing"), "pub born_on: Option<chrono::NaiveDate>,");
        assertContains(model(noChrono, "thing"), "pub created_at: Option<String>,");
        assertContains(model(noChrono, "thing"), "pub born_on: Option<String>,");
        assertContains(Files.readString(out.resolve("Cargo.toml")), "chrono = ");
        assertFalse(Files.readString(noChrono.resolve("Cargo.toml")).contains("chrono"));
    }

    @Test
    void validationKeywordsAreCheckedByValidateAndDocumented() throws IOException {
        String thing = model(out, "thing");
        assertContains(thing, "/// Validation: `minLength: 1`, `maxLength: 10`, `pattern: ^[a-z]+$`.\n");
        assertContains(thing, "pub name: String,");
        assertContains(thing, "impl crate::models::validation::Validate for Thing {");
        assertContains(thing, "crate::models::validation::length(&self.name, Some(1), Some(10), &path.field(\"name\"), errors);");
    }

    @Test
    void aUuidParameterIsAUuid() throws IOException {
        String api = Files.readString(out.resolve("src/apis/default_api.rs"));
        assertContains(api, "pub async fn get_thing(configuration: &configuration::Configuration, id: uuid::Uuid, options: Option<&timvw_rust_runtime::RequestOptions>)");
    }

    @Test
    void binaryIsAFileToSendOrAResponseToRead() throws IOException {
        String api = Files.readString(out.resolve("src/apis/default_api.rs"));
        assertContains(api, "pub async fn put_thing_file(configuration: &configuration::Configuration, id: &str, "
                + "#[builder(into)] body: timvw_rust_runtime::Upload, options: Option<&timvw_rust_runtime::RequestOptions>) -> Result<timvw_rust_runtime::Response, Error<PutThingFileError>>");
    }

    @Test
    void propertiesAreAStructWithAConstructorOfTheRequiredFields() throws IOException {
        String thing = model(out, "thing");
        assertContains(thing, "#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, bon::Builder)]\npub struct Thing {");
        assertContains(thing, "#[serde(rename = \"name\")]\n    pub name: String,");
        assertContains(thing, "#[serde(rename = \"tags\", skip_serializing_if = \"Option::is_none\")]");
        assertContains(thing,
                "pub fn new(name: String, id: uuid::Uuid, count: i64, ratio: f64, active: bool) -> Thing {");
        assertContains(thing, "pub owner: Option<models::Owner>,");
    }

    @Test
    void anOptionalNullablePropertyCanSendNull() throws IOException {
        String thing = model(out, "thing");
        assertContains(thing, "#[serde(rename = \"note\", default, with = \"::serde_with::rust::double_option\", "
                + "skip_serializing_if = \"Option::is_none\")]\n    pub note: Option<Option<String>>,");
    }

    @Test
    void mapsAndFreeFormObjects() throws IOException {
        String thing = model(out, "thing");
        assertContains(thing, "pub attributes: Option<std::collections::HashMap<String, i32>>,");
        assertContains(thing, "pub extra: Option<std::collections::HashMap<String, serde_json::Value>>,");
        assertContains(thing, "pub anything: Option<serde_json::Value>,");
    }

    @Test
    void readOnlyAndWriteOnlyAreFieldsInBothDirections() throws IOException {
        String thing = model(out, "thing");
        assertContains(thing, "#[serde(rename = \"server_id\", skip_serializing_if = \"Option::is_none\")]");
        assertContains(thing, "#[serde(rename = \"secret\", skip_serializing_if = \"Option::is_none\")]");
    }

    @Test
    void descriptionsAreDocCommentsWithBareUrlsWrapped() throws IOException {
        String thing = model(out, "thing");
        assertContains(thing, "/// Thing : A thing. See <https://example.com/things> for more.");
        assertContains(thing, "/// An alias.\n");
    }

    @Test
    void aScalarComponentHasNoTypeOfItsOwn() throws IOException {
        assertContains(model(out, "thing"), "pub alias: Option<String>,");
        assertFalse(Files.exists(out.resolve("src/models/alias.rs")));
    }

    @Test
    void arraysAreVecsAndUniqueItemsChangesNothing() throws IOException {
        String thing = model(out, "thing");
        assertContains(thing, "pub tags: Option<Vec<String>>,");
        assertContains(thing, "pub parts: Option<Vec<models::ThingPartsInner>>,");
        assertContains(model(out, "thing_parts_inner"), "pub struct ThingPartsInner {");
    }

    @Test
    void aStringEnumIsAUnitEnumWithDisplay() throws IOException {
        String color = model(out, "color");
        assertContains(color,
                "#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Hash, Serialize, Deserialize)]\npub enum Color {");
        assertContains(color, "#[serde(rename = \"red\")]\n    Red,");
        assertContains(color, "Self::Red => write!(f, \"red\"),");
        assertFalse(color.contains("impl Default"), color);
    }

    @Test
    void anInlineEnumIsAnEnumInItsStructsFile() throws IOException {
        String thing = model(out, "thing");
        assertContains(thing, "pub mode: Option<Mode>,");
        assertContains(thing, "pub enum Mode {");
        assertFalse(Files.exists(out.resolve("src/models/mode.rs")));
    }

    @Test
    void anIntegerEnumIsReprI64() throws IOException {
        String level = model(out, "level");
        assertContains(level, "#[repr(i64)]");
        assertContains(level, "Serialize_repr, Deserialize_repr)]\npub enum Level {");
        assertContains(level, "Variant1 = 1,");
    }

    @Test
    void allOfObjectsIsOneStructWithEveryPartsProperties() throws IOException {
        String pet = model(out, "pet");
        assertContains(pet, "pub struct Pet {");
        assertContains(pet, "pub login: Option<String>,");
        assertContains(pet, "pub kind: String,");
        assertContains(pet, "pub fn new(kind: String) -> Pet {");
    }
}
