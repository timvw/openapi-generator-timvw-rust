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
                "\\x41\\x{1F600}\\u0041\\u{41}", "[\\d\\s.]", "\\bword\\b", "[a\\-z]", "[-a]", "[a-]", "()", "a|",
                "a{1000}", "(a{30}){30}", "\\w{100}", "\\d{1000}", "[a-z]{1000}", "[\\x41-\\x5A]", "[a-a]",
                "(?<a>x)(?<b>y)", "\\u{10FFFF}", "[\\u0000-\\uD7FF]")) {
            assertNull(RustRegex.unsupported(pattern), pattern);
        }
    }

    @Test
    void everythingElseIsSkipped() {
        for (String pattern : List.of("[^]", "[]", "^[]a]$", "a{,3}", "a{", "a{x}", "a{3,1}", "a{1,5000}", "\\Z",
                "\\e", "\\cJ", "\\k<a>", "(a)\\1", "\\0", "(?=a)", "(?!a)", "(?<=a)", "(?<!a)", "(?>a)", "(?i)a",
                "[[a]]", "[a&&b]", "[a--b]", "[a~~b]", "[\\w-.]", "[a-c-e]", "[a-\\d]", "\\<", "\\>", "a**", "*a",
                "a|*", "(*)", "^*", "a]", "a}", "(a", "a)", "\\", "\\p{Greek}", "\\xZ", "[a", "[\\b]",
                // no Unicode scalar value
                "\\uD83D", "[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]", "\\u{D800}", "\\x{110000}", "\\uDE00",
                // reversed ranges
                "[z-a]", "[a-\\x41]", "[\\x7A-a]",
                // a group name twice
                "(?<n>a)(?<n>b)", "(?<n>a)|(?<n>b)",
                // beyond the size limit
                "\\w{1000}", "\\w{101}", "\\p{L}{300}", "[\\w.]{200}", "(a{1000}){1000}", "(\\w{100}){100}",
                "(a{30}){40}", "((a{10}){10}){11}", "(\\w{10}){11}")) {
            assertNotNull(RustRegex.unsupported(pattern), pattern);
        }
    }
}
