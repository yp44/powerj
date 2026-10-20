package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.jline.reader.EOFError;
import org.jline.reader.Parser.ParseContext;
import org.junit.jupiter.api.Test;

class ShellParserTest {

    private final ShellParser parser = new ShellParser();

    @Test
    void incompleteInputAsksForContinuationOnAccept() {
        assertThatThrownBy(() -> parser.parse("ls |", 4, ParseContext.ACCEPT_LINE))
                .isInstanceOf(EOFError.class);
    }

    @Test
    void incompleteInputIsStillSplitWhileEditing() {
        assertThat(parser.parse("ls |", 4, ParseContext.COMPLETE).words()).containsExactly("ls", "|");
    }

    @Test
    void splitsWordsAndLocatesTheCursor() {
        var parsed = ShellParser.split("ls  -r C:\\temp", 9);

        assertThat(parsed.words()).isEqualTo(List.of("ls", "-r", "C:\\temp"));
        assertThat(parsed.wordIndex()).isEqualTo(2);
        assertThat(parsed.word()).isEqualTo("C:\\temp");
        assertThat(parsed.wordCursor()).isEqualTo(2);
    }

    @Test
    void cursorOnBlankGivesAnEmptyWordAtThatPosition() {
        var parsed = ShellParser.split("ls  -r", 3);

        assertThat(parsed.words()).isEqualTo(List.of("ls", "", "-r"));
        assertThat(parsed.wordIndex()).isEqualTo(1);
        assertThat(parsed.word()).isEmpty();
    }

    @Test
    void backslashIsNotAnEscapeCharacter() {
        assertThat(parser.isEscapeChar('\\')).isFalse();
    }

    @Test
    void completionLineReplacesTheWordAndQuotesPathsWithSpaces() throws Exception {
        var session = new io.powerj.core.exec.Session(java.nio.file.Path.of("."), java.nio.file.Path.of("."),
                java.util.Map.of("PATH", ""));
        var interpreter = new io.powerj.core.exec.Interpreter(session, new io.powerj.core.exec.ShellIo(
                new java.io.PrintWriter(new java.io.StringWriter()), _ -> { }, false), java.util.Map.of(),
                io.powerj.core.exec.CmdletRegistry.discover());
        var completing = new ShellParser(new io.powerj.core.exec.Completions(interpreter));

        var parsed = (ShellParser.CompletionLine) completing.parse("ls -r wh", 8, ParseContext.COMPLETE);
        assertThat(parsed.word()).isEqualTo("wh");
        assertThat(parsed.wordCursor()).isEqualTo(2);
        assertThat(parsed.wordIndex()).isZero();
        assertThat(parsed.words()).containsExactly("wh");
        assertThat(parsed.rawWordCursor()).isEqualTo(2);
        assertThat(parsed.rawWordLength()).isEqualTo(2);
        assertThat(parsed.escape("docs", true)).isEqualTo("docs");
        assertThat(parsed.escape("Program Files", true).toString()).isEqualTo("\"Program Files\"");
        assertThat(parsed.escape("a b\\c\"d$e", false).toString()).isEqualTo("\"a b\\\\c\\\"d\\$e");

        var quoted = (ShellParser.CompletionLine) completing.parse("ls \"do", 6, ParseContext.COMPLETE);
        assertThat(quoted.escape("docs", true).toString()).isEqualTo("\"docs\"");
    }

    @Test
    void plainWordsAreNotEscaped() {
        var parsed = ShellParser.split("ls doc", 6);
        assertThat(parsed.escape("docs", true)).isEqualTo("docs");
        assertThat(parsed.rawWordCursor()).isEqualTo(3);
        assertThat(parsed.rawWordLength()).isEqualTo(3);
    }
}
