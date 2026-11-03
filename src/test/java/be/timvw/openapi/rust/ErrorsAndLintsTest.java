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

/** Milestone 2.6: boxResponseError, apiLintAllows and userAgentFromPackage. */
class ErrorsAndLintsTest {

    @TempDir
    static Path output;

    @BeforeAll
    static void generate() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("packageName", "errors-lints");
        props.put(TimvwRustCodegen.BOX_RESPONSE_ERROR, "true");
        props.put(TimvwRustCodegen.API_LINT_ALLOWS, "clippy::needless_return, clippy::into_iter_on_ref");
        props.put(TimvwRustCodegen.USER_AGENT_FROM_PACKAGE, "true");
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/milestone1.yaml").toAbsolutePath().toString())
                .setOutputDir(output.toString())
                .setAdditionalProperties(props);
        ClientOptInput input = configurator.toClientOptInput();
        new DefaultGenerator().opts(input).generate();
    }

    private static String read(String relative) throws IOException {
        return Files.readString(output.resolve(relative));
    }

    @Test
    void responseErrorIsBoxed() throws IOException {
        String mod = read("src/apis/mod.rs");
        assertTrue(mod.contains("    ResponseError(Box<ResponseContent<T>>),\n"), mod);
        String api = read("src/apis/widgets_api.rs");
        assertTrue(api.contains("Err(Error::ResponseError(Box::new(ResponseContent { status, content, entity })))"), api);
        assertFalse(api.contains("Err(Error::ResponseError(ResponseContent {"), api);
    }

    @Test
    void apiModulesAndConfigurationAllowTheLints() throws IOException {
        String allow = "*/\n\n#![allow(clippy::needless_return, clippy::into_iter_on_ref)]\n";
        assertTrue(read("src/apis/widgets_api.rs").contains(allow), read("src/apis/widgets_api.rs"));
        assertTrue(read("src/apis/configuration.rs").contains(allow), read("src/apis/configuration.rs"));
        assertFalse(read("src/apis/mod.rs").contains("#![allow("));
    }

    @Test
    void userAgentIsThePackageAndCrateVersion() throws IOException {
        String cfg = read("src/apis/configuration.rs");
        assertTrue(cfg.contains("user_agent: Some(concat!(\"errors-lints/\", env!(\"CARGO_PKG_VERSION\")).to_owned()),"), cfg);
    }
}
