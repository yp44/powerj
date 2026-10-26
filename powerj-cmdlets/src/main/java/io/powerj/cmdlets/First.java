package io.powerj.cmdlets;

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

    /** Objects still to emit. */
    private static final class Remaining {
        int left;

        Remaining(int left) {
            this.left = left;
        }
    }

    @Override
    public void begin(Params p, CmdletContext<Object> context) {
        int count = Counts.orOne(p.count());
        context.state(Remaining.class, () -> new Remaining(count));
        if (count == 0) {
            context.stopInput();
        }
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Object> context) {
        Remaining remaining = context.state(Remaining.class, () -> new Remaining(Counts.orOne(p.count())));
        if (remaining.left <= 0) {
            return; // the input was stopped; nothing more is emitted
        }
        context.emit(input);
        if (--remaining.left == 0) {
            context.stopInput();
        }
    }
}
