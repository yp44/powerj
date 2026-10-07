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
}
