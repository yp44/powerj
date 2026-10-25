package io.powerj.api;

import java.util.List;

/**
 * PowerJ command written in Java. The shell builds the parameter record {@code P} from the
 * options typed ({@link Option}), then calls {@link #begin}, {@link #process} for each object received
 * from the pipeline, and {@link #end}.
 *
 * @param <P> parameter record
 * @param <I> type of the objects received from the pipeline ({@link Void} if the cmdlet reads none)
 * @param <O> type of the produced objects; a {@code record} is recommended (table display,
 *            property completion)
 */
public interface Cmdlet<P extends Record, I, O> {

    /** Called once, before any object is received. A cmdlet that produces objects often does so here. */
    default void begin(P params, CmdletContext<O> context) throws Exception {
    }

    /** Called for each object received from the pipeline. */
    default void process(P params, I input, CmdletContext<O> context) throws Exception {
    }

    /** Called once, after the last object received. */
    default void end(P params, CmdletContext<O> context) throws Exception {
    }

    /**
     * Preview mode (FR-66), for a cmdlet declared with {@link CmdletInfo#preview()}: {@code false} if these
     * parameters would change something ({@code env --set}), and the preview stops instead of running the cmdlet.
     * Called after the options are bound, before {@link #begin}.
     *
     * @deprecated implement {@link Previewable} instead: the contract of a cmdlet stays {@code begin}, {@code process}
     *             and {@code end}, and what is optional is a separate interface. Still honoured when the cmdlet does
     *             not implement it.
     */
    @Deprecated(since = "0.2", forRemoval = true)
    default boolean previewable(P params) {
        return true;
    }

    /**
     * Names of the columns of the {@link Row}s this cmdlet emits, read from the text of its arguments while the user
     * types, for Tab completion in the next stage ({@code ls | select name kb={ … } | where { $_.kb > 1 }}).
     * Called on text that may be incomplete or invalid: it must not fail. Default: none known.
     *
     * @param arguments text following the command name in the stage, as typed
     * @deprecated implement {@link ColumnProvider} instead. Still honoured when the cmdlet does not implement it.
     */
    @Deprecated(since = "0.2", forRemoval = true)
    default List<String> columns(String arguments) {
        return List.of();
    }
}
