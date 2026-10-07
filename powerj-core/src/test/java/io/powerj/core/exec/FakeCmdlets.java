package io.powerj.core.exec;

import java.util.List;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;

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

    static List<Cmdlet<?, ?, ?>> all() {
        return List.of(new Items());
    }
}
