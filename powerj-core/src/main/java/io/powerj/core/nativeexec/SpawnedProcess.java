package io.powerj.core.nativeexec;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.powerj.fs.Host;

/**
 * A background process started through the FFM API ({@link PosixSpawn}, {@link WindowsSpawn}), seen as a {@link Process}
 * so that the jobs ({@code Jobs}) and {@code kill} treat it as any other. It has no streams: a background process reads the
 * null device and writes to the terminal or to a file. Its exit code comes from the thread that waits for it.
 */
final class SpawnedProcess extends Process {

    /** The exit code of a process whose status could not be read (it cannot happen unless something else reaped it). */
    static final int UNKNOWN_EXIT = -1;

    private final int pid;
    private final CompletableFuture<Integer> exit;
    private final Runnable terminate;
    private final Runnable kill;
    private final CompletableFuture<Process> onExit;
    /**
     * The handle of the JDK, taken while the process runs. It knows the start time of the process, so it never designates
     * another process that would get the same pid, and it stays valid (not alive) after the exit. {@code null} if the
     * process had already ended.
     */
    private final ProcessHandle handle;
    /** The process leads a process group that is signalled as a whole: see {@link #destroy()}. */
    private final boolean groupLeader;

    /**
     * @param terminate asks the process to stop ({@code SIGTERM}; on Windows there is no such thing and it is a kill)
     * @param kill      stops it for good ({@code SIGKILL}, {@code TerminateProcess})
     */
    SpawnedProcess(int pid, CompletableFuture<Integer> exit, Runnable terminate, Runnable kill) {
        this(pid, exit, terminate, kill, false);
    }

    /**
     * @param groupLeader {@code true} if {@code terminate} and {@code kill} signal the process group that this process leads:
     *                    they are then sent even after it has ended, since the processes it started may still be in the group
     */
    SpawnedProcess(int pid, CompletableFuture<Integer> exit, Runnable terminate, Runnable kill, boolean groupLeader) {
        this.groupLeader = groupLeader;
        this.pid = pid;
        this.exit = exit;
        this.terminate = terminate;
        this.kill = kill;
        this.onExit = exit.handle((_, _) -> this);
        this.handle = ProcessHandle.of(pid).orElse(null);
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

    /**
     * Nothing is sent to a single process once it has been reaped: its pid may belong to another by now. The leader of a job's
     * group is the exception: a script that forks and returns (`sh -c 'sleep 1000 & exit' &`) leaves its children in the group,
     * and the group is signalled as long as it may have members. The number of a group is not given to another process while
     * the group has a member; once it is empty the signal finds nothing ({@code ESRCH}, ignored), unless in the meantime the
     * number was given to a new process that leads a group of its own, which the system does only after the pids have wrapped
     * around (review of #173).
     */
    @Override
    public void destroy() {
        if (isAlive() || groupLeader) {
            terminate.run();
        }
    }

    @Override
    public Process destroyForcibly() {
        if (isAlive() || groupLeader) {
            kill.run();
        }
        return this;
    }

    /** The handle of the process, still valid once it has ended. */
    @Override
    public ProcessHandle toHandle() {
        if (handle == null) {
            throw new UnsupportedOperationException("process " + pid + " had ended before its handle could be taken");
        }
        return handle;
    }

    /** Windows has no gentle stop: {@link #destroy()} is a {@code TerminateProcess}, as for the JDK's own processes. */
    @Override
    public boolean supportsNormalTermination() {
        return !Host.windows();
    }
}
