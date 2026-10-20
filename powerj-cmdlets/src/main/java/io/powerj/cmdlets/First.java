package io.powerj.cmdlets;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;

/**
 * {@code first}: the first {@code n} objects of the pipeline (specification FR-68). Once they are emitted, the previous
 * stage is stopped ({@link CmdletContext#stopInput()}): {@code ls -r / | first 3} answers at once.
 */
@CmdletInfo(name = "first", preview = true, passThrough = true, category = "Filters", summary = "Keeps the first objects of the pipeline",
        examples = {"ls | first", "ls -r | first 5", "ls | sort size -d | first 3", "git log --oneline | first 10"})
public final class First implements Cmdlet<First.Params, Object, Object> {

    /** Parameters of {@code first}. */
    public record Params(
            @Option(position = 0, description = "Number of objects kept (default: 1)")
            Integer count) {
    }

    /** Objects still to emit, per execution. */
    private final Map<CmdletContext<?>, AtomicInteger> remaining = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void begin(Params p, CmdletContext<Object> context) {
        int count = count(p.count());
        remaining.put(context, new AtomicInteger(count));
        if (count == 0) {
            context.stopInput();
        }
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Object> context) {
        AtomicInteger left = remaining.get(context);
        if (left.get() <= 0) {
            return; // the input was stopped; nothing more is emitted
        }
        context.emit(input);
        if (left.decrementAndGet() == 0) {
            context.stopInput();
        }
    }

    @Override
    public void end(Params p, CmdletContext<Object> context) {
        remaining.remove(context);
    }

    /** Number of objects, 1 by default. */
    static int count(Integer count) {
        if (count == null) {
            return 1;
        }
        if (count < 0) {
            throw new IllegalArgumentException(Messages.get("count.negative", count));
        }
        return count;
    }
}
