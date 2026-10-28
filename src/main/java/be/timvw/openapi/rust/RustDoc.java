package be.timvw.openapi.rust;

import java.util.regex.Pattern;

/**
 * Doc-comment rendering rules.
 *
 * <p>Descriptions are printed into {@code ///} comments. rustdoc, run with {@code -D warnings}, rejects bare
 * URLs in doc comments ({@code rustdoc::bare_urls}), so every URL that is not already inside {@code <...>},
 * a markdown link, inline code or a quoted string is wrapped in angle brackets.</p>
 */
final class RustDoc {

    /** A URL not preceded by a delimiter that already encloses it; trailing punctuation stays outside. */
    private static final Pattern BARE_URL =
            Pattern.compile("(?<![<(\\[`\"/\\w])(https?://[^\\s<>)\\]`\"]*[^\\s<>)\\]`\".,;:])");

    private RustDoc() {
    }

    static String wrapBareUrls(String text) {
        if (text == null || !text.contains("http")) {
            return text;
        }
        return BARE_URL.matcher(text).replaceAll("<$1>");
    }
}
