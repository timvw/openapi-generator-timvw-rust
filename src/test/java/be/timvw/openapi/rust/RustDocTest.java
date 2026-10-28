package be.timvw.openapi.rust;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RustDocTest {

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            "See https://example.com/docs.                 | See <https://example.com/docs>.",
            "https://example.com/a/b at the start          | <https://example.com/a/b> at the start",
            "two: http://a.io/x and https://b.io/y;        | two: <http://a.io/x> and <https://b.io/y>;",
            "already <https://example.com/docs> wrapped    | already <https://example.com/docs> wrapped",
            "[link](https://example.com/docs) in markdown  | [link](https://example.com/docs) in markdown",
            "`https://example.com/docs` in code            | `https://example.com/docs` in code",
            "\"https://example.com/docs\" quoted           | \"https://example.com/docs\" quoted",
            "(see https://example.com/docs)                | (see <https://example.com/docs>)",
            "[https://example.com/docs]                    | [https://example.com/docs]",
            "no url here                                   | no url here",
    })
    void wrapsBareUrlsOnly(String input, String expected) {
        assertEquals(expected.trim(), RustDoc.wrapBareUrls(input.trim()));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            "trailing comma https://example.com/x, next     | trailing comma <https://example.com/x>, next",
            "colon https://example.com/x: next              | colon <https://example.com/x>: next",
            "paren https://example.com/x) next              | paren <https://example.com/x>) next",
    })
    void keepsTrailingPunctuationOutside(String input, String expected) {
        assertEquals(expected.trim(), RustDoc.wrapBareUrls(input.trim()));
    }

    @org.junit.jupiter.api.Test
    void passesNullThrough() {
        assertNull(RustDoc.wrapBareUrls(null));
    }
}
