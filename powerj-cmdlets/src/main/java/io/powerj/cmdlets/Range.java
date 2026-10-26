package io.powerj.cmdlets;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import io.powerj.api.Attempt;
import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.ScriptBlock;
import io.powerj.api.UsageException;

/**
 * {@code range}: the objects between two bounds (specification FR-68). A bound is a position (from 1) without
 * {@code --property}, a regular expression searched in the property with {@code --property}, or a block condition.
 * <pre>
 * cat a.log | range --from 5 --to 10
 * env | range --from JAVA --to PATH --property name
 * cat app.log | range --from { $_.contains("BEGIN") } --to { $_.contains("END") } --repeat
 * </pre>
 * Without {@code --repeat}, the previous stage is stopped once the range is closed.
 */
@CmdletInfo(name = "range", preview = true, passThrough = true, category = "Filters", summary = "Keeps the objects between two bounds",
        examples = {"cat a.log | range --from 5 --to 10", "ls | range --from 3",
                "env | range --from JAVA --to PATH --property name -i",
                "ls | range --from ^a --to ^d --property name --exclude",
                "cat app.log | range --from { $_.contains(\"BEGIN\") } --to { $_.contains(\"END\") } --repeat"})
public final class Range implements Cmdlet<Range.Params, Object, Object> {

    /** Parameters of {@code range}. */
    public record Params(
            @Option(description = "Opening bound: position (from 1), regular expression with --property, or block { … }")
            Object from,
            @Option(description = "Closing bound: position (from 1), regular expression with --property, or block { … }")
            Object to,
            @Option(description = "Property (or path) on which --from and --to are searched as regular expressions")
            String property,
            @Option(shortName = 'i', description = "The regular expressions ignore case")
            boolean ignoreCase,
            @Option(description = "Emits the opening and closing objects (default)")
            boolean include,
            @Option(description = "Leaves out the opening and closing objects")
            boolean exclude,
            @Option(description = "After each closing, waits for the next opening (several ranges)")
            boolean repeat) {
    }

    /** Bound of a range. */
    private sealed interface Bound {
    }

    /** Position of the object, from 1. */
    private record Position(long n) implements Bound {
    }

    /** Regular expression searched in a property of the object. */
    private record Match(ScriptBlock property, Pattern regex) implements Bound {
    }

    /** Boolean condition on the object. */
    private record Condition(ScriptBlock block) implements Bound {
    }

    /** State of an execution. {@code from == null}: the range is open from the first object. */
    private static final class State {
        final Bound from;
        final Bound to;
        long index;
        boolean open;
        boolean done;

        State(Bound from, Bound to) {
            this.from = from;
            this.to = to;
            this.open = from == null;
        }
    }

    @Override
    public void begin(Params p, CmdletContext<Object> context) {
        if (p.include() && p.exclude()) {
            throw new UsageException(Messages.get("range.include.exclude"));
        }
        ScriptBlock property = p.property() == null ? null : Keys.block(p.property(), context, "range.property.invalid");
        Bound from = bound("--from", p.from(), property, p.ignoreCase());
        Bound to = bound("--to", p.to(), property, p.ignoreCase());
        if (p.repeat() && !(from instanceof Match || from instanceof Condition)) {
            throw new UsageException(Messages.get("range.repeat.position"));
        }
        State state = context.state(State.class, () -> new State(from, to));
        long first = from instanceof Position(long n) ? n : 1;
        if (to instanceof Position(long n) && n < first) {
            state.done = true; // --to before --from: nothing to emit
            context.stopInput();
        }
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Object> context) {
        State state = ExecutionState.begun(context, State.class);
        state.index++;
        if (state.done) {
            return; // the input was stopped
        }
        boolean include = !p.exclude();
        if (!state.open) {
            var opens = matches(state.from, input, state.index);
            if (opens.failed()) {
                Skipped.report(context, opens.error(), input); // FR-68: neither emitted nor used as a bound
                return;
            }
            if (!opens.value()) {
                return;
            }
            state.open = true;
            // A position --to already reached closes the range on its opening object (as sed does).
            if (state.to instanceof Position(long n) && state.index >= n) {
                if (include) {
                    context.emit(input);
                }
                close(state, p, context);
                return;
            }
            if (include) {
                context.emit(input);
            }
            return; // --to is tested from the next object
        }
        if (state.to != null) {
            var closes = matches(state.to, input, state.index);
            if (closes.failed()) {
                Skipped.report(context, closes.error(), input);
                return;
            }
            if (closes.value()) {
                if (include) {
                    context.emit(input);
                }
                close(state, p, context);
                return;
            }
        }
        context.emit(input);
    }

    private static void close(State state, Params p, CmdletContext<Object> context) {
        state.open = false;
        if (!p.repeat()) {
            state.done = true;
            context.stopInput();
        }
    }

    private static Attempt<Boolean> matches(Bound bound, Object input, long index) {
        return switch (bound) {
            case Position(long n) -> Attempt.succeeded(index == n);
            case Match(ScriptBlock property, Pattern regex) -> property.tryInvoke(input).map(value ->
                    value != null && regex.matcher(new InterruptibleText(String.valueOf(value))).find());
            case Condition(ScriptBlock block) -> block.tryTest(input);
        };
    }

    /** Bound given for {@code option}: {@code null} if absent. */
    private static Bound bound(String option, Object value, ScriptBlock property, boolean ignoreCase) {
        if (value == null) {
            return null;
        }
        if (value instanceof ScriptBlock block) {
            return new Condition(block);
        }
        String text = value instanceof Number n ? n.toString() : String.valueOf(value);
        if (property != null) {
            try {
                return new Match(property, Pattern.compile(text,
                        ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0));
            } catch (PatternSyntaxException e) {
                throw new UsageException(Messages.get("grep.pattern.invalid", text, e.getDescription(),
                        e.getIndex()));
            }
        }
        long position;
        try {
            position = Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new UsageException(Messages.get("range.not.position", option, text));
        }
        if (position < 1) {
            throw new UsageException(Messages.get("range.position.invalid", option, text));
        }
        return new Position(position);
    }
}
