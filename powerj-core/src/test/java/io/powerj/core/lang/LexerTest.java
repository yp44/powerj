package io.powerj.core.lang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class LexerTest {

    private static Token.Word w(String text) {
        return new Token.Word(text);
    }

    private static Token.Str s(String text) {
        return new Token.Str(List.of(new StringPart.Text(text)));
    }

    @Test
    void wordsKeepBackslashesLiterally() {
        assertThat(Lexer.tokenize("cd C:\\Users\\yves")).containsExactly(w("cd"), w("C:\\Users\\yves"));
        assertThat(Lexer.tokenize("ls C:\\")).containsExactly(w("ls"), w("C:\\"));
    }

    @Test
    void stringsUseJavaEscapes() {
        assertThat(Lexer.tokenize("\"C:\\\\Program Files\"")).containsExactly(s("C:\\Program Files"));
        assertThat(Lexer.tokenize("\"a\\tb\\n\\\"c\\\" \\u00e9\"")).containsExactly(s("a\tb\n\"c\" é"));
        assertThat(Lexer.tokenize("\"\"")).containsExactly(s(""));
    }

    @Test
    void unknownEscapeIsAnError() {
        assertThatThrownBy(() -> Lexer.tokenize("\"C:\\dev\"")).hasMessageContaining("échappement inconnu \\d");
        assertThatThrownBy(() -> Lexer.tokenize("\"C:\\Users\"")).hasMessageContaining("échappement inconnu \\U");
    }

    @Test
    void unclosedStringIsAnError() {
        assertThatThrownBy(() -> Lexer.tokenize("echo \"abc")).hasMessageContaining("chaîne non fermée");
    }

    @Test
    void stringsInterpolateVariables() {
        assertThat(Lexer.tokenize("\"code $exit, durée $last.duration $ seul \\$x\"")).containsExactly(new Token.Str(List.of(
                new StringPart.Text("code "),
                new StringPart.Interpolation("exit", List.of()),
                new StringPart.Text(", durée "),
                new StringPart.Interpolation("last", List.of(new Accessor.Property("duration"))),
                new StringPart.Text(" $ seul $x"))));
    }

    @Test
    void variablesWithAccessors() {
        assertThat(Lexer.tokenize("$l[0] $last.exitCode $x[-1].name $? $_")).containsExactly(
                new Token.Var("l", List.of(new Accessor.Index(0))),
                new Token.Var("last", List.of(new Accessor.Property("exitCode"))),
                new Token.Var("x", List.of(new Accessor.Index(-1), new Accessor.Property("name"))),
                new Token.Var("?", List.of()),
                new Token.Var("_", List.of()));
    }

    @Test
    void separatorsAndRedirections() {
        assertThat(Lexer.tokenize("a;b && c||d > f >> g 2> h 2>> i")).containsExactly(
                w("a"), new Token.Separator(Connector.ALWAYS), w("b"),
                new Token.Separator(Connector.IF_SUCCESS), w("c"),
                new Token.Separator(Connector.IF_FAILURE), w("d"),
                new Token.Redirection(Token.Stream.OUT, false), w("f"),
                new Token.Redirection(Token.Stream.OUT, true), w("g"),
                new Token.Redirection(Token.Stream.ERR, false), w("h"),
                new Token.Redirection(Token.Stream.ERR, true), w("i"));
    }

    @Test
    void assignmentAndOptionsWithEquals() {
        assertThat(Lexer.tokenize("$x = ls --count=5")).containsExactly(
                new Token.Var("x", List.of()), new Token.Assign(), w("ls"), w("--count=5"));
    }

    @Test
    void dollarNotFollowedByANameIsAWord() {
        assertThat(Lexer.tokenize("echo $ 5$")).containsExactly(w("echo"), w("$"), w("5$"));
    }
}
