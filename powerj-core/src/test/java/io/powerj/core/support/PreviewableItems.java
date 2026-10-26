package io.powerj.core.support;

import java.util.List;

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

    public static List<Cmdlet<?, ?, ?>> all() {
        return List.of(new Items(), new Careless());
    }
}
