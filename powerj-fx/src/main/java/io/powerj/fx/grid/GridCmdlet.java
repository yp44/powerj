package io.powerj.fx.grid;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.fx.Messages;
import io.powerj.fx.runtime.FxUnavailableException;
import io.powerj.fx.view.GridWindowOpener;

/**
 * {@code grid}: shows the objects received from the pipeline in a window (specification FR-77). For now the window only
 * tells how many objects were received.
 */
@CmdletInfo(name = "grid", category = "Graphical", summary = "Shows the objects in a window",
        examples = {"ls -r | grid", "ps | grid"})
public final class GridCmdlet implements Cmdlet<GridParams, Object, Void> {

    private final WindowOpener opener;

    /** Objects received, per execution. */
    private final Map<CmdletContext<?>, long[]> counts = Collections.synchronizedMap(new WeakHashMap<>());

    public GridCmdlet() {
        this(new GridWindowOpener());
    }

    GridCmdlet(WindowOpener opener) {
        this.opener = opener;
    }

    @Override
    public void begin(GridParams p, CmdletContext<Void> context) {
        if (!context.hasInput()) {
            throw new IllegalArgumentException(Messages.get("grid.noInput"));
        }
        counts.put(context, new long[1]);
    }

    @Override
    public void process(GridParams p, Object input, CmdletContext<Void> context) {
        counts.get(context)[0]++;
    }

    @Override
    public void end(GridParams p, CmdletContext<Void> context) {
        long received = counts.remove(context)[0];
        String text = Messages.get(received == 1 ? "grid.received.one" : "grid.received", received);
        try {
            opener.open(Messages.get("grid.title"), text);
        } catch (FxUnavailableException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }
}
