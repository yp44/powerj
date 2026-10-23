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
import io.powerj.fx.model.LiveTable;
import io.powerj.fx.model.ObjectReader;
import io.powerj.fx.model.PropertyReader;
import io.powerj.fx.model.Reading;
import io.powerj.fx.runtime.FxUnavailableException;
import io.powerj.fx.view.GridWindowOpener;

/**
 * {@code grid}: shows the objects received from the pipeline as a table in a window (specification FR-77). The window
 * opens at the first object and fills while the pipeline goes on; at most {@code --max-rows} rows are read.
 */
@CmdletInfo(name = "grid", category = "Graphical", summary = "Shows the objects in a window",
        examples = {"ls -r | grid", "ps | grid", "ls -r / | grid --max-rows 5000"})
public final class GridCmdlet implements Cmdlet<GridParams, Object, Void> {

    /** Rows that can wait for the window before the pipeline has to wait. */
    static final int LIVE_CAPACITY = 20_000;

    private final WindowOpener opener;

    /** What one execution has gathered. */
    private static final class Execution {
        final ObjectReader reader;
        final int limit;
        final Set<String> reported = new HashSet<>();
        LiveTable live;
        int count;
        boolean truncated;
        boolean stopped;

        Execution(ObjectReader reader, int limit) {
            this.reader = reader;
            this.limit = limit;
        }
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
        if (p.limit() <= 0) {
            throw new IllegalArgumentException(Messages.get("grid.maxRows.invalid", p.limit()));
        }
        executions.put(context, new Execution(new ObjectReader(new ScriptReader(context)), p.limit()));
    }

    @Override
    public void process(GridParams p, Object input, CmdletContext<Void> context) throws InterruptedException {
        Execution execution = executions.get(context);
        if (execution.stopped) {
            return;
        }
        if (execution.count >= execution.limit) {
            // One more than the limit has arrived: the table is not complete.
            execution.truncated = true;
            stop(execution, context);
            return;
        }
        Reading reading = execution.reader.read(input);
        // One report per column: a property that fails fails for every object.
        reading.errors().forEach((column, reason) -> {
            if (execution.reported.add(column)) {
                context.error(Messages.get("grid.property.unreadable", column, reason));
            }
        });
        if (execution.live == null) {
            execution.live = new LiveTable(LIVE_CAPACITY);
            execution.live.offer(reading);
            open(execution.live);
        } else if (!execution.live.offer(reading)) {
            stop(execution, context); // the window was closed: nobody to read for
            return;
        }
        execution.count++;
    }

    @Override
    public void end(GridParams p, CmdletContext<Void> context) {
        Execution execution = executions.remove(context);
        if (execution.live == null) {
            execution.live = new LiveTable(LIVE_CAPACITY); // nothing arrived: the window says so
            open(execution.live);
        }
        execution.live.finish(execution.truncated);
    }

    private static void stop(Execution execution, CmdletContext<Void> context) {
        execution.stopped = true;
        context.stopInput();
    }

    private void open(LiveTable live) {
        try {
            opener.open(Messages.get("grid.title"), live);
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
