package io.powerj.core.lang;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What the reading of a line depends on, beyond the text: the Java names that start an expression ({@code Math.PI},
 * FR-46), the commands that take the short form {@code property operator value} ({@code where size > 1mb}, FR-36) as
 * declared by the cmdlets, and the parameters of the lambdas the reader is inside of ({@code (f.size)} in
 * {@code f -> …} is an expression, not the command {@code f.size}). It travels with the reader through the lexer, the
 * expression parser and the pipeline parser, which call each other.
 *
 * @param staticNames        recognizes qualified names designating a Java class or static field
 * @param comparisonCommands recognizes the commands whose {@code >} after the first word is a comparison
 * @param parameters         parameters of the enclosing lambdas
 */
public record ParseContext(Predicate<String> staticNames, Predicate<String> comparisonCommands, Set<String> parameters) {

    /** Nothing is known: no Java static name, no short-form command, no lambda. */
    public static final ParseContext NONE = new ParseContext(_ -> false, _ -> false);

    public ParseContext {
        parameters = Set.copyOf(parameters);
    }

    public ParseContext(Predicate<String> staticNames, Predicate<String> comparisonCommands) {
        this(staticNames, comparisonCommands, Set.of());
    }

    /** Only the Java static names are known. */
    public static ParseContext of(Predicate<String> staticNames) {
        return new ParseContext(staticNames, _ -> false);
    }

    /**
     * Is {@code name}, a word or a dotted chain, the start of a Java expression: a class or static field, or a path
     * that starts with the parameter of an enclosing lambda?
     */
    public boolean startsExpression(String name) {
        int dot = name.indexOf('.');
        return parameters.contains(dot < 0 ? name : name.substring(0, dot)) || staticNames.test(name);
    }

    /** Is {@code name} a parameter of an enclosing lambda? */
    public boolean isParameter(String name) {
        return parameters.contains(name);
    }

    /** Does {@code command} take the short form {@code where size > 1mb}? */
    public boolean acceptsComparison(String command) {
        return comparisonCommands.test(command);
    }

    /** The same, inside a lambda that has these parameters. */
    ParseContext withParameters(List<String> names) {
        if (names.isEmpty()) {
            return this;
        }
        Set<String> all = new HashSet<>(parameters);
        all.addAll(names);
        return new ParseContext(staticNames, comparisonCommands, all);
    }
}
