package io.powerj.core.lang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.powerj.core.lang.Ast.Expression;

class LexerTest {

    private static Token.Word w(String text) {
        return new Token.Word(text);
    }

    private static Token.Expr s(String text) {
        return new Token.Expr(new Ast.StringExpression(List.of(new StringPart.Text(text))));
    }

    private static Token.Expr e(Expression expression) {
        return new Token.Expr(expression);
    }

    private static Ast.VariableExpression v(String name) {
        return new Ast.VariableExpression(name, List.of());
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
        assertThatThrownBy(() -> Lexer.tokenize("\"C:\\dev\"")).hasMessageContaining("unknown escape \\d");
        assertThatThrownBy(() -> Lexer.tokenize("\"C:\\Users\"")).hasMessageContaining("unknown escape \\U");
    }

    @Test
    void unclosedStringIsAnError() {
        assertThatThrownBy(() -> Lexer.tokenize("echo \"abc")).hasMessageContaining("unclosed string");
    }

    @Test
    void stringsInterpolateVariablesAndGroups() {
        assertThat(Lexer.tokenize("\"code $exit, duration $last.duration $ alone \\$x\"")).containsExactly(e(
                new Ast.StringExpression(List.of(
                        new StringPart.Text("code "),
                        new StringPart.Interpolation("exit", List.of()),
                        new StringPart.Text(", duration "),
                        new StringPart.Interpolation("last", List.of(new Accessor.Property("duration"))),
                        new StringPart.Text(" $ alone $x")))));
        var parts = ((Ast.StringExpression) ((Token.Expr) Lexer.tokenize("\"n=$(1 + 2) $(ls).\"").getFirst())
                .expression()).parts();
        assertThat(parts).hasSize(5);
        assertThat(parts.get(1)).isEqualTo(new StringPart.Embedded(new Ast.Binary(Ast.Operator.ADD,
                new Ast.Literal(1), new Ast.Literal(2))));
        assertThat(((StringPart.Embedded) parts.get(3)).expression()).isInstanceOf(Ast.SubExpression.class);
    }

    @Test
    void variablesWithAccessorsAndCalls() {
        assertThat(Lexer.tokenize("echo $l[0] $last.exitCode $x.size() $? $_")).containsExactly(
                w("echo"),
                e(new Ast.At(v("l"), new Ast.Literal(0))),
                e(new Ast.Get(v("last"), "exitCode")),
                e(new Ast.Invoke(v("x"), "size", List.of())),
                e(v("?")),
                e(v("_")));
    }

    @Test
    void argumentExpressionsStopAtWhitespace() {
        assertThat(Lexer.tokenize("echo $a + 1")).containsExactly(w("echo"), e(v("a")), w("+"), w("1"));
    }

    @Test
    void statementExpressionsAllowOperatorsButNotCommandSyntax() {
        assertThat(Lexer.tokenize("$a + 1 > out.txt")).containsExactly(
                e(new Ast.Binary(Ast.Operator.ADD, v("a"), new Ast.Literal(1))),
                new Token.Redirection(Token.Stream.OUT, false), w("out.txt"));
        assertThat(Lexer.tokenize("$a && $b")).containsExactly(e(v("a")), new Token.Separator(Connector.IF_SUCCESS),
                e(v("b")));
        assertThat(Lexer.tokenize("($a > 1 && $b)")).hasSize(1);
    }

    @Test
    void javaNamesAtCommandPosition() {
        assertThat(Lexer.tokenize("Math.max(3, 7)")).singleElement().isInstanceOf(Token.Expr.class);
        assertThat(Lexer.tokenize("new java.io.File(\"x\")")).singleElement().isInstanceOf(Token.Expr.class);
        assertThat(Lexer.tokenize("[long] 5")).singleElement().isEqualTo(e(new Ast.Cast("long", new Ast.Literal(5))));
        // Without attached parentheses, a qualified name is an expression only if it denotes a class or a field.
        assertThat(Lexer.tokenize("notepad.exe fichier.txt")).containsExactly(w("notepad.exe"), w("fichier.txt"));
        assertThat(Lexer.tokenize("java -version")).containsExactly(w("java"), w("-version"));
        assertThat(Lexer.tokenize("Math.PI", Set.of("Math.PI")::contains)).singleElement()
                .isEqualTo(e(new Ast.Get(new Ast.Name("Math"), "PI")));
        assertThat(Lexer.tokenize("Math.PI")).containsExactly(w("Math.PI"));
        // As an argument: only attached calls.
        assertThat(Lexer.tokenize("cat Path.of(\"a\") a.b")).containsExactly(w("cat"),
                e(new Ast.Invoke(new Ast.Name("Path"), "of", List.of(new Ast.StringExpression(
                        List.of(new StringPart.Text("a")))))), w("a.b"));
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
                new Token.AssignTo("x"), w("ls"), w("--count=5"));
        assertThat(Lexer.tokenize("$x == 1")).singleElement().isInstanceOf(Token.Expr.class);
    }

    @Test
    void dollarNotFollowedByANameIsAWord() {
        assertThat(Lexer.tokenize("echo $ 5$")).containsExactly(w("echo"), w("$"), w("5$"));
    }
}
