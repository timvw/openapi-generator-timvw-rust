package be.timvw.openapi.rust.legacy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Port of {@code scripts/fix_default_issues.py}: removes {@code Default} from the derives of a seed list of structs
 * and of every model holding a {@code Box<models::RunObject>} or a {@code ChatCompletionRequest*MessageContent}.
 */
public class FixDefaultIssues implements Rewrite {

    private static final int PY = Pattern.UNIX_LINES | Pattern.UNICODE_CHARACTER_CLASS;

    /** {@code REMOVE_DEFAULT_FROM}: the seed list. */
    static final List<String> REMOVE_DEFAULT_FROM = List.of(
            "FineTuneReinforcementMethod",
            "GraderMulti",
            "RealtimeBetaServerEventConversationItemInputAudioTranscriptionCompleted",
            "RealtimeServerEventConversationItemInputAudioTranscriptionCompleted",
            "RunObject",
            "ValidateGraderRequest",
            "RunStreamEventAnyOf",
            "RunStreamEventAnyOf1",
            "RunStreamEventAnyOf2",
            "RunStreamEventAnyOf3",
            "RunStreamEventAnyOf4",
            "RunStreamEventAnyOf5",
            "RunStreamEventAnyOf6",
            "RunStreamEventAnyOf7",
            "RunStreamEventAnyOf8",
            "RunStreamEventAnyOf9");

    private static final Pattern UPPER_BOUNDARY = Pattern.compile("(?<!^)(?=[A-Z])", PY);
    private static final Pattern DERIVE = Pattern.compile("(#\\[derive\\([^)]*)\\bDefault,?\\s*([^)]*\\)\\])", PY);
    private static final Pattern DOUBLE_COMMA = Pattern.compile(",\\s*,", PY);
    private static final Pattern LEADING_COMMA = Pattern.compile("^,\\s*", PY);
    private static final Pattern ONLY_DEFAULT = Pattern.compile("#\\[derive\\(Default\\)\\]", PY);
    private static final Pattern BOXED_CONTENT = Pattern.compile("Box<models::ChatCompletionRequest\\w*MessageContent>", PY);

    public FixDefaultIssues() {
    }

    /** {@code main}: the seed list first, then the auto-detected files. */
    @Override
    public void apply(LegacyContext context) throws IOException {
        Path modelsDir = context.models();
        for (String structName : REMOVE_DEFAULT_FROM) {
            String fileName = UPPER_BOUNDARY.matcher(structName).replaceAll("_").toLowerCase(Locale.ROOT) + ".rs";
            Path filePath = modelsDir.resolve(fileName);
            if (Files.exists(filePath)) {
                removeDefaultDerive(filePath);
            }
        }
        try (Stream<Path> files = Files.list(modelsDir)) {
            for (Path filePath : (Iterable<Path>) files.sorted()::iterator) {
                if (!filePath.getFileName().toString().endsWith(".rs")) {
                    continue;
                }
                String content = Files.readString(filePath);
                if (content.contains("derive") && content.contains("Default")) {
                    if (content.contains("Box<models::RunObject>") || BOXED_CONTENT.matcher(content).find()) {
                        removeDefaultDerive(filePath);
                    }
                }
            }
        }
    }

    /** {@code remove_default_derive}: drops {@code Default} from each derive list of the file. */
    static boolean removeDefaultDerive(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        String original = content;
        content = DERIVE.matcher(content).replaceAll(match -> {
            String before = DOUBLE_COMMA.matcher(match.group(1)).replaceAll(",");
            String after = LEADING_COMMA.matcher(match.group(2)).replaceFirst("");
            return Matcher.quoteReplacement(before + after);
        });
        content = ONLY_DEFAULT.matcher(content).replaceAll(Matcher.quoteReplacement("#[derive()]"));
        if (!content.equals(original)) {
            Files.writeString(filePath, content);
            return true;
        }
        return false;
    }
}
