package io.powerj.core.lang;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The reading of strings and groups that the parser, the highlighting and the completion share. */
class ScanningTest {

    @Test
    void aStringEndsAtItsClosingQuote() {
        assertThat(Scanning.stringEnd("say \"hi\" now", 4)).isEqualTo(8);
        assertThat(Scanning.stringEnd("\"\"", 0)).isEqualTo(2);
    }

    @Test
    void anEscapedQuoteDoesNotEndTheString() {
        assertThat(Scanning.stringEnd("\"a\\\"b\" c", 0)).isEqualTo(6);
        assertThat(Scanning.stringEnd("\"a\\\\\" c", 0)).isEqualTo(5); // an escaped backslash, then the quote
    }

    @Test
    void anInterpolationMayHoldStringsOfItsOwn() {
        String line = "\"a $(f(\"b\")) c\" d";

        assertThat(Scanning.stringEnd(line, 0)).isEqualTo(line.indexOf(" d"));
    }

    @Test
    void whatIsInsideAnInterpolationDoesNotEndTheString() {
        assertThat(Scanning.stringEnd("\"x $(f(\"}\")) y\"", 0)).isEqualTo(15);
        assertThat(Scanning.stringEnd("\"x $(f(\")\")) y\"", 0)).isEqualTo(15);
        assertThat(Scanning.stringEnd("\"x $(f('\"')) y\"", 0)).isEqualTo(15); // a character literal holding a quote
        assertThat(Scanning.stringEnd("\"x $(a $(b)) y\"", 0)).isEqualTo(15);
    }

    @Test
    void aDollarThatIsNotAnInterpolationIsText() {
        assertThat(Scanning.stringEnd("\"cost $5 and $\"", 0)).isEqualTo(15);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"open", "\"a $(f(\"b\")", "\"a $(f(\"b\")) c", "\"dangling \\"})
    void aStringThatIsNotClosedHasNoEnd(String line) {
        assertThat(Scanning.stringEnd(line, 0)).isEqualTo(-1);
    }

    @Test
    void aGroupEndsAtTheSymbolThatClosesIt() {
        assertThat(Scanning.matching("f(a, (b), [c])", 1)).isEqualTo(13);
        assertThat(Scanning.matching("{ { } }", 0)).isEqualTo(6);
        assertThat(Scanning.matching("[1, 2]", 0)).isEqualTo(5);
    }

    @Test
    void theContentOfStringsAndCharactersDoesNotCount() {
        assertThat(Scanning.matching("f(\")\")", 1)).isEqualTo(5);
        assertThat(Scanning.matching("f(')')", 1)).isEqualTo(5);
        assertThat(Scanning.matching("f(\"a $(g(\")\"))\")", 1)).isEqualTo(15);
    }

    @Test
    void aGroupThatIsNotClosedHasNoEnd() {
        assertThat(Scanning.matching("f(a, (b)", 1)).isEqualTo(-1);
        assertThat(Scanning.matching("f(\"open)", 1)).isEqualTo(-1);
        assertThat(Scanning.matching("{ ]", 0)).isEqualTo(-1);
    }

    // --- character literals, which the group scanner skips (found by mutation testing: nothing pinned their ends) ---

    @Test
    void aCharacterLiteralIsSkippedWhateverItHolds() {
        assertThat(Scanning.matching("f('a')", 1)).isEqualTo(5);
        assertThat(Scanning.matching("f('(')", 1)).isEqualTo(5);
        assertThat(Scanning.matching("f('\\n')", 1)).isEqualTo(6); // '\n': a backslash and a letter
        assertThat(Scanning.matching("f('\\'')", 1)).isEqualTo(6); // '\'' holds a quote, then ) closes
        assertThat(Scanning.matching("f('\\u0029')", 1)).isEqualTo(10); // '\u0029' is ')' written as an escape
        assertThat(Scanning.matching("f('é')", 1)).isEqualTo(5);
    }

    @Test
    void aQuoteThatOpensNoCharacterLiteralIsPlainText() {
        assertThat(Scanning.matching("(it's)", 0)).isEqualTo(5);
        assertThat(Scanning.matching("('')", 0)).isEqualTo(3);
        assertThat(Scanning.matching("(')", 0)).isEqualTo(2);
        assertThat(Scanning.matching("('ab')", 0)).isEqualTo(5);
    }

    @Test
    void aCharacterLiteralAtTheEndOfTheTextIsReadWithoutGoingBeyond() {
        assertThat(Scanning.matching("('a'", 0)).isEqualTo(-1);
        assertThat(Scanning.matching("('a", 0)).isEqualTo(-1);
        assertThat(Scanning.matching("('\\n'", 0)).isEqualTo(-1);
        assertThat(Scanning.matching("('\\n", 0)).isEqualTo(-1);
        assertThat(Scanning.matching("('\\u0041'", 0)).isEqualTo(-1);
        assertThat(Scanning.matching("('\\u004", 0)).isEqualTo(-1);
        assertThat(Scanning.matching("('", 0)).isEqualTo(-1);
    }

    @Test
    void theEndOfAStringIsFoundAtTheEdgesOfTheText() {
        assertThat(Scanning.stringEnd("\"$\"", 0)).isEqualTo(3); // a dollar before the closing quote
        assertThat(Scanning.stringEnd("\"$(", 0)).isEqualTo(-1);
        assertThat(Scanning.stringEnd("\"$", 0)).isEqualTo(-1);
        assertThat(Scanning.stringEnd("\"\\", 0)).isEqualTo(-1);
        assertThat(Scanning.stringEnd("\"a\"", 0)).isEqualTo(3);
        assertThat(Scanning.stringEnd("\"", 0)).isEqualTo(-1);
        assertThat(Scanning.stringEnd("x\"a\"", 1)).isEqualTo(4);
    }

    @Test
    void blanksAreSkippedInTheirVariants() {
        assertThat(Scanning.skipBlanks("  \t x", 0)).isEqualTo(4);
        assertThat(Scanning.skipBlanks("x", 0)).isEqualTo(0);
        assertThat(Scanning.skipBlanks("a  ", 1)).isEqualTo(3);
        assertThat(Scanning.skipBlanks("a b", 1)).isEqualTo(Lexer.isBlank(' ') ? 2 : 1);
    }
}
