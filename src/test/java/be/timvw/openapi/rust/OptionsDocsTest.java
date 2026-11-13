package be.timvw.openapi.rust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openapitools.codegen.CliOption;
import org.junit.jupiter.api.Test;

/**
 * docs/options.md documents every option: each advertised option has a heading of its own ({@code ### `name`}), and
 * each name the generator rejects (stock options, the former baseline, removed options) is in a table row. A public
 * {@code String} constant of the generator classes is either one of those names or listed in {@link #NOT_OPTIONS}, so
 * a new option cannot be added without its documentation.
 */
class OptionsDocsTest {

    private static final Path DOC = Path.of("docs/options.md");

    /** Public constants that are not option names: values, the generator's name, keys the generator sets itself. */
    private static final Set<String> NOT_OPTIONS = new TreeSet<>(List.of(
            TimvwRustCodegen.GENERATOR_NAME,
            TimvwRustCodegen.DEFAULT_RUNTIME_VERSION,
            RustCodegenBase.REQWEST_LIBRARY,
            RustCodegenBase.TEMPLATES,
            RustCodegenBase.EXTERN_CRATE_NAME));

    static Set<String> advertised() {
        Set<String> names = new TreeSet<>();
        for (CliOption option : new TimvwRustCodegen().cliOptions()) {
            names.add(option.getOpt());
        }
        return names;
    }

    static Set<String> rejected() {
        Set<String> names = new TreeSet<>(TimvwRustCodegen.REJECTED_OPTIONS);
        names.addAll(TimvwRustCodegen.ALWAYS_ON);
        names.addAll(TimvwRustCodegen.FIXED_VALUES.keySet());
        names.add(TimvwRustCodegen.JSON_VALUE_SCHEMAS);
        names.add(TimvwRustCodegen.LEGACY_MODEL_REWRITES);
        names.add(TimvwRustCodegen.HTTP_STACK);
        return names;
    }

    @Test
    void everyOptionHasASection() throws IOException {
        Set<String> headings = new TreeSet<>();
        Matcher m = Pattern.compile("(?m)^### `([^`]+)`").matcher(Files.readString(DOC));
        while (m.find()) {
            headings.add(m.group(1));
        }
        assertTrue(advertised().size() > 30, advertised().toString());
        List<String> missing = new ArrayList<>();
        for (String option : advertised()) {
            if (!headings.contains(option)) {
                missing.add(option);
            }
        }
        assertEquals(List.of(), missing, "options without a '### `name`' section in " + DOC);
    }

    @Test
    void everyRejectedNameIsInTheTable() throws IOException {
        String doc = Files.readString(DOC);
        List<String> missing = new ArrayList<>();
        for (String name : rejected()) {
            if (!doc.contains("| `" + name + "` |")) {
                missing.add(name);
            }
        }
        assertEquals(List.of(), missing, "rejected names without a '| `name` |' row in " + DOC);
    }

    @Test
    void everyPublicConstantIsAnOptionOrKnownNotToBe() throws IllegalAccessException {
        Set<String> known = new TreeSet<>(advertised());
        known.addAll(rejected());
        known.addAll(NOT_OPTIONS);
        List<String> unknown = new ArrayList<>();
        for (Class<?> type : List.of(TimvwRustCodegen.class, RustCodegenBase.class)) {
            for (Field field : type.getDeclaredFields()) {
                int mod = field.getModifiers();
                if (Modifier.isPublic(mod) && Modifier.isStatic(mod) && Modifier.isFinal(mod)
                        && field.getType() == String.class) {
                    String value = (String) field.get(null);
                    if (!known.contains(value)) {
                        unknown.add(type.getSimpleName() + "." + field.getName() + " = " + value);
                    }
                }
            }
        }
        assertEquals(List.of(), unknown, "public constants that are neither documented options nor rejected names "
                + "(document the option in " + DOC + ", or add the constant to NOT_OPTIONS)");
    }
}
