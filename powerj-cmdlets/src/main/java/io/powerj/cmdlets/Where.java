package io.powerj.cmdlets;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.ScriptBlock;
import io.powerj.api.UsageException;

/**
 * {@code where}: lets through only the objects for which the condition is true (specification FR-36).
 * <pre>
 * where { f -> f.size > 1mb && !f.dir }
 * where { $_.dir }
 * where size > 1mb                      # short form, equivalent to where { $_.size > 1mb }
 * </pre>
 */
@CmdletInfo(name = "where", preview = true, comparison = true, passThrough = true, category = "Filters",
        summary = "Filters pipeline objects by a condition",
        examples = {"ls -r | where { $_.size > 1mb }", "ls | where { $_.name.endsWith(\".java\") && !$_.dir }",
                "ls | where { f -> f.size > 1mb && !f.dir }", "ls | where size > 10kb", "git status --porcelain | where { $_.startsWith(\" M \") }",
                "env | where { $_.name.startsWith(\"JAVA\") }"})
public final class Where implements Cmdlet<Where.Params, Object, Object> {

    /** Parameters of {@code where}. */
    public record Params(
            @Option(position = 0, mandatory = true,
                    description = "Boolean condition: lambda { f -> … }, block { $_… }, or short form: property operator value")
            List<Object> condition) {
    }

    private static final Set<String> OPERATORS = Set.of("==", "!=", "<", "<=", ">", ">=");
    private static final Pattern LITERAL = Pattern.compile(
            "-?\\d+(\\.\\d+)?(L|b|kb|mb|gb|tb|ms|s|m|h|d)?|true|false|null", Pattern.CASE_INSENSITIVE);

    /** The condition of this execution, compiled once. */
    private record Compiled(ScriptBlock condition) {
    }

    private static ScriptBlock compiled(Params p, CmdletContext<Object> context) {
        return context.state(Compiled.class, () -> new Compiled(condition(p.condition(), context))).condition();
    }

    @Override
    public void begin(Params p, CmdletContext<Object> context) {
        compiled(p, context); // compiles the condition before anything is read
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Object> context) {
        ScriptBlock condition = compiled(p, context);
        var keep = condition.tryTest(input);
        if (keep.failed()) {
            Skipped.report(context, keep.error(), input); // FR-36: an evaluation error is non-blocking
            return;
        }
        if (keep.value()) {
            context.emit(input);
        }
    }

    /** Block given as is, or block compiled from the short form. */
    static ScriptBlock condition(List<Object> words, CmdletContext<?> context) {
        if (words.size() == 1 && words.getFirst() instanceof ScriptBlock block) {
            return block;
        }
        if (words.size() == 3 && words.get(0) instanceof String property && words.get(1) instanceof String operator
                && OPERATORS.contains(operator)) {
            if (!Texts.isPropertyPath(property)) {
                throw new UsageException(Messages.get("where.property.invalid", property));
            }
            return context.compile("$_." + property + " " + operator + " " + value(words.get(2)));
        }
        throw new UsageException(Messages.get("where.condition.expected"));
    }

    /** Short-form value as expression text: literal as is, otherwise a quoted string. */
    private static String value(Object value) {
        return switch (value) {
            case null -> "null";
            case Number n -> n.toString();
            case Boolean b -> b.toString();
            case String s when LITERAL.matcher(s).matches() -> s;
            case String s -> quote(s);
            default -> throw new UsageException(Messages.get("where.value.unsupported",
                    value.getClass().getSimpleName()));
        };
    }

    private static String quote(String text) {
        var quoted = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '$' -> quoted.append("\\$");
                case '\n' -> quoted.append("\\n");
                case '\t' -> quoted.append("\\t");
                default -> quoted.append(c);
            }
        }
        return quoted.append('"').toString();
    }
}
