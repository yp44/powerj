package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.api.internal.LanguageSetting;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.error.ErrorLocation;
import io.powerj.core.error.PjException;
import io.powerj.core.lang.Parser;
import io.powerj.core.lang.SyntaxException;

/** What the user is told when a line is wrong: where, and in words that say what to do (audit v2, U-03, U-11, #175). */
class LanguageErrorsTest {

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private Interpreter interpreter;
    private Session session;

    @BeforeEach
    void setUp() {
        session = new Session(tmp, tmp, Map.of("PATH", ""));
        Builtin cancelled = (args, s) -> {
            throw new CancellationException();
        };
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false),
                Map.of("cancelme", cancelled), CmdletRegistry.of(List.of()));
    }

    @AfterEach
    void englishAgain() {
        LanguageSetting.set(Locale.ENGLISH);
    }

    private static SyntaxException syntaxError(String line) {
        try {
            Parser.parse(line);
        } catch (SyntaxException e) {
            return e;
        }
        throw new AssertionError("no syntax error for: " + line);
    }

    @Test
    void aSyntaxErrorAfterTheLexerSaysWhere() {
        assertThat(syntaxError("ls | | x").offset()).isEqualTo(5); // the second |
        assertThat(syntaxError("1 2").offset()).isEqualTo(2);
        assertThat(syntaxError("ls |").offset()).isEqualTo(4); // the end of the line
    }

    @Test
    void aSyntaxErrorOfTheLexerSaysWhereToo() {
        assertThat(syntaxError("echo )").offset()).isEqualTo(5);
        assertThat(syntaxError("\"abc").offset()).isZero();
    }

    @Test
    void thePositionOfAMessageCountsCharactersNotUtf16Units() {
        // the emoji is two units: ")" is at index 3 but is the third character
        SyntaxException error = syntaxError("😀 )");

        assertThat(error.offset()).isEqualTo(3);
        assertThat(error.getMessage()).contains("position 3");
    }

    @Test
    void theErrorOfABlockHasNoPlaceBecauseItIsRelativeToTheBlock() {
        assertThat(syntaxError("items | where { 1 2 }").offset()).isEqualTo(-1);
    }

    @Test
    void theCaretIsUnderTheRightColumnAfterWideCharacters() {
        assertThat(ErrorLocation.show("abc )", 4)).containsExactly("abc )", "    ^");
        assertThat(ErrorLocation.show("日本 )", 3)).containsExactly("日本 )", "     ^"); // 日本 takes 4 columns
        assertThat(ErrorLocation.show("ab", 99)).containsExactly("ab", "  ^");
    }

    @Test
    void theOffsetReachesTheErrorOfTheInterpreter() {
        assertThatThrownBy(() -> interpreter.execute("ls | | x")).isInstanceOfSatisfying(PjException.class,
                e -> assertThat(e.error().offset()).isEqualTo(5));
    }

    @Test
    void aLineThatDoesNotParseSetsDollarQuestionToFalse() throws Exception {
        interpreter.execute("1");
        assertThat(session.lastSucceeded()).isTrue();

        assertThatThrownBy(() -> interpreter.execute("1 +")).isInstanceOf(PjException.class);

        assertThat(session.lastSucceeded()).isFalse();
    }

    @Test
    void aCancellationInABuiltinIsACancellationNotAnErrorOfTheBuiltin() {
        assertThatThrownBy(() -> interpreter.execute("cancelme")).isInstanceOf(CancellationException.class);
    }

    @Test
    void aDivisionByZeroSpeaksTheLanguageOfTheUser() throws Exception {
        interpreter.execute("(1 / 0)");
        assertThat(errors).singleElement().asString().contains("division by zero").doesNotContain("/ by zero");

        LanguageSetting.set(Locale.FRENCH);
        errors.clear();
        interpreter.execute("(1 / 0)");
        assertThat(errors).singleElement().asString().contains("division par zéro").doesNotContain("/ by zero");
    }

    @Test
    void aLinkageErrorWithoutMessageNamesItsClass() {
        assertThat(Supervisor.messageOf(new ExceptionInInitializerError())).contains("ExceptionInInitializerError")
                .doesNotContain("null");
    }

    @Test
    void anOverflowSaysWhatToDoInsteadOfNothing() throws Exception {
        interpreter.execute("(9223372036854775807L + 1L)");

        assertThat(errors).singleElement().asString().contains("does not fit in a long").contains("BigInteger");
    }
}
