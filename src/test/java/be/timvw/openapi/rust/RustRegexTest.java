package be.timvw.openapi.rust;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which schema patterns the generated {@code validate()} checks with the regex crate (see {@link RustRegex}). */
class RustRegexTest {

    @Test
    void patternsBothEnginesReadAlikeAreChecked() {
        for (String pattern : List.of("^[a-z]+$", "^\\+[1-9][0-9]{1,14}$", "^[A-Za-z0-9._~:-]+$", "[^A-Za-z0-9._~:-]",
                "^(data:|https?://)", "^(?:error|info|[a-z][a-z0-9_-]*(?:\\.[a-z0-9_-]+)+)$", "^[0-9A-D*#]$",
                "^live_[A-Za-z0-9_-]{1,128}$", "^\\p{L}+$", "^\\pN$", "^\\/a$", "^(?<w>a)$", "^a+?b{2,}?c{3}$",
                "\\x41\\x{1F600}\\u0041\\u{41}", "[\\d\\s.]", "\\bword\\b", "[a\\-z]", "[-a]", "[a-]", "()", "a|")) {
            assertNull(RustRegex.unsupported(pattern), pattern);
        }
    }

    @Test
    void everythingElseIsSkipped() {
        for (String pattern : List.of("[^]", "[]", "^[]a]$", "a{,3}", "a{", "a{x}", "a{3,1}", "a{1,5000}", "\\Z",
                "\\e", "\\cJ", "\\k<a>", "(a)\\1", "\\0", "(?=a)", "(?!a)", "(?<=a)", "(?<!a)", "(?>a)", "(?i)a",
                "[[a]]", "[a&&b]", "[a--b]", "[a~~b]", "[\\w-.]", "[a-c-e]", "[a-\\d]", "\\<", "\\>", "a**", "*a",
                "a|*", "(*)", "^*", "a]", "a}", "(a", "a)", "\\", "\\p{Greek}", "\\xZ", "[a", "[\\b]")) {
            assertNotNull(RustRegex.unsupported(pattern), pattern);
        }
    }
}
