package io.powerj.core.runtime;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import io.powerj.core.format.ValueUnroller;
import io.powerj.core.java.PreviewGuard;

/**
 * What a stage that is not the last sends to the next one in a preview (FR-66, {@link PreviewSettings#stageLimit()}):
 * the objects are counted, and the one after the limit stops the stage. A stage that is stopped closes its pipe, as one
 * that has finished does, so the next stage reads a normal end of its input and a stage that emits only then ({@code sort},
 * {@code group}) emits what it has. The values are the real ones of the first objects, not of all of them: the guard
 * is told ({@link PreviewGuard#stageCut}) so that the preview says so.
 */
final class StageLimit implements Consumer<Object> {

    /** Stops the stage that has sent its share; a {@link CancellationException}, as a stage is stopped by one. */
    private static final class Full extends CancellationException {
        Full() {
            super("preview: the stage has sent as many objects as a preview takes");
        }
    }

    private final Pipe output;
    private final PreviewGuard guard;
    private final AtomicInteger sent = new AtomicInteger();

    private StageLimit(Pipe output, PreviewGuard guard) {
        this.output = output;
        this.guard = guard;
    }

    /** What the stage writes to {@code output}: counted in a preview, as it is otherwise. */
    static Consumer<Object> into(Pipe output) {
        PreviewGuard guard = PreviewGuard.current();
        return guard == null ? output::putUnrolled : new StageLimit(output, guard);
    }

    @Override
    public void accept(Object value) {
        if (!ValueUnroller.unroll(value, ValueUnroller.Mode.BETWEEN_STAGES, this::send)) {
            send(value);
        }
    }

    private void send(Object item) {
        if (sent.incrementAndGet() > guard.stageLimit()) {
            guard.stageCut();
            throw new Full();
        }
        output.put(item);
    }
}
