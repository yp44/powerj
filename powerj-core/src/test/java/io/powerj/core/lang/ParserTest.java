package io.powerj.core.lang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.powerj.core.lang.Ast.Command;
import io.powerj.core.lang.Ast.ExpressionBody;
import io.powerj.core.lang.Ast.Statement;
import io.powerj.core.lang.Ast.WordArgument;

class ParserTest {

    private static Statement only(String line) {
        var steps = Parser.parse(line).steps();
        assertThat(steps).hasSize(1);
        return steps.getFirst().statement();
    }

    @Test
    void commandWithArguments() {
        assertThat(only("git log -n 5").body()).isEqualTo(new Command("git", false,
                List.of(new WordArgument("log"), new WordArgument("-n"), new WordArgument("5"))));
    }

    @Test
    void caretForcesNative() {
        assertThat(only("^find x").body()).isEqualTo(new Command("find", true, List.of(new WordArgument("x"))));
        assertThatThrownBy(() -> Parser.parse("^")).hasMessageContaining("command name expected");
    }

    @Test
    void expressionsAlone() {
        assertThat(only("$exit").body()).isEqualTo(new ExpressionBody(new Ast.VariableExpression("exit", List.of())));
        assertThat(only("42").body()).isEqualTo(new ExpressionBody(new Ast.Literal(42)));
        assertThat(only("true").body()).isEqualTo(new ExpressionBody(new Ast.Literal(true)));
        assertThat(only("\"ok\"").body()).isInstanceOf(ExpressionBody.class);
    }

    @Test
    void assignment() {
        var statement = only("$l = ipconfig /all");
        assertThat(statement.assignTo()).isEqualTo(Optional.of("l"));
        assertThat(statement.body()).isEqualTo(new Command("ipconfig", false, List.of(new WordArgument("/all"))));
    }

    @Test
    void chainedStatements() {
        var steps = Parser.parse("a ; b && c || d;").steps();
        assertThat(steps).extracting(Ast.Step::connector)
                .containsExactly(Connector.ALWAYS, Connector.ALWAYS, Connector.IF_SUCCESS, Connector.IF_FAILURE);
    }

    @Test
    void redirections() {
        var statement = only("git log > log.txt 2>> err.txt");
        assertThat(statement.redirects()).containsExactly(
                new Ast.Redirect(Token.Stream.OUT, false, new WordArgument("log.txt")),
                new Ast.Redirect(Token.Stream.ERR, true, new WordArgument("err.txt")));
    }

    @Test
    void syntaxErrors() {
        assertThatThrownBy(() -> Parser.parse("&& a")).hasMessageContaining("without a command before it");
        assertThatThrownBy(() -> Parser.parse("a &&")).hasMessageContaining("command expected after");
        assertThatThrownBy(() -> Parser.parse("a >")).hasMessageContaining("file expected");
        assertThatThrownBy(() -> Parser.parse("$x =")).hasMessageContaining("value expected");
        assertThatThrownBy(() -> Parser.parse("ls |")).hasMessageContaining("command expected after \"|\"");
        assertThatThrownBy(() -> Parser.parse("| ls")).hasMessageContaining("command expected");
        assertThatThrownBy(() -> Parser.parse("ls | $x")).hasMessageContaining("first stage");
        assertThatThrownBy(() -> Parser.parse("ls | { e -> e.name }")).hasMessageContaining("write map { … }");
        assertThatThrownBy(() -> Parser.parse("ls | { $_.name }")).hasMessageContaining("write map { … }");
        assertThatThrownBy(() -> Parser.parse("ls > f | where x")).hasMessageContaining("put it at the end");
        assertThatThrownBy(() -> Parser.parse("where }")).hasMessageContaining("\"}\" without a matching \"{\"");
        assertThatThrownBy(() -> Parser.parse("where { $_.x ")).hasMessageContaining("unclosed block");
        assertThatThrownBy(() -> Parser.parse("$x.y = 1")).hasMessageContaining("assignment: $name = value");
    }

