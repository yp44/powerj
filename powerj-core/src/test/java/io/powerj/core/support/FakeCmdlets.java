package io.powerj.core.support;

import java.util.List;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.ScriptBlock;

/** Test cmdlets. */
public final class FakeCmdlets {

    private FakeCmdlets() {
    }

    public record Item(String name, int size) { }

    @CmdletInfo(name = "items", category = "Test", summary = "Produces test objects", examples = "items -n 2")
    static final class Items implements Cmdlet<Items.Params, Void, Item> {

        record Params(@Option(shortName = 'n', description = "Number of objects") int count,
                      @Option(description = "Reports a non-blocking error") boolean fail) { }

        @Override
        public void begin(Params params, CmdletContext<Item> context) {
            for (int i = 1; i <= (params.count() == 0 ? 3 : params.count()); i++) {
                context.emit(new Item("item" + i, i * 10));
                if (params.fail() && i == 1) {
                    context.error("problem with item1");
                }
            }
        }
    }

    /** Mini {@code where}: block or text compiled by the context. */
    @CmdletInfo(name = "filter", category = "Test", summary = "Filters", examples = "items | filter { $_.size > 10 }")
    static final class Filter implements Cmdlet<Filter.Params, Object, Object> {

        record Params(@Option(position = 0) Object condition) { }

        @Override
        public void process(Params params, Object input, CmdletContext<Object> context) {
            ScriptBlock block = params.condition() instanceof ScriptBlock b ? b
                    : context.compile(String.valueOf(params.condition()));
            try {
                if (block.test(input)) {
                    context.emit(input);
                }
            } catch (java.util.concurrent.CancellationException e) {
                throw e;
            } catch (RuntimeException e) {
                context.error(e.getMessage());
            }
        }
    }

    /** Short form declared by the cmdlet itself, as {@code where} does: {@code items | pick size > 10}. */
    @CmdletInfo(name = "pick", category = "Test", summary = "Picks by comparison", examples = "items | pick size > 10",
            comparison = true, passThrough = true)
    static final class Pick implements Cmdlet<Pick.Params, Object, Object> {

        record Params(@Option(position = 0) List<Object> words) { }

        @Override
        public void process(Params params, Object input, CmdletContext<Object> context) {
            List<Object> w = params.words();
            if (context.compile("$_." + w.get(0) + " " + w.get(1) + " " + w.get(2)).test(input)) {
                context.emit(input);
            }
        }
    }

    /** Counts the objects received. */
    @CmdletInfo(name = "count", category = "Test", summary = "Counts", examples = "items | count")
    static final class Count implements Cmdlet<Count.Params, Object, Long> {

        record Params() { }

        private final java.util.Map<Params, long[]> counts = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void begin(Params params, CmdletContext<Long> context) {
            counts.put(params, new long[1]);
        }

        @Override
        public void process(Params params, Object input, CmdletContext<Long> context) {
            counts.get(params)[0]++;
        }

        @Override
        public void end(Params params, CmdletContext<Long> context) {
            context.emit(counts.remove(params)[0]);
        }
    }

    /** Evaluates a block with {@code $_} = the {@code --with} argument. */
    @CmdletInfo(name = "eval", category = "Test", summary = "Evaluates a block", examples = "eval { 1 + 2 }")
    static final class Eval implements Cmdlet<Eval.Params, Void, Object> {

        record Params(@Option(position = 0) Object block, @Option(shortName = 'w') Object with) { }

        @Override
        public void begin(Params params, CmdletContext<Object> context) {
            context.emit(((ScriptBlock) params.block()).invoke(params.with()));
        }
    }

    /** Produces numbers endlessly (stopped by Ctrl+C or by the next stage). */
    @CmdletInfo(name = "infinite", category = "Test", summary = "Endless", examples = "infinite")
    public static final class Infinite implements Cmdlet<Infinite.Params, Void, Long> {

        record Params() { }

        /** Number of {@code infinite} stages running: a test can tell that Ctrl+C stopped the producer (a virtual thread). */
        public static final java.util.concurrent.atomic.AtomicInteger RUNNING = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public void begin(Params params, CmdletContext<Long> context) {
            RUNNING.incrementAndGet();
            try {
                for (long i = 0; ; i++) {
                    context.emit(i);
                }
            } finally {
                RUNNING.decrementAndGet();
            }
        }
    }

    /** Passes the objects through and holds a resource that the shell must close, whatever happens to the command. */
    @CmdletInfo(name = "hold", category = "Test", summary = "Holds a resource", examples = "items | hold")
    public static final class Hold implements Cmdlet<Hold.Params, Object, Object> {

        record Params() { }

        /** Resources held and not yet closed. */
        public static final java.util.concurrent.atomic.AtomicInteger OPEN = new java.util.concurrent.atomic.AtomicInteger();

        private static final class Resource implements AutoCloseable {
            Resource() {
                OPEN.incrementAndGet();
            }

            @Override
            public void close() {
                OPEN.decrementAndGet();
            }
        }

        @Override
        public void begin(Params params, CmdletContext<Object> context) {
            context.state(Resource.class, Resource::new);
        }

        @Override
        public void process(Params params, Object input, CmdletContext<Object> context) {
            context.emit(input);
        }
    }

    /** Mini {@code map}: applies a block or a method reference to each object. */
    @CmdletInfo(name = "eval-each", category = "Test", summary = "Transforms", examples = "items | eval-each Item::name")
    static final class EvalEach implements Cmdlet<EvalEach.Params, Object, Object> {

        record Params(@Option(position = 0) Object function) { }

        @Override
        public void process(Params params, Object input, CmdletContext<Object> context) {
            try {
                context.emit(((ScriptBlock) params.function()).invoke(input));
            } catch (java.util.concurrent.CancellationException e) {
                throw e;
            } catch (RuntimeException e) {
                context.error(e.getMessage());
            }
        }
    }

    /** Mini {@code first}: emits the first n objects, stops its input (FR-68b), then emits "done". */
    @CmdletInfo(name = "take", category = "Test", summary = "Takes the first objects", examples = "infinite | take 3")
    static final class Take implements Cmdlet<Take.Params, Object, Object> {

        record Params(@Option(position = 0) Integer count) { }

        private final java.util.Map<CmdletContext<?>, int[]> taken = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void begin(Params params, CmdletContext<Object> context) {
            taken.put(context, new int[1]);
        }

        @Override
        public void process(Params params, Object input, CmdletContext<Object> context) {
            context.emit(input);
            if (++taken.get(context)[0] == params.count()) {
                context.stopInput();
            }
        }

        @Override
        public void end(Params params, CmdletContext<Object> context) {
            taken.remove(context);
            context.emit("done");
        }
    }

    public static List<Cmdlet<?, ?, ?>> all() {
        return List.of(new Items(), new Filter(), new Pick(), new Count(), new Eval(), new Infinite(), new EvalEach(), new Take(),
                new Hold());
    }
}
