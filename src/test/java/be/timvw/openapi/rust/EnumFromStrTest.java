package be.timvw.openapi.rust;

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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Milestone 3.2: enumFromStr gives closed enums FromStr and TryFrom, matching what their Display prints. */
class EnumFromStrTest {

    @TempDir
    static Path on;

    @TempDir
    static Path off;

    @BeforeAll
    static void generate() {
        generate(on, true);
        generate(off, false);
    }

    private static void generate(Path output, boolean enumFromStr) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "enums");
        if (enumFromStr) {
            props.put(TimvwRustCodegen.ENUM_FROM_STR, "true");
        }
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/enum_display.yaml").toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props)
                .toClientOptInput()).generate();
    }

    private static String read(Path output, String model) throws IOException {
        return Files.readString(output.resolve("src/models/" + model + ".rs"));
    }

    @Test
    void stringEnumAcceptsWhatItsDisplayPrints() throws IOException {
        String color = read(on, "color");
        assertTrue(color.contains("impl ::std::str::FromStr for Color {"), color);
        assertTrue(color.contains("\"blue-green\" => Ok(Self::BlueGreen),"), color);
        assertTrue(color.contains("\"256x256\" => Ok(Self::Variant256x256),"), color);
        assertTrue(color.contains("_ => Err(crate::models::ParseEnumError::new(\"Color\", value)),"), color);
        assertTrue(color.contains("impl ::std::convert::TryFrom<&str> for Color {"), color);
        assertTrue(color.contains("impl ::std::convert::TryFrom<&::std::string::String> for Color {"), color);
        assertTrue(color.contains("impl ::std::convert::TryFrom<::std::string::String> for Color {"), color);
        assertEquals(1, count(color, "impl std::fmt::Display for Color"), color);
        assertFalse(color.contains("impl ::std::fmt::Display"), color);
    }

    @Test
    void integerEnumAcceptsItsNumbers() throws IOException {
        String priority = read(on, "priority");
        assertTrue(priority.contains("\"30\" => Ok(Self::Variant30),"), priority);
        assertTrue(priority.contains("impl ::std::str::FromStr for Priority {"), priority);
    }

    @Test
    void propertyEnumsInAStructFileGetDisplayToo() throws IOException {
        String holder = read(on, "holder");
        assertTrue(holder.contains("impl ::std::str::FromStr for Quality {"), holder);
        assertTrue(holder.contains("\"low\" => Ok(Self::Low),"), holder);
        assertTrue(holder.contains("impl ::std::fmt::Display for Quality {"), holder);
        assertTrue(holder.contains("Self::Low => \"low\","), holder);
        // The integer property enum has its Display from the template.
        assertTrue(holder.contains("impl ::std::str::FromStr for Level {"), holder);
        assertEquals(1, count(holder, "Display for Level"), holder);
        assertFalse(holder.contains("FromStr for Holder"), holder);
    }

    @Test
    void unionsAndStructsGetNothing() throws IOException {
        assertFalse(read(on, "either").contains("FromStr for Either"));
        assertFalse(read(on, "filter").contains("FromStr"));
    }

    @Test
    void theErrorTypeIsGeneratedOnce() throws IOException {
        String mod = read(on, "mod");
        assertEquals(1, count(mod, "pub mod parse_enum_error;"), mod);
        assertEquals(1, count(mod, "pub use self::parse_enum_error::ParseEnumError;"), mod);
        assertTrue(read(on, "parse_enum_error").contains("pub struct ParseEnumError {"));
    }

    @Test
    void offChangesNothing() throws IOException {
        assertFalse(Files.exists(off.resolve("src/models/parse_enum_error.rs")));
        assertFalse(read(off, "mod").contains("parse_enum_error"));
        assertFalse(read(off, "color").contains("FromStr"));
        assertFalse(read(off, "holder").contains("Display for Quality"));
    }

    @Test
    void schemaTakingTheErrorNameFails(@TempDir Path root) throws IOException {
        Path models = Files.createDirectories(root.resolve("src/models"));
        Files.writeString(models.resolve("mod.rs"),
                "pub mod parse_enum_error;\npub use self::parse_enum_error::ParseEnumError;\n");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> EnumFromStr.run(models, m -> { }));
        assertTrue(e.getMessage().contains("ParseEnumError"), e.getMessage());
    }

    @Test
    void aSecondRunOverAFreshlyRenderedModIsAllowed(@TempDir Path root) throws IOException {
        Path models = Files.createDirectories(root.resolve("src/models"));
        Files.writeString(models.resolve("mod.rs"), "pub mod a;\n");
        Files.writeString(models.resolve("a.rs"), "pub enum A {\n    One,\n}\n");
        Files.writeString(models.resolve("parse_enum_error.rs"), "stale");
        assertEquals(new EnumFromStr.Result(1, 1), EnumFromStr.run(models, m -> { }));
        assertTrue(read(root, "parse_enum_error").contains("pub struct ParseEnumError {"));
    }

    @Test
    void noClosedEnumMeansNoErrorType(@TempDir Path root) throws IOException {
        Path models = Files.createDirectories(root.resolve("src/models"));
        Files.writeString(models.resolve("mod.rs"), "pub mod a;\n");
        Files.writeString(models.resolve("a.rs"), "pub struct A {\n}\n");
        assertEquals(new EnumFromStr.Result(0, 0), EnumFromStr.run(models, m -> { }));
        assertFalse(Files.exists(models.resolve("parse_enum_error.rs")));
        assertEquals("pub mod a;\n", Files.readString(models.resolve("mod.rs")));
    }

    /** The layout {@code FixUntaggedUnions} writes: rename_all, and arms through a local. */
    @Test
    void readsTheEnumsTheLegacyRewritesWrite() {
        String source = """
                /// Text - String enum type
                #[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
                #[serde(rename_all = "lowercase")]
                pub enum Text {
                    Inf,
                    Gpt4,
                }

                impl std::fmt::Display for Text {
                    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                        let value = match self {
                            Text::Inf => "inf",
                            Text::Gpt4 => "gpt-4",
                        };
                        write!(f, "{}", value)
                    }
                }
                """;
        List<EnumFromStr.ClosedEnum> found = EnumFromStr.closedEnums(source, m -> { });
        assertEquals(List.of(new EnumFromStr.ClosedEnum("Text",
                List.of(new EnumFromStr.Variant("Inf", "inf"), new EnumFromStr.Variant("Gpt4", "gpt-4")), true)), found);
    }

    @Test
    void takesWireNamesFromSerdeWithoutADisplay() {
        String source = """
                #[serde(rename_all = "lowercase")]
                pub enum Renamed {
                    /// a doc comment naming } a brace
                    Auto,
                    #[serde(rename = "a\\"b}")]
                    Quote,
                }
                #[repr(i64)]
                pub enum Numbered {
                    One = 1,
                    Minus = -1,
                }
                """;
        List<EnumFromStr.ClosedEnum> found = EnumFromStr.closedEnums(source, m -> { });
        assertEquals(List.of(new EnumFromStr.Variant("Auto", "auto"), new EnumFromStr.Variant("Quote", "a\\\"b}")),
                found.get(0).variants());
        assertEquals(List.of(new EnumFromStr.Variant("One", "1"), new EnumFromStr.Variant("Minus", "-1")),
                found.get(1).variants());
    }

    @Test
    void skipsUntaggedTaggedAndDataCarryingEnums() {
        String source = """
                #[derive(Clone)]
                #[serde(untagged)]
                pub enum Unit {
                    Null,
                }
                #[serde(tag = "type")]
                pub enum Tagged {
                    A,
                }
                pub enum Data {
                    A(String),
                    B,
                }
                pub enum Fields {
                    A { b: i32 },
                }
                pub enum Empty {}
                pub enum Value {
                    Yes,
                }
                """;
        List<EnumFromStr.ClosedEnum> found = EnumFromStr.closedEnums(source, m -> { });
        assertEquals(1, found.size(), found.toString());
        assertEquals("Value", found.get(0).name());
    }

    @Test
    void twoVariantsPrintingOneStringParseToTheFirstAndWarn() {
        String source = """
                pub enum Twice {
                    A,
                    B,
                }
                impl std::fmt::Display for Twice {
                    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
                        match self {
                            Self::A => write!(f, "same"),
                            Self::B => write!(f, "same"),
                        }
                    }
                }
                """;
        List<String> warnings = new ArrayList<>();
        String impl = EnumFromStr.impls(EnumFromStr.closedEnums(source, warnings::add).get(0));
        assertEquals(1, warnings.size(), warnings.toString());
        assertEquals(1, count(impl, "\"same\" =>"), impl);
        assertTrue(impl.contains("\"same\" => Ok(Self::A),"), impl);
    }

    /**
     * The emitted code compiles and behaves, against the names most likely to break it: variants called {@code Err}
     * and {@code Error} (which would make {@code Self::Err} ambiguous), and types shadowing {@code Result} and
     * {@code String}. The impls use only std, so rustc alone is enough. Skipped where rustc is not installed.
     */
    @Test
    void emittedCodeCompilesAndRoundTrips(@TempDir Path crate) throws Exception {
        Assumptions.assumeTrue(installed("rustc"), "rustc not installed");
        String clash = "#[derive(Clone, Copy, Debug, PartialEq)]\npub enum Clash {\n"
                + "    Err,\n    Error,\n    Ok,\n    None,\n    Some,\n    String,\n    Result,\n    FromStr,\n"
                + "    TryFrom,\n    Display,\n    Value,\n}\n";
        String shadow = "pub struct Result;\npub struct String;\n"
                + "#[derive(Clone, Copy, Debug, PartialEq)]\npub enum Kind {\n    Short,\n}\n";
        String number = "#[derive(Clone, Copy, Debug, PartialEq)]\npub enum Number {\n    One = 1,\n    Two = 2,\n}\n";
        Path models = Files.createDirectories(crate.resolve("models"));
        Files.writeString(models.resolve("mod.rs"), "pub mod clash;\npub mod shadow;\npub mod number;\n");
        Files.writeString(models.resolve("clash.rs"), clash);
        Files.writeString(models.resolve("shadow.rs"), shadow);
        Files.writeString(models.resolve("number.rs"), number);
        EnumFromStr.Result result = EnumFromStr.run(models, m -> { });
        assertEquals(3, result.enums());
        Files.writeString(crate.resolve("lib.rs"), """
                #![deny(warnings)]
                #![allow(dead_code)]
                pub mod models;
                use models::clash::Clash;
                use models::number::Number;
                use models::shadow::Kind;
                use models::ParseEnumError;
                use std::convert::TryFrom;

                #[test]
                fn round_trip() {
                    for v in [Clash::Err, Clash::Error, Clash::Ok, Clash::None, Clash::Some, Clash::String,
                              Clash::Result, Clash::FromStr, Clash::TryFrom, Clash::Display, Clash::Value] {
                        let text = v.to_string();
                        assert_eq!(text.parse::<Clash>(), Ok(v));
                        assert_eq!(Clash::try_from(text.as_str()), Ok(v));
                        assert_eq!(Clash::try_from(&text), Ok(v));
                        assert_eq!(Clash::try_from(text), Ok(v));
                    }
                    assert_eq!("Short".parse::<Kind>(), Ok(Kind::Short));
                    assert_eq!("2".parse::<Number>(), Ok(Number::Two));
                    assert_eq!(Number::One.to_string(), "1");
                }

                #[test]
                fn rejects_everything_else() {
                    for text in ["", "err", "ERR", " Err", "Err ", "Unknown"] {
                        let error: ParseEnumError = text.parse::<Clash>().unwrap_err();
                        assert_eq!(error.type_name(), "Clash");
                        assert_eq!(error.value(), text);
                    }
                    assert!("3".parse::<Number>().is_err());
                    assert!("01".parse::<Number>().is_err());
                    assert_eq!("nope".parse::<Number>().unwrap_err().to_string(),
                               "invalid value \\"nope\\" for enum Number");
                    let _: &dyn std::error::Error = &"nope".parse::<Number>().unwrap_err();
                }
                """);
        Path binary = crate.resolve("tests");
        run(crate, "rustc", "--edition", "2021", "--test", "lib.rs", "-o", binary.toString());
        run(crate, binary.toString());
    }

    private static boolean installed(String tool) throws InterruptedException {
        try {
            return new ProcessBuilder(tool, "--version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static void run(Path dir, String... command) throws Exception {
        Path log = Files.createTempFile("enum-from-str", ".log");
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        assertTrue(process.waitFor(5, TimeUnit.MINUTES), "timed out: " + command[0]);
        assertEquals(0, process.exitValue(), () -> {
            try {
                return Files.readString(log);
            } catch (IOException e) {
                return e.toString();
            }
        });
    }

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
