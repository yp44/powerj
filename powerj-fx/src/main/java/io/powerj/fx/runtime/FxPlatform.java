package io.powerj.fx.runtime;

/** What {@link FxRuntime} needs from JavaFX, so that its logic is tested without a display. */
public interface FxPlatform {

    /**
     * Starts the toolkit and returns once it runs, with the implicit exit turned off (closing the last window must not
     * stop it: it cannot be restarted in the same JVM). Returns at once if it already runs.
     *
     * @throws RuntimeException or {@link LinkageError} if the toolkit cannot start (no display, missing library)
     */
    void startup();

    /** Runs {@code action} later on the toolkit thread. */
    void runLater(Runnable action);
}
