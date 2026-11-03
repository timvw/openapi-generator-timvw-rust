package be.timvw.openapi.rust.legacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** A rewrite that writes model payloads itself boxes them as the generator's BoxPolicy says (Milestone 3.1). */
class BoxPolicyTest {

    @TempDir
    Path root;

    private String rebuild(BoxPolicy policy) throws IOException {
        Path models = Files.createDirectories(root.resolve("src/models"));
        Files.writeString(models.resolve("pet.rs"), "#[derive(Clone)]\n#[serde(tag = \"type\")]\npub enum Pet {\n}\n");
        Files.writeString(models.resolve("cat.rs"), "pub struct Cat {}\n");
        Files.writeString(models.resolve("dog.rs"), "pub struct Dog {}\n");
        Map<String, Object> spec = Map.of("components", Map.of("schemas", Map.of(
                "Pet", Map.of(
                        "anyOf", List.of(Map.of("$ref", "#/components/schemas/Cat"), Map.of("$ref", "#/components/schemas/Dog")),
                        "discriminator", Map.of("propertyName", "type", "mapping", Map.of(
                                "cat", "#/components/schemas/Cat", "dog", "#/components/schemas/Dog"))),
                "Cat", Map.of("type", "object"),
                "Dog", Map.of("type", "object"))));
        new FixEmptyEnums().apply(new LegacyContext(root, spec, spec, policy));
        return Files.readString(models.resolve("pet.rs"));
    }

    @Test
    void rebuiltEnumPayloadsAreBoxedOnlyWhereThePolicySays() throws IOException {
        String pet = rebuild((holder, target) -> target.equals("Dog"));
        assertTrue(pet.contains("Cat(models::Cat),"), pet);
        assertTrue(pet.contains("Dog(Box<models::Dog>),"), pet);
    }

    @Test
    void withoutAPolicyEveryPayloadIsBoxedAsInTheScripts() throws IOException {
        String pet = rebuild(BoxPolicy.ALWAYS);
        assertTrue(pet.contains("Cat(Box<models::Cat>),"), pet);
        assertTrue(pet.contains("Dog(Box<models::Dog>),"), pet);
    }
}
