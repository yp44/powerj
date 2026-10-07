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
        assertThatThrownBy(() -> Parser.parse("^")).hasMessageContaining("nom de commande attendu");
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
        assertThatThrownBy(() -> Parser.parse("&& a")).hasMessageContaining("sans commande avant");
        assertThatThrownBy(() -> Parser.parse("a &&")).hasMessageContaining("commande attendue après");
        assertThatThrownBy(() -> Parser.parse("a >")).hasMessageContaining("fichier attendu");
        assertThatThrownBy(() -> Parser.parse("$x =")).hasMessageContaining("valeur attendue");
        assertThatThrownBy(() -> Parser.parse("ls | where")).hasMessageContaining("pipeline");
        assertThatThrownBy(() -> Parser.parse("$x.y = 1")).hasMessageContaining("variable simple");
    }

    @Test
    void emptyLineHasNoStep() {
        assertThat(Parser.parse("   ").steps()).isEmpty();
        assertThat(Parser.parse(";;").steps()).isEmpty();
    }
}
