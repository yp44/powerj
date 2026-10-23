package io.powerj.fx.grid;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.ScriptBlock;
import io.powerj.fx.Messages;
import io.powerj.fx.model.GridModel;
import io.powerj.fx.model.ObjectReader;
import io.powerj.fx.model.PropertyReader;
import io.powerj.fx.model.Reading;
import io.powerj.fx.runtime.FxUnavailableException;
import io.powerj.fx.view.GridWindowOpener;

/**
 * {@code grid}: shows the objects received from the pipeline as a table in a window (specification FR-77). The
 * objects are read as they arrive, the window opens when the input ends.
 */
@CmdletInfo(name = "grid", category = "Graphical", summary = "Shows the objects in a window",
        examples = {"ls -r | grid", "ps | grid"})
public final class GridCmdlet implements Cmdlet<GridParams, Object, Void> {

    private final WindowOpener opener;

    /** What one execution has gathered. */
    private record Execution(GridModel.Builder model, ObjectReader reader, Set<String> reported) {
    }

    private final Map<CmdletContext<?>, Execution> executions = Collections.synchronizedMap(new WeakHashMap<>());

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
        executions.put(context, new Execution(new GridModel.Builder(), new ObjectReader(new ScriptReader(context)),
                new HashSet<>()));
    }

    @Override
    public void process(GridParams p, Object input, CmdletContext<Void> context) {
        Execution execution = executions.get(context);
        Reading reading = execution.reader().read(input);
        // One report per column: a property that fails fails for every object.
        reading.errors().forEach((column, reason) -> {
            if (execution.reported().add(column)) {
                context.error(Messages.get("grid.property.unreadable", column, reason));
            }
        });
        execution.model().add(reading);
    }

    @Override
    public void end(GridParams p, CmdletContext<Void> context) {
        GridModel model = executions.remove(context).model().build();
        try {
            opener.open(Messages.get("grid.title"), model);
        } catch (FxUnavailableException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * Reads the properties the way the shell does ({@code $_.name}): it works for any object, including the records of
     * other third-party modules, whose packages are open to PowerJ only.
     */
    private static final class ScriptReader implements PropertyReader {

        private final CmdletContext<?> context;
        private final Map<String, ScriptBlock> blocks = new HashMap<>();

        ScriptReader(CmdletContext<?> context) {
            this.context = context;
        }

        @Override
        public Object read(Object target, String name) {
            return blocks.computeIfAbsent(name, n -> context.compile("$_." + n)).invoke(target);
        }
    }
}
