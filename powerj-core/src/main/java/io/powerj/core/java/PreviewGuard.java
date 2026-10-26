package io.powerj.core.java;

import java.lang.reflect.Executable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import io.powerj.core.i18n.Messages;

/**
 * Preview in progress (FR-66): bound to the threads that evaluate it, it stops the evaluation before anything that
 * could change something runs — native command, built-in command, cmdlet not declared {@code preview = true},
 * Java call outside {@link PreviewPolicy}. The check happens when the call is about to be made, so it also covers
 * the calls made from a lambda, a stream, a {@code $( … )} or a pipeline stage running in another thread.
 */
public final class PreviewGuard {

    private static final ScopedValue<PreviewGuard> CURRENT = ScopedValue.newInstance();

    /** Thrown to stop the evaluation; a {@link CancellationException} so that no cmdlet or block swallows it. */
    static final class Denied extends CancellationException {
        Denied(String reason) {
            super(reason);
        }
    }

    private volatile String reason;
    private final AtomicInteger errors = new AtomicInteger();

    /** Guard of the current thread, or {@code null} outside a preview. */
    public static PreviewGuard current() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    public static boolean active() {
        return CURRENT.isBound();
    }

    /** Runs {@code operation} with this guard bound to the current thread. */
    public <T, X extends Throwable> T call(ScopedValue.CallableOp<T, X> operation) throws X {
        return ScopedValue.where(CURRENT, this).call(operation);
    }

    /** {@code task}, which will run with the current guard if there is one (pipeline stage thread). */
    public static Runnable propagate(Runnable task) {
        PreviewGuard guard = current();
        return guard == null ? task : () -> ScopedValue.where(CURRENT, guard).run(task);
    }

    /** Stops the preview if one is in progress: {@code reason} says what would have run. */
    public static void deny(String reason) {
        PreviewGuard guard = current();
        if (guard != null) {
            guard.stop(reason);
        }
    }

    /** Java method or constructor about to be called. */
    static void checkCall(Executable executable) {
        PreviewGuard guard = current();
        if (guard != null && !PreviewPolicy.allows(executable)) {
            guard.stop(Messages.get("preview.off.java", PreviewPolicy.describe(executable)));
        }
    }

    private void stop(String why) {
        if (reason == null) {
            reason = why;
        }
        throw new Denied(why);
    }

    /** What stopped the preview, or {@code null}. */
    public String reason() {
        return reason;
    }

    /** A non-blocking error reported during the preview: counted, never displayed. */
    public void error(String ignored) {
        errors.incrementAndGet();
    }

    public int errors() {
        return errors.get();
    }
}
