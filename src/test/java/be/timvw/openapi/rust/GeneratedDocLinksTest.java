package be.timvw.openapi.rust;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every relative {@code .md} link of a generated crate's Markdown ({@code README.md}, {@code docs/*.md}) points at a
 * file that was generated. A type links to the doc of the model it names; a container, primitive or library type
 * ({@code Vec}, {@code String}, {@code std::path::PathBuf}, {@code serde_json::Value}) is not linked, where stock
 * linked {@code Vec.md}, {@code String.md} and the like.
 */
class GeneratedDocLinksTest {

    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s#]+\\.md)(#[^)]*)?\\)");

    @ParameterizedTest
    @ValueSource(strings = {"doc_links.yaml", "null_shapes.yaml", "nullable_items_31.yaml", "schema_mapping.yaml",
            "request_bodies.yaml", "union_params.yaml", "multipart.yaml", "nested_enum_containers.yaml",
            "operation_mapping.yaml"})
    void everyRelativeLinkResolves(String spec, @TempDir Path out) throws IOException {
        generate(spec, out);
        assertNoBrokenLinks(out);
    }

    /** With model name mappings, the doc files and the links take the mapped names. */
    @org.junit.jupiter.api.Test
    void linksFollowModelNameMappings(@TempDir Path out) throws IOException {
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/doc_links.yaml").toAbsolutePath().toString())
                .setOutputDir(out.toString())
                .setAdditionalProperties(Map.of("packageName", "doc_links_fixture", "webhookModule", "Webhooks"))
                .addModelNameMapping("Kind", "PetKind")
                .addModelNameMapping("Shape", "Form")
                .toClientOptInput()).generate();
        assertNoBrokenLinks(out);
        String pet = Files.readString(out.resolve("docs/Pet.md"));
        assertContains(pet, "**kind** | Option<[**models::PetKind**](PetKind.md)>");
        assertContains(pet, "**shape** | Option<[**models::Form**](Form.md)>");
        assertContains(Files.readString(out.resolve("docs/DefaultApi.md")), "**kind** | Option<[**PetKind**](PetKind.md)>");
    }

    private static void assertNoBrokenLinks(Path out) throws IOException {
        List<String> broken = new ArrayList<>();
        int links = 0;
        try (Stream<Path> files = Files.walk(out)) {
            for (Path md : files.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                Matcher m = LINK.matcher(Files.readString(md));
                while (m.find()) {
                    String target = m.group(1);
                    if (target.contains("://")) {
                        continue;
                    }
                    links++;
                    // compared by name, so that a case-insensitive file system does not hide a wrong case
                    Path file = md.getParent().resolve(target).normalize();
                    if (!Files.exists(file) || !listed(file)) {
                        broken.add(out.relativize(md) + " -> " + target);
                    }
                }
            }
        }
        assertTrue(links > 0, "no links in " + out);
        assertEquals(List.of(), broken);
    }

    @org.junit.jupiter.api.Test
    void aTypeLinksToTheOneModelItNames(@TempDir Path out) throws IOException {
        generate("doc_links.yaml", out);
        String pet = Files.readString(out.resolve("docs/Pet.md"));
        assertContains(pet, "**kind** | Option<[**models::Kind**](Kind.md)>");
        assertContains(pet, "**kinds** | Option<[**HashSet<models::Kind>**](Kind.md)>");
        assertContains(pet, "**by_kind** | Option<[**std::collections::HashMap<String, Vec<models::Kind>>**](Kind.md)>");
        assertContains(pet, "**children** | Option<[**Vec<models::Pet>**](Pet.md)>");
        assertContains(pet, "**tags** | Option<**Vec<String>**>");
        assertContains(pet, "**output** | Option<**serde_json::Value**>");
        assertContains(pet, "**error** | Option<**serde_json::Value**>");
        assertContains(pet, "**id** | Option<**uuid::Uuid**>");
        String api = Files.readString(out.resolve("docs/DefaultApi.md"));
        assertContains(api, "**ids** | Option<**Vec<String>**>");
        assertContains(api, "**kinds** | Option<[**Vec<models::Kind>**](Kind.md)>");
        assertContains(api, "[**Vec<models::Pet>**](Pet.md)");
        assertContains(api, "**std::path::PathBuf**");
        assertContains(api, "**serde_json::Value**");
        assertContains(api, "[**models::CreatePet200Response**](CreatePet200Response.md)");
        assertContains(api, "**pet** | Option<[**Pet**](Pet.md)>");
        // a webhook's too
        assertContains(Files.readString(out.resolve("docs/WebhooksApi.md")), "**pet** | Option<[**Pet**](Pet.md)>");
    }

    /** Generates {@code spec} with its webhooks in a module of their own, so they do not replace default_api. */
    private static void generate(String spec, Path out) {
        new DefaultGenerator().opts(new CodegenConfigurator()
                .setGeneratorName(TimvwRustCodegen.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources", spec).toAbsolutePath().toString())
                .setOutputDir(out.toString())
                .setAdditionalProperties(Map.of("packageName", "doc_links_fixture", "webhookModule", "Webhooks"))
                .toClientOptInput()).generate();
    }

    private static boolean listed(Path file) throws IOException {
        try (Stream<Path> siblings = Files.list(file.getParent())) {
            return siblings.anyMatch(p -> p.getFileName().toString().equals(file.getFileName().toString()));
        }
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue(haystack.contains(needle), () -> "expected\n" + needle + "\nin\n" + haystack);
    }
}