    @Test
    void subExpressions() {
        assertThat(only("(ls).name").body()).isEqualTo(new ExpressionBody(new Ast.Get(new Ast.SubExpression(
                pipeline(new Command("ls", false, List.of()))), "name")));
        assertThat(only("help members (ls -r)[0]").body()).isEqualTo(new Command("help", false, List.of(
                new WordArgument("members"),
                new Ast.ExpressionArgument(new Ast.At(new Ast.SubExpression(pipeline(new Command("ls", false,
                        List.of(new WordArgument("-r"))))), new Ast.Literal(0))))));
        assertThatThrownBy(() -> Parser.parse("(ls")).hasMessageContaining("missing \")\"");
        assertThatThrownBy(() -> Parser.parse("()")).hasMessageContaining("empty parentheses");
        assertThatThrownBy(() -> Parser.parse("ls)")).hasMessageContaining("unexpected \")\"");
        // A value followed by a pipeline, in parentheses.
        var grouped = (ExpressionBody) only("($l | where { $_ }).size()").body();
        assertThat(((Ast.Invoke) grouped.expression()).target()).isInstanceOf(Ast.SubExpression.class);
        // A real expression in parentheses.
        assertThat(only("(1 + 2)").body()).isEqualTo(new ExpressionBody(new Ast.Binary(Ast.Operator.ADD,
                new Ast.Literal(1), new Ast.Literal(2))));
    }

    private static Ast.Pipeline pipeline(Ast.Body... bodies) {
        return new Ast.Pipeline(java.util.Arrays.stream(bodies).map(b -> new Ast.Stage(b, false)).toList(), List.of());
    }

    @Test
    void pipelineStages() {
        var statement = only("ls -r | where { $_.size > 1mb } | ^more");
        assertThat(statement.pipeline().stages()).extracting(s -> ((Command) s.body()).name())
                .containsExactly("ls", "where", "more");
        var where = (Command) statement.pipeline().stages().get(1).body();
        assertThat(where.arguments()).singleElement().isEqualTo(new Ast.ExpressionArgument(new Ast.BlockExpression(
                "$_.size > 1mb", new Ast.Binary(Ast.Operator.GT,
                        new Ast.Get(new Ast.VariableExpression("_", List.of()), "size"), new Ast.Literal(1024L * 1024)))));
        // Java expression as the first stage.
        assertThat(only("java.util.List.of(1, 2) | where { $_ > 1 }").pipeline().stages().getFirst().body())
                .isInstanceOf(ExpressionBody.class);
        assertThat(((Command) statement.pipeline().stages().get(2).body()).forceNative()).isTrue();
    }

    @Test
    void pipelineInsideParentheses() {
        var body = (ExpressionBody) only("(ls | where { $_.dir }).name").body();
        var sub = (Ast.SubExpression) ((Ast.Get) body.expression()).target();
        assertThat(sub.pipeline().stages()).hasSize(2);
    }

    @Test
    void errorsToOutputPerStage() {
        var statement = only("git status 2>&1 | where { $_.contains(\"x\") } 2> err.txt");
        assertThat(statement.pipeline().stages()).extracting(Ast.Stage::errorsToOutput).containsExactly(true, false);
        assertThat(statement.redirects()).containsExactly(
                new Ast.Redirect(Token.Stream.ERR, false, new WordArgument("err.txt")));
    }

    @Test
    void whereShortForm() {
        assertThat(only("ls | where size > 10kb > out.txt").pipeline()).satisfies(p -> {
            assertThat(((Command) p.stages().get(1).body()).arguments()).containsExactly(
                    new WordArgument("size"), new WordArgument(">"), new WordArgument("10kb"));
            assertThat(p.redirects()).containsExactly(new Ast.Redirect(Token.Stream.OUT, false, new WordArgument("out.txt")));
        });
        assertThat(((Command) only("where ext == log").body()).arguments()).containsExactly(
                new WordArgument("ext"), new WordArgument("=="), new WordArgument("log"));
        assertThat(((Command) only("where size >= 1").body()).arguments()).hasSize(3);
        // Outside of where, > remains a redirection.
        assertThat(only("echo a > b").redirects()).hasSize(1);
    }

    @Test
    void nonBreakingSpacesSeparateWords() {
        // AltGr+6 then AltGr+Space on a French keyboard: "|" followed by a non-breaking space.
        var statement = only("ls -r |\u00a0where {\u00a0$_.size\u202f>\u00a06kb }");
        assertThat(statement.pipeline().stages()).extracting(s -> ((Command) s.body()).name())
                .containsExactly("ls", "where");
    }

    @Test
    void emptyLineHasNoStep() {
        assertThat(Parser.parse("   ").steps()).isEmpty();
        assertThat(Parser.parse(";;").steps()).isEmpty();
    }
}
