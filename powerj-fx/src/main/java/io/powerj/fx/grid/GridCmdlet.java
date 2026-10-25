package io.powerj.fx.grid;

import java.util.Locale;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.ScriptBlock;
import io.powerj.fx.Messages;
import io.powerj.fx.model.ColumnChoice;
import io.powerj.fx.model.WindowSettings;
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
@CmdletInfo(name = "grid", category = "Graphical", passThrough = true, summary = "Shows the objects in a window",
        examples = {"ls -r | grid", "ps | grid --columns pid,name", "ls -r / | grid --max-rows 5000",
                "ls | grid --title Files --wait", "ls -r | grid --pass-thru | rm"})
public final class GridCmdlet implements Cmdlet<GridParams, Object, Object> {

    /** Rows that can wait for the window before the pipeline has to wait. */
    static final int LIVE_CAPACITY = 20_000;

    private final WindowOpener opener;

    /**
     * What one execution has gathered. Closing it, which the shell does when the command is over, closes the window of
     * a command that did not get as far as handing it over (Ctrl+C, an error): once {@link GridCmdlet#end} has, the
     * window lives on its own.
     */
    private static final class Execution implements AutoCloseable {
        final ObjectReader reader;
        final int limit;
        final WindowOptions options;
        final Set<String> reported = new HashSet<>();
        /** Lower-case names of the columns that have arrived, to tell the ones asked for that never did. */
        final Set<String> seenColumns = new HashSet<>();
        LiveTable live;
        int count;
        boolean truncated;
        boolean stopped;
        /** The table is complete and the window no longer depends on the command. */
        boolean handedOver;

        Execution(ObjectReader reader, int limit, WindowOptions options) {
            this.reader = reader;
            this.limit = limit;
            this.options = options;
        }

        @Override
        public void close() {
            if (!handedOver && live != null) {
                live.close();
            }
        }
    }

    /** The execution that {@code begin} made. */
    private static Execution execution(CmdletContext<?> context) {
        return context.state(Execution.class, () -> {
            throw new IllegalStateException("begin was not called: no grid execution");
        });
    }

    public GridCmdlet() {
        this(new GridWindowOpener());
    }

    GridCmdlet(WindowOpener opener) {
        this.opener = opener;
    }

    @Override
    public void begin(GridParams p, CmdletContext<Object> context) {
        if (!context.hasInput()) {
            throw new IllegalArgumentException(Messages.get("grid.noInput"));
        }
        if (p.single() && !p.passThru()) {
            throw new IllegalArgumentException(Messages.get("grid.single.needsPassThru"));
        }
        if (p.limit() <= 0) {
            throw new IllegalArgumentException(Messages.get("grid.maxRows.invalid", p.limit()));
        }
        ColumnChoice columns = ColumnChoice.ALL;
        if (p.columns() != null) {
            try {
                columns = ColumnChoice.parse(p.columns());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(Messages.get("grid.columns.invalid", p.columns()), e);
            }
        }
        String title = p.title() == null || p.title().isBlank() ? Messages.get("grid.title") : p.title();
        var settings = context.configDirectory().map(dir -> dir.resolve(WindowSettings.FILE_NAME)).orElse(null);
        ColumnChoice chosen = columns;
        context.state(Execution.class, () -> new Execution(new ObjectReader(new ScriptReader(context), p.raw()),
                p.limit(), new WindowOptions(title, chosen, settings, p.passThru(), p.single())));
    }

    @Override
    public void process(GridParams p, Object input, CmdletContext<Object> context) throws InterruptedException {
        Execution execution = execution(context);
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
        reading.columns().forEach(column -> execution.seenColumns.add(column.name().toLowerCase(Locale.ROOT)));
        // One report per column: a property that fails fails for every object.
        reading.errors().forEach((column, reason) -> {
            if (execution.reported.add(column)) {
                context.error(Messages.get("grid.property.unreadable", column, reason));
            }
        });
        if (execution.live == null) {
            execution.live = new LiveTable(LIVE_CAPACITY);
            execution.live.offer(reading);
            open(execution);
        } else if (!execution.live.offer(reading)) {
            stop(execution, context); // the window was closed: nobody to read for
            return;
        }
        execution.count++;
    }

    @Override
    public void end(GridParams p, CmdletContext<Object> context) throws InterruptedException {
        Execution execution = execution(context);
        if (execution.live == null) {
            execution.live = new LiveTable(LIVE_CAPACITY); // nothing arrived: the window says so
            open(execution);
        }
        execution.live.finish(execution.truncated);
        execution.handedOver = true;
        List<String> missing = execution.options.columns().missing(execution.seenColumns);
        if (!missing.isEmpty()) {
            context.error(Messages.get("grid.columns.unknown", String.join(", ", missing)));
        }
        if (p.waitClose() || p.passThru()) {
            try {
                execution.live.awaitClosed();
            } catch (InterruptedException e) {
                execution.live.close(); // Ctrl+C: the window goes with the command, nothing goes on
                throw e;
            }
        }
        if (p.passThru()) {
            // OK gave the ticked rows (the ones a filter hides too); any other way to close gives nothing.
            execution.live.accepted().ifPresent(rows -> rows.forEach(row -> context.emit(row.source())));
        }
    }

    private static void stop(Execution execution, CmdletContext<Object> context) {
        execution.stopped = true;
        context.stopInput();
    }

    private void open(Execution execution) {
        try {
            opener.open(execution.options, execution.live);
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
