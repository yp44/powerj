package io.powerj.core.exec;

import java.util.List;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.ScriptBlock;

/** Cmdlets de test. */
final class FakeCmdlets {

    private FakeCmdlets() {
    }

    record Item(String name, int size) { }

    @CmdletInfo(name = "items", category = "Test", summary = "Produit des objets de test", examples = "items -n 2")
    static final class Items implements Cmdlet<Items.Params, Void, Item> {

        record Params(@Option(shortName = 'n', description = "Nombre d'objets") int count,
                      @Option(description = "Signale une erreur non bloquante") boolean fail) { }

        @Override
        public void begin(Params params, CmdletContext<Item> context) {
            for (int i = 1; i <= (params.count() == 0 ? 3 : params.count()); i++) {
                context.emit(new Item("item" + i, i * 10));
                if (params.fail() && i == 1) {
                    context.error("problème sur item1");
                }
            }
        }
    }

    /** Mini {@code where} : bloc ou texte compilé par le contexte. */
    @CmdletInfo(name = "filter", category = "Test", summary = "Filtre", examples = "items | filter { $_.size > 10 }")
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

    /** Compte les objets reçus. */
    @CmdletInfo(name = "count", category = "Test", summary = "Compte", examples = "items | count")
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

    /** Évalue un bloc avec {@code $_} = l'argument {@code --with}. */
    @CmdletInfo(name = "eval", category = "Test", summary = "Évalue un bloc", examples = "eval { 1 + 2 }")
    static final class Eval implements Cmdlet<Eval.Params, Void, Object> {

        record Params(@Option(position = 0) Object block, @Option(shortName = 'w') Object with) { }

        @Override
        public void begin(Params params, CmdletContext<Object> context) {
            context.emit(((ScriptBlock) params.block()).invoke(params.with()));
        }
    }

    /** Produit des nombres sans fin (arrêt par Ctrl+C ou par l'étape suivante). */
    @CmdletInfo(name = "infinite", category = "Test", summary = "Sans fin", examples = "infinite")
    static final class Infinite implements Cmdlet<Infinite.Params, Void, Long> {

        record Params() { }

        @Override
        public void begin(Params params, CmdletContext<Long> context) {
            for (long i = 0; ; i++) {
                context.emit(i);
            }
        }
    }

    static List<Cmdlet<?, ?, ?>> all() {
        return List.of(new Items(), new Filter(), new Count(), new Eval(), new Infinite());
    }
}
