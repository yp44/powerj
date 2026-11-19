package io.powerj.core.lang;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Whatever is typed, the parser either returns a script or throws a {@link SyntaxException}: any other exception would
 * reach the user as an "internal error" (audit v2, R-03, #167). A fixed seed keeps the run the same every time.
 */
class ParserFuzzTest {

    /** The pieces of the language, and the ones that tend to break a reader: signs, units, quotes, escapes, indexes. */
    private static final List<String> PIECES = List.of("a", "ls", "grep", "x", "1", "0", "9", "99999999999", "1.5", "0x", "1e9",
            "d", "ms", "kb", "tb", "L", "u", "\\u", "\\u+0", "\\", "\"", "'", "\"\"\"", "$", "$x", "$_", "$(", "(", ")", "{", "}",
            "[", "]", "[-1]", "[99999999999]", "|", "||", "&&", "&", ";", "<", ">", ">>", "2>", "=", "==", "!=", "!", "-", "--",
            "+", "*", "/", "%", "?", ":", ".", ",", "...", "..", "*.", "^", "~", "#", "`", "@", "\uD83D\uDE00", "\uD83D", "é", " ",
            " ", "\t", "\n", "true", "null", "new", "->", "=>", "$x.y", "$x[0]", "'a'", "'\\n'", "\"a $x b\"", "\"$(1)\"");

    private static String random(Random random, int pieces) {
        var line = new StringBuilder();
        for (int i = random.nextInt(pieces) + 1; i > 0; i--) {
            line.append(PIECES.get(random.nextInt(PIECES.size())));
        }
        return line.toString();
    }

    @Test
    void onlyASyntaxExceptionCanComeOutOfTheParser() {
        var random = new Random(Long.getLong("fuzz.seed", 167));
        for (int i = 0; i < Integer.getInteger("fuzz.runs", 60_000); i++) {
            String line = random(random, 12);
            boolean known = i % 2 == 0; // half of the words are commands the parser knows, half are not
            try {
                assertThat(Parser.parse(line, _ -> known)).isNotNull();
            } catch (SyntaxException expected) {
                assertThat(expected.getMessage()).as(line).isNotBlank();
            } catch (RuntimeException | StackOverflowError e) {
                throw new AssertionError("the parser threw " + e + " for: " + line.replace("\n", "\\n"), e);
            }
        }
    }

    /** What the fuzzing found: {@code [a.] x} took the last part of the "type" {@code a.}, which is empty. */
    @Test
    void aTypeWithAnEmptyLastPartIsNotACast() {
        for (String line : List.of("[null.] 1", "[a.] x", "[.] 1")) {
            try {
                Parser.parse(line, _ -> false);
            } catch (SyntaxException expected) {
                assertThat(expected.getMessage()).as(line).isNotBlank();
            }
        }
    }
}
