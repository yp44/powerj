package io.powerj.cmdlets;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.UsageException;
import io.powerj.fs.Host;

/**
 * {@code kill}: stops processes given by pid or received from the pipeline (specification FR-74), with the JDK
 * ({@code destroy()}, or {@code destroyForcibly()} with {@code -f}). The JDK forbids stopping the current process.
 * <p>
 * On Unix a normal stop ({@code SIGTERM}) is followed by {@code SIGCONT}: a process that the terminal has stopped (a background
 * job that reads it, or writes to it after {@code stty tostop}) keeps {@code SIGTERM} pending until it is continued.
 */
@CmdletInfo(name = "kill", category = "System", summary = "Stops processes",
        examples = {"kill 1234", "ps notepad | kill", "ps notepad | kill -n", "ps java | kill -f -w -v"})
public final class Kill implements Cmdlet<Kill.Params, Object, ProcessEntry> {

    /** Parameters of {@code kill}. */
    public record Params(
            @Option(position = 0, description = "Numbers of the processes to stop; default: the pipeline objects")
            List<Object> pids,
            @Option(shortName = 'f', description = "Forcible stop (SIGKILL on Unix); on Windows every stop is forcible")
            boolean force,
            @Option(shortName = 'w', longName = "wait", description = "Waits until each process has ended")
            boolean await,
            @Option(shortName = 'v', description = "Emits each process stopped")
            boolean verbose,
            @Option(shortName = 'n', description = "Stops nothing: shows the processes that would be stopped")
            boolean dryRun) {
    }

    @Override
    public void begin(Params p, CmdletContext<ProcessEntry> context) {
        List<Object> arguments = p.pids() == null ? List.of() : p.pids();
        if (arguments.isEmpty() && !context.hasInput()) {
            throw new UsageException(Messages.get("kill.pid.expected"));
        }
        long[] pids = new long[arguments.size()];
        for (int i = 0; i < pids.length; i++) {
            pids[i] = pid(arguments.get(i));
        }
        for (long pid : pids) {
            kill(p, pid, context);
        }
    }

    /** Pid of an argument. */
    private static long pid(Object argument) {
        if (argument instanceof Number number && !(argument instanceof Double) && !(argument instanceof Float)) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(argument).strip());
        } catch (NumberFormatException e) {
            throw new UsageException(Messages.get("kill.pid.invalid", argument));
        }
    }

    @Override
    public void process(Params p, Object input, CmdletContext<ProcessEntry> context) {
        switch (input) {
            case ProcessEntry entry -> kill(p, entry.pid(), context);
            case ProcessHandle handle -> kill(p, handle.pid(), context);
            case Process process -> kill(p, process.pid(), context);
            case Long _, Integer _, Short _ -> kill(p, ((Number) input).longValue(), context);
            case Record record when pidOf(record) != null -> kill(p, pidOf(record), context);
            case null, default -> context.error(Messages.get("kill.not.process", Texts.abbreviate(input)));
        }
    }

    /** Component {@code pid} of a record ({@code jobs | kill}, FR-75), or {@code null} if it has none. */
    private static Long pidOf(Record record) {
        RecordShape shape = RecordShape.of(record.getClass());
        int index = shape.indexOf("pid");
        if (index < 0 || (shape.accessor(index).getReturnType() != long.class
                && shape.accessor(index).getReturnType() != Long.class)) {
            return null;
        }
        try {
            return (Long) shape.accessor(index).invoke(record);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static void kill(Params p, long pid, CmdletContext<ProcessEntry> context) {
        Optional<ProcessHandle> found = ProcessHandle.of(pid);
        if (found.isEmpty() || !found.get().isAlive()) {
            context.error(Messages.get("kill.no.process", pid));
            return;
        }
        ProcessHandle process = found.get();
        ProcessEntry entry = ProcessEntry.of(process);
        if (p.dryRun()) {
            context.emit(entry);
            return;
        }
        boolean requested;
        try {
            requested = p.force() ? process.destroyForcibly() : process.destroy();
        } catch (IllegalStateException e) { // the JDK forbids stopping the current process
            context.error(Messages.get("kill.self"));
            return;
        }
        if (!requested) {
            context.error(Messages.get("kill.failed", pid, entry.name() == null ? "?" : entry.name()));
            return;
        }
        if (!p.force() && !Host.windows()) {
            resume(pid);
        }
        if (p.await()) {
            try {
                process.onExit().get();
                while (process.isAlive()) { // Windows: the end may be seen a moment after onExit completes
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException();
            } catch (ExecutionException e) {
                // onExit does not fail
            }
        }
        if (p.verbose()) {
            context.emit(entry);
        }
    }

    /**
     * Continues a process that was just asked to stop, so that its {@code SIGTERM} is delivered even if the terminal had
     * stopped it. Harmless to a process that runs. Java has no way to send {@code SIGCONT}: the native {@code kill} does it;
     * where it is missing the process stays as it is (and {@code kill -f} still ends it).
     */
    private static void resume(long pid) {
        try {
            Process kill = new ProcessBuilder("kill", "-CONT", Long.toString(pid))
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!kill.waitFor(5, TimeUnit.SECONDS)) {
                kill.destroyForcibly();
            }
        } catch (IOException e) {
            // no kill program: nothing more can be done here
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        }
    }
}
