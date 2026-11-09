package be.timvw.openapi.rust;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone 3.5: what a response can hold has a catch-all. What the generated code does at run time is in
 * src/test/rust/open_enums.rs, which {@link #whatItDoesAtRunTime} runs with cargo.
 */
class OpenEnumsTest {

    private static final String SPEC = "src/test/resources/open_enums.yaml";

    @TempDir
    static Path on;

    private static JsonNode census;

    @BeforeAll
    static void generate() throws IOException {
        generate(on);
        census = new ObjectMapper().readTree(on.resolve("target/union_plan.json").toFile()).get("openEnums");
    }

    private static void generate(Path output) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "open_enums_fixture");
        props.put(TimvwRustCodegen.BON_BUILDERS, "false");
        props.put(TimvwRustCodegen.UNION_PLAN_FILE, "target/union_plan.json");
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of(SPEC).toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static String read(Path output, String model) throws IOException {
        return Files.readString(output.resolve("src/models/" + model + ".rs"));
    }

    private static List<String> names(String key) {
        List<String> names = new ArrayList<>();
        census.get(key).forEach(n -> names.add(n.asText()));
        return names;
    }

    private static boolean open(String model) throws IOException {
        return read(on, model).contains("other)]");
    }

    @Test
    void whatAResponseHoldsIsOpen() throws IOException {
        for (String model : List.of("status", "color", "role", "flavor", "rate", "hoisted_mode_enum", "error_code",
                "stream_phase", "page_kind", "webhook_state", "level", "opaque_kind", "tone")) {
            assertTrue(open(model), model);
        }
        assertTrue(read(on, "shape").contains("Unknown(serde_json::Value),"));
    }

    @Test
    void whatOnlyARequestHoldsStaysClosed() throws IOException {
        assertFalse(open("priority"));
        assertFalse(read(on, "order").contains("other)]"));
        assertFalse(read(on, "payment").contains("Unknown(serde_json::Value)"));
        assertTrue(read(on, "payment").contains("Tag::Value(other) => Err(dispatch::unknown_tag(UNION, TAG, other)),"));
        assertTrue(names("closedEnums").containsAll(List.of("Priority", "Order.urgency")), census.toString());
        assertEquals(List.of("Payment"), names("closedUnions"));
    }

    @Test
    void anInlineEnumFollowsItsStruct() throws IOException {
        String envelope = read(on, "envelope");
        assertTrue(envelope.contains("pub enum Phase {"), envelope);
        assertEquals(3, envelope.split("other\\)]", -1).length - 1, envelope);
        assertTrue(names("openEnums").containsAll(List.of("Envelope.phase", "Envelope.phases", "Envelope.weight")));
    }

    @Test
    void anInlineIntegerEnumOpensWhereItIsAnIntegerEnum() throws IOException {
        String envelope = read(on, "envelope");
        Assumptions.assumeTrue(envelope.contains("repr(i64)"), "openapi-generator 7.15 renders it as a string enum");
        assertTrue(envelope.contains("    #[serde(other)]\n    Unknown = i64::MAX,\n"), envelope);
    }

    @Test
    void theWalkGoesThroughAOneBranchUnion() throws IOException {
        assertTrue(read(on, "envelope").contains("pub opaque: Option<models::OpaqueBranch>"), read(on, "envelope"));
        assertTrue(open("opaque_kind"));
    }

    @Test
    void whatTellsVariantsApartIsGuarded() throws IOException {
        assertEquals(List.of("Author", "Cat.type", "Dog.type", "EitherEnumPrimaryEnum", "Grade", "ModelNameKnownEnum",
                        "UserTurn.role"),
                names("guardedEnums"));
        assertEquals(List.of("Animal"), names("guardedUnions"));
        assertFalse(open("model_name_known_enum"));
        assertFalse(open("either_enum_primary_enum"));
        assertTrue(open("either_enum_secondary_enum"));
        assertFalse(read(on, "animal").contains("Unknown(serde_json::Value)"));
    }

    @Test
    void anUntaggedUnionHasNoCatchAll() throws IOException {
        for (String union : List.of("turn", "model_name", "either_enum", "hoisted", "loose", "untagged")) {
            assertFalse(read(on, union).contains("Unknown(serde_json::Value)"), union);
        }
        assertTrue(names("reachableUntaggedUnions").containsAll(List.of("Turn", "Untagged")));
        assertFalse(census.get("untaggedUnions").isNull());
    }

    @Test
    void theCatchAllTakesANameAndAStringNoValueHas() throws IOException {
        String role = read(on, "role");
        assertTrue(role.contains("    #[serde(rename = \"unknown\")]\n    Unknown,\n"), role);
        assertTrue(role.contains("#[serde(rename = \"unknown_value\", other)]\n    UnknownValue,\n"), role);
        String odd = read(on, "odd");
        assertTrue(odd.contains("Unknown(models::UnknownThing)"), odd);
        assertTrue(odd.contains("UnknownValue(serde_json::Value)"), odd);
    }

    @Test
    void fromStrSkipsTheCatchAll() throws IOException {
        String status = read(on, "status");
        assertFalse(status.contains("\"unknown\" => Ok("), status);
        assertTrue(status.contains("Self::Unknown => write!(f, \"unknown\"),"), status);
        String phase = read(on, "envelope");
        assertTrue(phase.contains("Self::Unknown => \"unknown\","), phase);
        assertFalse(phase.contains("\"unknown\" => Ok("), phase);
        assertTrue(read(on, "flavor").contains("\"other\" => Ok(Self::Other),"));
        assertTrue(read(on, "role").contains("\"unknown\" => Ok(Self::Unknown),"));
        assertFalse(read(on, "role").contains("\"unknown_value\" => Ok("));
    }

    /** Runs src/test/rust/open_enums.rs on the generated models. Needs cargo; without it there is nothing to run. */
    @Test
    void whatItDoesAtRunTime(@TempDir Path crate) throws IOException, InterruptedException {
        Assumptions.assumeTrue(cargo(crate, 30, "--version") != null, "no cargo");
        Files.writeString(crate.resolve("Cargo.toml"), """
                [package]
                name = "open_enums_fixture"
                version = "0.0.0"
                edition = "2021"

                [dependencies]
                serde = { version = "1", features = ["derive"] }
                serde_json = "1"
                serde_repr = "0.1"
                serde_with = { version = "3", default-features = false, features = ["base64", "std", "macros"] }
                """);
        Files.createDirectories(crate.resolve("src/models"));
        Files.createDirectories(crate.resolve("tests"));
        Files.writeString(crate.resolve("src/lib.rs"), "#![allow(clippy::all)]\npub mod models;\npub mod validation;\n");
        Files.copy(on.resolve("src/validation.rs"), crate.resolve("src/validation.rs"));
        try (Stream<Path> files = Files.list(on.resolve("src/models"))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Files.copy(file, crate.resolve("src/models").resolve(file.getFileName()));
            }
        }
        Files.copy(Path.of("src/test/rust/open_enums.rs"), crate.resolve("tests/open_enums.rs"));
        String output = cargo(crate, 600, "test", "--test", "open_enums");
        assertNotNull(output, "cargo did not finish");
        assertTrue(output.contains("test result: ok.") && !output.contains(" 0 passed"), output);
    }

    /** What cargo printed, or {@code null} when it cannot run or does not finish in time. */
    private static String cargo(Path dir, long seconds, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("cargo"));
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile("cargo", ".log");
        Process process;
        try {
            process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
        } catch (IOException e) {
            return null;
        }
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return null;
        }
        String output = Files.readString(log);
        Files.delete(log);
        return output;
    }
}
