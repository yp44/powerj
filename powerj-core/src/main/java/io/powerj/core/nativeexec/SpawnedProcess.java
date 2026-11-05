package io.powerj.core.nativeexec;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A process started by {@link PosixSpawn}, seen as a {@link Process} so that the jobs ({@code Jobs}) and {@code kill} treat
 * it as any other. It has no streams: a background process reads the null device and writes to the terminal or to a file.
 * Its exit code comes from the {@code waitpid} thread of {@link PosixSpawn}.
 */
final class SpawnedProcess extends Process {

    /** The exit code of a process whose status could not be read (it cannot happen unless something else reaped it). */
    static final int UNKNOWN_EXIT = -1;

    private final int pid;
    private final CompletableFuture<Integer> exit;
    private final CompletableFuture<Process> onExit;

    SpawnedProcess(PosixSpawn.Spawned spawned) {
        this.pid = spawned.pid();
        this.exit = spawned.exit();
        this.onExit = exit.handle((_, _) -> this);
    }

    @Override
    public long pid() {
        return pid;
    }

    @Override
    public OutputStream getOutputStream() {
        return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
        return InputStream.nullInputStream();
    }

    @Override
    public InputStream getErrorStream() {
        return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() throws InterruptedException {
        try {
            return exit.get();
        } catch (ExecutionException _) {
            return UNKNOWN_EXIT;
        }
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        try {
            exit.get(timeout, unit);
        } catch (ExecutionException _) {
            return true;
        } catch (TimeoutException _) {
            return false;
        }
        return true;
    }

    @Override
    public int exitValue() {
        if (!exit.isDone()) {
            throw new IllegalThreadStateException("process hasn't exited");
        }
        return exit.isCompletedExceptionally() ? UNKNOWN_EXIT : exit.join();
    }

    @Override
    public boolean isAlive() {
        return !exit.isDone();
    }

    @Override
    public CompletableFuture<Process> onExit() {
        return onExit;
    }

    /** {@code SIGTERM}. Nothing is sent once the process has been reaped: its pid may belong to another by now. */
    @Override
    public void destroy() {
        if (isAlive()) {
            PosixSpawn.kill(pid, PosixSpawn.SIGTERM);
        }
    }

    /** {@code SIGKILL}. */
    @Override
    public Process destroyForcibly() {
        if (isAlive()) {
            PosixSpawn.kill(pid, PosixSpawn.SIGKILL);
        }
        return this;
    }

    @Override
    public ProcessHandle toHandle() {
        return ProcessHandle.of(pid).orElseThrow(() -> new IllegalStateException("process " + pid + " has ended"));
    }
}
