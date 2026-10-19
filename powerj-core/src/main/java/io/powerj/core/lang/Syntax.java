package io.powerj.core.lang;

import java.util.function.Predicate;

/**
 * What the syntax of a line depends on, beyond the text: the Java names that start an expression ({@code Math.PI},
 * FR-46) and the commands that take the short form {@code property operator value} ({@code where size > 1mb},
 * FR-36), as declared by the cmdlets. Passed where a {@code Predicate<String>} of static names is expected, so that
 * it reaches the parentheses {@code ( … )} parsed by {@link ExpressionParser}.
 *
 * @param staticNames        recognizes qualified names designating a Java class or static field
 * @param comparisonCommands recognizes the commands whose {@code >} after the first word is a comparison
 */
public record Syntax(Predicate<String> staticNames, Predicate<String> comparisonCommands) implements Predicate<String> {

    @Override
    public boolean test(String name) {
        return staticNames.test(name);
    }

    /** Does {@code command} take the short form, according to the {@code Syntax} carried by {@code names}? */
    static boolean acceptsComparison(Predicate<String> names, String command) {
        return ExpressionParser.syntax(names) instanceof Syntax syntax && syntax.comparisonCommands().test(command);
    }
}
