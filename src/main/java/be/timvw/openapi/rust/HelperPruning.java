package be.timvw.openapi.rust;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Drops the items of a crate-private helper module that nothing calls ({@code src/models/union_dispatch.rs},
 * {@code src/apis/multipart_helper.rs}), so a crate that needs only part of a helper does not carry the rest as
 * {@code dead_code}. The modules are {@code pub(crate)}, so this changes no public API.
 *
 * <p>The module is cut into its top-level items: a run of doc comments and attributes, then the item, up to the next
 * blank line at column 0. An item is kept when a caller names it after {@code ::}, {@code {} or {@code ,} (a path or a
 * {@code use} list), when a kept item names it, or, unless {@code pruneAllHelpers} is set, when it is marked
 * {@code #[allow(dead_code)]} (kept on purpose as part of the helper's API). Everything before the first item
 * ({@code //!} docs, {@code use} lines) stays, except the names of a {@code use} that no kept item names any more.
 * An item without a name stays too.
 */
final class HelperPruning {

    private static final Pattern NAME = Pattern.compile(
            "^(?:pub(?:\\([a-z]+\\))?\\s+)?(?:fn|type|enum|struct|const|static)\\s+(\\w+)", Pattern.MULTILINE);

    private HelperPruning() {
    }

    /** One top-level item: its text and name ({@code null} when it has none). */
    record Item(String text, String name) {
    }

    /**
     * Prunes {@code module} against the Rust files of {@code callerDirs} (the module itself excluded).
     *
     * @param keepAllowed keep items marked {@code #[allow(dead_code)]} even when nothing calls them
     * @return the names dropped
     */
    static List<String> prune(Path module, List<Path> callerDirs, boolean keepAllowed) throws IOException {
        if (!Files.exists(module)) {
            return List.of();
        }
        StringBuilder callers = new StringBuilder();
        for (Path dir : callerDirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".rs"))
                        .filter(f -> !f.equals(module)).sorted()::iterator) {
                    callers.append(Files.readString(file)).append('\n');
                }
            }
        }
        String source = Files.readString(module);
        List<String> dropped = new ArrayList<>();
        String pruned = prune(source, callers.toString(), dropped, keepAllowed);
        if (!dropped.isEmpty()) {
            Files.writeString(module, pruned);
        }
        return dropped;
    }

    /** The module's text without the items neither the callers nor a kept item name; dropped names go to {@code dropped}. */
    static String prune(String source, String callers, List<String> dropped) {
        return prune(source, callers, dropped, true);
    }

    static String prune(String source, String callers, List<String> dropped, boolean keepAllowed) {
        String head;
        List<Item> items = new ArrayList<>();
        String[] blocks = source.split("\n\n");
        StringBuilder prefix = new StringBuilder();
        int first = -1;
        for (int i = 0; i < blocks.length; i++) {
            if (name(blocks[i]) != null) {
                first = i;
                break;
            }
            prefix.append(blocks[i]).append("\n\n");
        }
        if (first < 0) {
            return source;
        }
        head = prefix.toString();
        for (int i = first; i < blocks.length; i++) {
            String block = blocks[i];
            // A blank line inside an item (between statements of a fn body) continues the item before it.
            if (!items.isEmpty() && startsIndented(block)) {
                Item last = items.remove(items.size() - 1);
                items.add(new Item(last.text() + "\n\n" + block, last.name()));
            } else {
                items.add(new Item(block, name(block)));
            }
        }
        Set<String> kept = new LinkedHashSet<>();
        for (Item item : items) {
            if (item.name() == null || (keepAllowed && item.text().contains("#[allow(dead_code)]")) || calledBy(callers, item.name())) {
                kept.add(item.name());
            }
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Item item : items) {
                if (item.name() == null || !kept.contains(item.name())) {
                    continue;
                }
                for (Item other : items) {
                    if (other.name() != null && !kept.contains(other.name()) && !other.name().equals(item.name())
                            && Pattern.compile("\\b" + Pattern.quote(other.name()) + "\\b").matcher(body(item)).find()) {
                        kept.add(other.name());
                        grew = true;
                    }
                }
            }
        }
        List<String> texts = new ArrayList<>();
        for (Item item : items) {
            if (item.name() == null || kept.contains(item.name())) {
                texts.add(item.text());
            } else {
                dropped.add(item.name());
            }
        }
        String rest = String.join("\n\n", texts);
        if (!dropped.isEmpty()) {
            head = unusedImports(head, rest);
        }
        StringBuilder out = new StringBuilder(head);
        out.append(rest);
        if (source.endsWith("\n") && !out.toString().endsWith("\n")) {
            out.append('\n');
        }
        return out.toString();
    }

    private static final Pattern SINGLE_USE = Pattern.compile("^use [\\w:]+::(\\w+);\\n", Pattern.MULTILINE);
    private static final Pattern LIST_USE = Pattern.compile("^use ([\\w:]+)::\\{([\\w,\\s]+)\\};\\n", Pattern.MULTILINE);

    /**
     * {@code head} without the {@code use} names that {@code rest} does not name: a {@code use} of one name goes, a
     * {@code use a::{B, C}} keeps only the names still used, and goes when none is.
     */
    private static String unusedImports(String head, String rest) {
        Matcher m = SINGLE_USE.matcher(head);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, used(m.group(1), rest) ? Matcher.quoteReplacement(m.group()) : "");
        }
        m.appendTail(out);
        m = LIST_USE.matcher(out.toString());
        StringBuilder lists = new StringBuilder();
        while (m.find()) {
            List<String> all = new ArrayList<>();
            List<String> kept = new ArrayList<>();
            for (String name : m.group(2).split(",")) {
                String n = name.trim();
                if (n.isEmpty()) {
                    continue;
                }
                all.add(n);
                if (n.equals("self") || used(n, rest)) {
                    kept.add(n);
                }
            }
            String line = kept.size() == all.size() ? m.group()
                    : kept.isEmpty() ? ""
                    : kept.size() == 1 ? "use " + m.group(1) + "::" + kept.get(0) + ";\n"
                    : "use " + m.group(1) + "::{" + String.join(", ", kept) + "};\n";
            m.appendReplacement(lists, Matcher.quoteReplacement(line));
        }
        m.appendTail(lists);
        return lists.toString();
    }

    private static boolean used(String name, String rest) {
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(rest).find();
    }

    private static boolean startsIndented(String block) {
        return !block.isEmpty() && (block.charAt(0) == ' ' || block.charAt(0) == '\t' || block.charAt(0) == '}'
                || block.charAt(0) == ')');
    }

    /** The item's text without its own name, so an item does not keep itself. */
    private static String body(Item item) {
        Matcher m = NAME.matcher(item.text());
        return m.find() ? item.text().substring(m.end()) : item.text();
    }

    private static String name(String block) {
        Matcher m = NAME.matcher(block);
        return m.find() ? m.group(1) : null;
    }

    private static boolean calledBy(String callers, String name) {
        return Pattern.compile("(?:::|[{,]\\s*)" + Pattern.quote(name) + "\\b").matcher(callers).find();
    }
}
