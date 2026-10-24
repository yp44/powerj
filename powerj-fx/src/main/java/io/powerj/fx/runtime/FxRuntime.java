package io.powerj.fx.runtime;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.powerj.fx.Messages;

/**
 * The JavaFX toolkit of the shell: started once, on first use, and kept for the life of the JVM. A cmdlet runs in a
 * virtual thread; everything that touches a window goes through {@link #runAndWait}.
 */
public final class FxRuntime {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final FxPlatform platform;
    private final Duration timeout;
    private boolean started;
    private FxUnavailableException failure;

    public FxRuntime(FxPlatform platform) {
        this(platform, DEFAULT_TIMEOUT);
    }

    FxRuntime(FxPlatform platform, Duration timeout) {
        this.platform = platform;
        this.timeout = timeout;
    }

    /**
     * Starts the toolkit if it is not running yet. A failed start is remembered and not tried again: JavaFX cannot
     * start a second time in the JVM after a failure (the second call to {@code Platform.startup} never returns).
     */
    public synchronized void ensureStarted() {
        if (started) {
            return;
        }
        if (failure != null) {
            throw new FxUnavailableException(Messages.get("fx.unavailable.again", failure.getMessage()), failure);
        }
        try {
            platform.startup();
        } catch (RuntimeException | LinkageError e) {
            failure = new FxUnavailableException(Messages.get("fx.unavailable", describe(e)), e);
            throw failure;
        }
        started = true;
    }

    /**
     * Runs {@code action} on the toolkit thread and waits for it to end.
     *
     * @throws FxUnavailableException if the toolkit cannot start, or does not answer in time
     * @throws CancellationException if the calling thread is interrupted (Ctrl+C)
     */
    public void runAndWait(Runnable action) {
        ensureStarted();
        CompletableFuture<Void> done = new CompletableFuture<>();
        platform.runLater(() -> {
            try {
                action.run();
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            done.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } catch (TimeoutException e) {
            throw new FxUnavailableException(Messages.get("fx.timeout", timeout.toSeconds()), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
