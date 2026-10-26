package io.powerj.core.lang;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** What the reading of a line depends on, beyond the text. */
class ParseContextTest {

    private final ParseContext context = new ParseContext("Math.PI"::equals, "where"::equals);

    @Test
    void nothingIsKnownByDefault() {
        assertThat(ParseContext.NONE.startsExpression("Math.PI")).isFalse();
        assertThat(ParseContext.NONE.acceptsComparison("where")).isFalse();
        assertThat(ParseContext.NONE.isParameter("f")).isFalse();
    }

    @Test
    void aJavaStaticNameStartsAnExpression() {
        assertThat(context.startsExpression("Math.PI")).isTrue();
        assertThat(context.startsExpression("Math.E")).isFalse();
    }

    @Test
    void onlyTheCommandsThatDeclareItTakeTheShortForm() {
        assertThat(context.acceptsComparison("where")).isTrue();
        assertThat(context.acceptsComparison("sort")).isFalse();
    }

    @Test
    void ofKnowsTheStaticNamesOnly() {
        var onlyStatics = ParseContext.of("Math.PI"::equals);

        assertThat(onlyStatics.startsExpression("Math.PI")).isTrue();
        assertThat(onlyStatics.acceptsComparison("where")).isFalse();
    }

    @Test
    void theParameterOfALambdaStartsAnExpressionAndAPathFromIt() {
        var inLambda = context.withParameters(List.of("f"));

        assertThat(inLambda.isParameter("f")).isTrue();
        assertThat(inLambda.startsExpression("f")).isTrue();
        assertThat(inLambda.startsExpression("f.size")).isTrue();
        assertThat(inLambda.startsExpression("g.size")).isFalse();
        assertThat(context.isParameter("f")).as("the outer context is not changed").isFalse();
    }

    @Test
    void aNestedLambdaSeesTheParametersOfTheEnclosingOnes() {
        var inner = context.withParameters(List.of("f")).withParameters(List.of("g"));

        assertThat(inner.parameters()).containsExactlyInAnyOrder("f", "g");
        assertThat(inner.startsExpression("Math.PI")).isTrue();
        assertThat(inner.acceptsComparison("where")).isTrue();
    }

    @Test
    void noParameterLeavesTheContextAsItIs() {
        assertThat(context.withParameters(List.of())).isSameAs(context);
    }

    @Test
    void theParametersAreCopied() {
        var names = new java.util.HashSet<>(Set.of("f"));
        var withParameters = new ParseContext(_ -> false, _ -> false, names);

        names.add("g");

        assertThat(withParameters.parameters()).containsExactly("f");
    }
}
