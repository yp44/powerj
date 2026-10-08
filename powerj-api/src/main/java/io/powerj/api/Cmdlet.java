package io.powerj.api;

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
}
