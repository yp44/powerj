package io.powerj.api;

/**
 * Capability of a cmdlet declared with {@link CmdletInfo#preview()}: it says, for the parameters it is given, whether it
 * changes something. Preview mode (FR-66) runs the cmdlet while the user types only if it does not.
 * <p>
 * A cmdlet that has no such parameters ({@code ls}) does not implement this: {@code preview = true} is enough. One
 * whose options can change something ({@code env --set}) implements it and refuses those.
 *
 * @param <P> parameter record of the cmdlet
 */
public interface Previewable<P extends Record> {

    /**
     * {@code false} if these parameters would change something ({@code env --set}): the preview then stops instead of
     * running the cmdlet. Called after the options are bound, before {@link Cmdlet#begin}.
     */
    boolean canPreview(P params);
}
