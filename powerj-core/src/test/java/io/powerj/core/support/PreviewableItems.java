package io.powerj.core.support;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;

/** A cmdlet declared safe to preview, for the tests of preview mode. */
public final class PreviewableItems {

    private PreviewableItems() {
    }

    @CmdletInfo(name = "items", preview = true, category = "Test", summary = "Produces test objects",
            examples = "items -n 2")
    static final class Items implements Cmdlet<Items.Params, Void, FakeCmdlets.Item> {

        record Params(@Option(shortName = 'n', description = "Number of objects") int count) { }

        @Override
        public void begin(Params params, CmdletContext<FakeCmdlets.Item> context) {
            for (int i = 1; i <= (params.count() == 0 ? 3 : params.count()); i++) {
                context.emit(new FakeCmdlets.Item("item" + i, i * 10));
            }
        }
    }

    /** A careless cmdlet: it swallows every RuntimeException, so also the one that stops a preview (review C-15). */
    @CmdletInfo(name = "careless", preview = true, category = "Test", summary = "Swallows what stops it",
            examples = "careless")
    static final class Careless implements Cmdlet<Careless.Params, Void, FakeCmdlets.Item> {

        record Params() { }

        @Override
        public void begin(Params params, CmdletContext<FakeCmdlets.Item> context) {
            for (int i = 1; i <= 6; i++) {
                try {
                    context.emit(new FakeCmdlets.Item("item" + i, i * 10));
                } catch (RuntimeException _) {
                    // the stop of a preview is a CancellationException, which is a RuntimeException
                }
            }
        }
    }

    /** A blocking cmdlet, like {@code sort} and {@code group}: it emits only when its input has ended. */
    @CmdletInfo(name = "tally", preview = true, category = "Test", summary = "Counts what it receives",
            examples = "items | tally")
    static final class Tally implements Cmdlet<Tally.Params, Object, Integer> {

        record Params() { }

        @Override
        public void process(Params params, Object input, CmdletContext<Integer> context) {
            context.state(AtomicInteger.class, AtomicInteger::new).incrementAndGet();
        }

        @Override
        public void end(Params params, CmdletContext<Integer> context) {
            context.emit(context.state(AtomicInteger.class, AtomicInteger::new).get());
        }
    }

    /** A source that has three objects and then takes for ever, like a walk over a huge tree. */
    @CmdletInfo(name = "stall", preview = true, category = "Test", summary = "Produces three objects, then waits",
            examples = "stall")
    static final class Stall implements Cmdlet<Stall.Params, Void, FakeCmdlets.Item> {

        record Params() { }

        @Override
        public void begin(Params params, CmdletContext<FakeCmdlets.Item> context) throws InterruptedException {
            for (int i = 1; i <= 3; i++) {
                context.emit(new FakeCmdlets.Item("item" + i, i * 10));
            }
            Thread.sleep(Duration.ofMinutes(10));
        }
    }

    /** A source with nothing to say. */
    @CmdletInfo(name = "nothing", preview = true, category = "Test", summary = "Produces nothing",
            examples = "nothing")
    static final class Nothing implements Cmdlet<Nothing.Params, Void, FakeCmdlets.Item> {

        record Params() { }
    }

    public static List<Cmdlet<?, ?, ?>> all() {
        return List.of(new Items(), new Careless(), new Tally(), new Stall(), new Nothing());
    }
}
