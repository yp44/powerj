package io.powerj.core.lang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.powerj.core.lang.Ast.Binary;
import io.powerj.core.lang.Ast.Get;
import io.powerj.core.lang.Ast.Invoke;
import io.powerj.core.lang.Ast.Literal;
import io.powerj.core.lang.Ast.Operator;
import io.powerj.core.lang.Ast.VariableExpression;

class ExpressionParserTest {

    private static final VariableExpression CURRENT = new VariableExpression("_", List.of());

    @Test
    void precedence() {
        assertThat(ExpressionParser.parse("1 + 2 * 3")).isEqualTo(new Binary(Operator.ADD, new Literal(1),
                new Binary(Operator.MUL, new Literal(2), new Literal(3))));
        var parsed = (Binary) ExpressionParser.parse("$a || $b && $c");
        assertThat(parsed.operator()).isEqualTo(Operator.OR);
        assertThat(((Binary) parsed.right()).operator()).isEqualTo(Operator.AND);
        var comparison = (Binary) ExpressionParser.parse("$_.size > 1 + 1 == true");
        assertThat(comparison.operator()).isEqualTo(Operator.EQ);
    }

    @Test
    void postfixAccess() {
        assertThat(ExpressionParser.parse("$_.name.endsWith(\".java\")")).isEqualTo(new Invoke(
                new Get(CURRENT, "name"), "endsWith",
                List.of(new Ast.StringExpression(List.of(new StringPart.Text(".java"))))));
        assertThat(ExpressionParser.parse("$_.items[0]")).isEqualTo(new Ast.At(new Get(CURRENT, "items"), new Literal(0)));
        assertThat(ExpressionParser.parse("$_.f()")).isEqualTo(new Invoke(CURRENT, "f", List.of()));
    }

    @Test
    void literals() {
        assertThat(ExpressionParser.parse("10kb")).isEqualTo(new Literal(10_240L));
        assertThat(ExpressionParser.parse("7d")).isEqualTo(new Literal(Duration.ofDays(7)));
        assertThat(ExpressionParser.parse("250ms")).isEqualTo(new Literal(Duration.ofMillis(250)));
        assertThat(ExpressionParser.parse("5000000000")).isEqualTo(new Literal(5_000_000_000L));
        assertThat(ExpressionParser.parse("3L")).isEqualTo(new Literal(3L));
        assertThat(ExpressionParser.parse("1.25")).isEqualTo(new Literal(1.25));
        assertThat(ExpressionParser.parse("'\\n'")).isEqualTo(new Literal('\n'));
        assertThat(ExpressionParser.parse("'\\''")).isEqualTo(new Literal('\''));
        assertThat(ExpressionParser.parse("null")).isEqualTo(new Literal(null));
        assertThat(ExpressionParser.parse("now")).isEqualTo(new Ast.Now());
        assertThat(ExpressionParser.parse("[]")).isEqualTo(new Ast.ListLiteral(List.of()));
    }

    @Test
    void ternaryAndUnary() {
        assertThat(ExpressionParser.parse("!$_.dir ? 1 : -1")).isEqualTo(new Ast.Conditional(
                new Ast.Unary(Operator.NOT, new Get(CURRENT, "dir")), new Literal(1),
                new Ast.Unary(Operator.NEG, new Literal(1))));
    }

    @Test
    void errors() {
        assertThatThrownBy(() -> ExpressionParser.parse("")).hasMessageContaining("bloc vide");
        assertThatThrownBy(() -> ExpressionParser.parse("1 +")).hasMessageContaining("expression incomplète");
        assertThatThrownBy(() -> ExpressionParser.parse("(1")).hasMessageContaining("« ) » manquant");
        assertThatThrownBy(() -> ExpressionParser.parse("1 2")).hasMessageContaining("« 2 » inattendu");
        assertThatThrownBy(() -> ExpressionParser.parse("$_.")).hasMessageContaining("nom attendu après « . »");
        assertThatThrownBy(() -> ExpressionParser.parse("$_ like \"b\"")).hasMessageContaining("« like » inattendu");
        assertThatThrownBy(() -> ExpressionParser.parse("$_.size = 1")).hasMessageContaining("utiliser ==");
        assertThatThrownBy(() -> ExpressionParser.parse("new File")).hasMessageContaining("« ( » attendu après new File");
        assertThatThrownBy(() -> ExpressionParser.parse("$")).hasMessageContaining("nom de variable attendu");
        assertThatThrownBy(() -> ExpressionParser.parse("1 # 2")).hasMessageContaining("« # » inattendu");
        assertThatThrownBy(() -> ExpressionParser.parse("\"abc")).hasMessageContaining("chaîne non fermée");
    }

    @Test
    void javaSyntax() {
        assertThat(ExpressionParser.parse("java.util.List.of(\"a\")")).isEqualTo(new Invoke(
                new Get(new Get(new Ast.Name("java"), "util"), "List"), "of",
                List.of(new Ast.StringExpression(List.of(new StringPart.Text("a"))))));
        assertThat(ExpressionParser.parse("new StringBuilder(\"ab\").reverse()")).isEqualTo(new Invoke(
                new Ast.New("StringBuilder", List.of(new Ast.StringExpression(List.of(new StringPart.Text("ab"))))),
                "reverse", List.of()));
        assertThat(ExpressionParser.parse("[java.util.ArrayList] $l")).isEqualTo(
                new Ast.Cast("java.util.ArrayList", new VariableExpression("l", List.of())));
        assertThat(ExpressionParser.parse("[x]")).isEqualTo(new Ast.ListLiteral(List.of(new Ast.Name("x"))));
        assertThat(ExpressionParser.parse("$l.sort({ $a.length() - $b.length() })"))
                .isInstanceOf(Invoke.class)
                .extracting(e -> ((Invoke) e).arguments().getFirst()).isInstanceOf(Ast.BlockExpression.class);
        // Dans un bloc, les accès peuvent être séparés par des espaces.
        assertThat(ExpressionParser.parse("$_ .name")).isEqualTo(new Get(CURRENT, "name"));
    }

    @Test
    void spreadOperator() {
        assertThat(ExpressionParser.parse("$l*.name")).isEqualTo(new Ast.SpreadGet(new VariableExpression("l", List.of()), "name"));
        assertThat(ExpressionParser.parse("$l*.name.size()")).isEqualTo(new Invoke(
                new Ast.SpreadGet(new VariableExpression("l", List.of()), "name"), "size", List.of()));
        assertThat(ExpressionParser.parse("$l*.trim()")).isEqualTo(
                new Ast.SpreadInvoke(new VariableExpression("l", List.of()), "trim", List.of()));
        assertThat(ExpressionParser.parse("2 * 3")).isInstanceOf(Binary.class);
    }

    @Test
    void units() {
        assertThat(Units.parse("2gb")).contains(2L * 1024 * 1024 * 1024);
        assertThat(Units.parse("30s")).contains(Duration.ofSeconds(30));
        assertThat(Units.parse("5m")).contains(Duration.ofMinutes(5));
        assertThat(Units.parse("10KB")).contains(10_240L);
        assertThat(Units.parse("10")).isEmpty();
        assertThat(Units.parse("abc")).isEmpty();
    }
}
