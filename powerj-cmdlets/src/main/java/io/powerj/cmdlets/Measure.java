package io.powerj.cmdlets;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.Row;
import io.powerj.api.ScriptBlock;

/**
 * {@code measure}: count, sum, average, minimum and maximum of the objects or of a key (specification FR-70), computed
 * as the objects arrive and emitted as one {@link Row} when the input ends.
 * <pre>
 * ls -r | measure size            # sum, average, min and max shown as sizes
 * ls | measure modified           # oldest and newest dates
 * (ls -r | measure size).sum
 * </pre>
 */
@CmdletInfo(name = "measure", preview = true, category = "Filters",
        summary = "Counts and sums the objects or a property, with average, minimum and maximum",
        examples = {"ls -r | measure size", "ls | measure modified", "env | measure { $_.value.length() }",
                "(ls -r | measure size).sum", "List.of(3, 1, 2) | measure"})
public final class Measure implements Cmdlet<Measure.Params, Object, Row> {

    /** Parameters of {@code measure}. */
    public record Params(
            @Option(position = 0, description = "Value measured: property, property path or block { … }; none: the objects themselves")
            Object key) {
    }

    private static final class State {
        final ScriptBlock key;
        final SortComparator comparator;
        long count;
        BigDecimal sum = BigDecimal.ZERO;
        boolean numeric = true;
        boolean integral = true;
        Object min;
        Object max;
        Boolean size; // decided on the first object

        State(ScriptBlock key, SortComparator comparator) {
            this.key = key;
            this.comparator = comparator;
        }
    }

    private final Map<CmdletContext<?>, State> states = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void begin(Params p, CmdletContext<Row> context) {
        ScriptBlock key = p.key() == null ? null : Keys.block(p.key(), context, "measure.key.invalid");
        states.put(context, new State(key, new SortComparator(false, context::error)));
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Row> context) {
        State state = states.get(context);
        if (state.size == null) {
            state.size = p.key() instanceof String property && Sizes.isSizeProperty(input, property);
        }
        Object value;
        try {
            value = state.key == null ? input : state.key.invoke(input);
        } catch (CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            context.error(Messages.get("pipeline.object.skipped", e.getMessage(), Where.abbreviate(input)));
            return;
        }
        if (value == null) {
            return;
        }
        state.count++;
        if (state.numeric && value instanceof Number number && isFinite(number)) {
            state.integral &= isIntegral(number);
            state.sum = state.sum.add(decimal(number));
        } else {
            state.numeric = false;
        }
        if (state.min == null || state.comparator.compare(value, state.min) < 0) {
            state.min = value;
        }
        if (state.max == null || state.comparator.compare(value, state.max) > 0) {
            state.max = value;
        }
    }

    @Override
    public void end(Params p, CmdletContext<Row> context) {
        State state = states.remove(context);
        boolean numbers = state.numeric && state.count > 0;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("count", state.count);
        values.put("sum", numbers ? sum(state) : null);
        values.put("average", numbers
                ? state.sum.divide(BigDecimal.valueOf(state.count), MathContext.DECIMAL64).doubleValue() : null);
        values.put("min", state.min);
        values.put("max", state.max);
        Set<String> sizes = Boolean.TRUE.equals(state.size) ? Set.of("sum", "average", "min", "max") : Set.of();
        context.emit(Row.of(values, sizes));
    }

    /** Exact sum: {@code Long}, or {@code BigInteger} beyond the range of a long; {@code Double} if a value is not integral. */
    private static Number sum(State state) {
        if (!state.integral) {
            return state.sum.doubleValue();
        }
        BigInteger exact = state.sum.toBigIntegerExact();
        return exact.bitLength() < Long.SIZE ? (Number) exact.longValue() : exact;
    }

    /** {@code NaN} and infinities have no exact sum: the sum and the average are then left empty. */
    private static boolean isFinite(Number n) {
        return !(n instanceof Double || n instanceof Float) || Double.isFinite(n.doubleValue());
    }

    private static boolean isIntegral(Number n) {
        return n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte
                || n instanceof BigInteger;
    }

    private static BigDecimal decimal(Number n) {
        return switch (n) {
            case BigDecimal d -> d;
            case BigInteger i -> new BigDecimal(i);
            case Double d -> new BigDecimal(d.toString());
            case Float f -> new BigDecimal(f.toString());
            default -> BigDecimal.valueOf(n.longValue());
        };
    }
}
