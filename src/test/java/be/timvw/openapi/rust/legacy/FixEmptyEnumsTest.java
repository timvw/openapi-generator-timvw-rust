package be.timvw.openapi.rust.legacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FixEmptyEnumsTest {

    private static final String NEW_ENUM = "pub enum Item {\n    A(Box<models::A>),\n}";

    @Test
    void replacesAnEnumWithStructVariantsUpToItsClosingBrace() {
        // A discriminated anyOf renders its variants inline; the script's block ended at the first variant's brace.
        String content = "use crate::models;\n\n"
                + "#[derive(Clone, Debug)]\n#[serde(tag = \"type\")]\npub enum Item {\n"
                + "    #[serde(rename=\"a\")]\n    A {\n        id: String,\n    },\n"
                + "    #[serde(rename=\"b\")]\n    B {\n        id: String,\n    },\n"
                + "}\n\nimpl Default for Item {\n    fn default() -> Self {\n        todo!()\n    }\n}\n";
        assertEquals("use crate::models;\n\n" + NEW_ENUM
                        + "\n\nimpl Default for Item {\n    fn default() -> Self {\n        todo!()\n    }\n}\n",
                FixEmptyEnums.replaceEnumBlock("Item", content, NEW_ENUM));
    }

    @Test
    void replacesAnEnumWithTupleVariantsAsTheScriptDid() {
        String content = "#[derive(Clone)]\npub enum Item {\n    A(String),\n}\nrest\n";
        assertEquals(NEW_ENUM + "\nrest\n", FixEmptyEnums.replaceEnumBlock("Item", content, NEW_ENUM));
    }

    @Test
    void leavesTheFileAloneWithoutTheEnumOrItsClosingBrace() {
        assertNull(FixEmptyEnums.replaceEnumBlock("Item", "pub struct Item {}\n", NEW_ENUM));
        assertNull(FixEmptyEnums.replaceEnumBlock("Item", "pub enum Item {\n    A {\n", NEW_ENUM));
    }

    @Test
    void closingBraceSkipsNestedBlocks() {
        assertEquals(8, FixEmptyEnums.closingBrace("{a{b}{c}}d", 0));
        assertEquals(-1, FixEmptyEnums.closingBrace("{a{b}", 0));
    }
}
