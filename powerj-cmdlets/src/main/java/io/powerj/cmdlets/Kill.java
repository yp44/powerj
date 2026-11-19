package io.powerj.cmdlets;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.UsageException;
import io.powerj.fs.Host;
import io.powerj.fs.ProcessSignals;
import io.powerj.text.Formats;
import io.powerj.text.RecordView;

/**
 * {@code kill}: stops processes given by pid or received from the pipeline (specification FR-74), with the JDK
 * ({@code destroy()}, or {@code destroyForcibly()} with {@code -f}). The JDK forbids stopping the current process.
 * <p>
 * On Unix a normal stop ({@code SIGTERM}) is followed by {@code SIGCONT} ({@link ProcessSignals}): a process that the terminal
 * has stopped (a background job that reads it, or writes to it after {@code stty tostop}) keeps {@code SIGTERM} pending until
 * it is continued.
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
            boolean dryRun,
            @Option(description = "Also stops the processes PowerJ runs inside (its parents, the terminal) and the init process")
            boolean allowProtected,
            @Option(description = "With --wait: gives up on a process that has not ended after this long (default 10 s)")
            Duration timeout) {
    }

    @Override
    public void begin(Params p, CmdletContext<ProcessEntry> context) {
        List<Object> arguments = p.pids() == null ? List.of() : p.pids();
        if (arguments.isEmpty() && !context.hasInput()) {
            throw new UsageException(Messages.get("kill.pid.expected"));
        }
        if (p.timeout() != null && (p.timeout().isNegative() || p.timeout().isZero())) {
            throw new UsageException(Messages.get("kill.timeout.invalid", Formats.duration(p.timeout())));
        }
        long[] pids = new long[arguments.size()];
        for (int i = 0; i < pids.length; i++) {
            pids[i] = pid(arguments.get(i));
        }
        for (long pid : pids) {
            kill(p, pid, null, context);
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
            case ProcessEntry entry -> kill(p, entry.pid(), entry.start(), context);
            case ProcessHandle handle -> kill(p, handle.pid(), null, context);
            case Process process -> kill(p, process.pid(), null, context);
            case Long _, Integer _, Short _ -> kill(p, ((Number) input).longValue(), null, context);
            case Record record -> {
                Long pid = pidOf(record, context);
                if (pid == null) {
                    notAProcess(input, context);
                } else {
                    kill(p, pid, null, context);
                }
            }
            case null, default -> notAProcess(input, context);
        }
    }

    private static void notAProcess(Object input, CmdletContext<ProcessEntry> context) {
        context.error(Messages.get("kill.not.process", Texts.abbreviate(input)));
    }

    /**
     * Component {@code pid} of a record ({@code jobs | kill}, FR-75), or {@code null} if it has none. The name and the type
     * of the component come from the record, its value from the context: a record of another module (the {@code Job} of
     * {@code jobs}) cannot be read from here by reflection (#130).
     */
    private static Long pidOf(Record record, CmdletContext<?> context) {
        RecordView.Component pid = RecordView.of(record.getClass()).component("pid");
        if (pid == null || (pid.recordComponent().getType() != long.class
                && pid.recordComponent().getType() != Long.class)) {
            return null;
        }
        return context.tryReadProperty(record, "pid").value() instanceof Long value ? value : null;
    }

    /** Time that {@code --wait} gives a process to end when {@code --timeout} does not say. */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Stops a process.
     *
     * @param start the start time of the process that was meant, when the pid comes from a {@link ProcessEntry}: a pid is
     *              reused, and a stored entry must not stop another process (audit v2, R-18)
     */
    private static void kill(Params p, long pid, Instant start, CmdletContext<ProcessEntry> context) {
        Optional<ProcessHandle> found = ProcessHandle.of(pid);
        if (found.isEmpty() || !found.get().isAlive() || reused(found.get(), start)) {
            context.error(Messages.get("kill.no.process", pid));
            return;
        }
        ProcessHandle process = found.get();
        ProcessEntry entry = ProcessEntry.of(process);
        if (!p.allowProtected()) {
            Optional<String> refusal = refusal(process, entry);
            if (refusal.isPresent()) {
                context.error(refusal.get());
                return;
            }
        }
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
        if (!p.force()) {
            ProcessSignals.resume(pid); // a process stopped by the terminal keeps SIGTERM pending until it is continued
        }
        if (p.await() && !awaited(p, process, entry, context)) {
            return;
        }
        if (p.verbose()) {
            context.emit(entry);
        }
    }

    /** Is this process another one than the one a stored entry described? Both start times must be known. */
    private static boolean reused(ProcessHandle process, Instant start) {
        if (start == null) {
            return false;
        }
        Optional<Instant> current = process.info().startInstant();
        return current.isPresent() && !current.get().equals(start);
    }

    /**
     * Why a process is not stopped without {@code --allow-protected}: it is a parent of PowerJ (the terminal, the IDE, the
     * shell it was started from: a {@code where} that is too wide in {@code ps | where … | kill} would end the session
     * that typed it) or the init process of the system. The current process itself is the JDK's refusal, further down.
     */
    private static Optional<String> refusal(ProcessHandle process, ProcessEntry entry) {
        String name = entry.name() == null ? "?" : entry.name();
        if (isInit(process)) {
            return Optional.of(Messages.get("kill.protected.init", process.pid(), name));
        }
        for (ProcessHandle ancestor = ProcessHandle.current().parent().orElse(null); ancestor != null;
             ancestor = ancestor.parent().orElse(null)) {
            if (ancestor.pid() == process.pid()) {
                return Optional.of(Messages.get("kill.protected.parent", process.pid(), name));
            }
        }
        return Optional.empty();
    }

    /** The init process of the system: pid 1 on Unix, the System process (pid 4) and the idle process (pid 0) on Windows. */
    private static boolean isInit(ProcessHandle process) {
        return Host.windows() ? process.pid() == 0 || process.pid() == 4 : process.pid() == 1;
    }

    /**
     * Waits for the process to end, as long as {@code --timeout} allows.
     *
     * @return {@code false} (after reporting it) if the process is still running when the time is over
     */
    private static boolean awaited(Params p, ProcessHandle process, ProcessEntry entry,
                                   CmdletContext<ProcessEntry> context) {
        Duration timeout = p.timeout() == null ? DEFAULT_TIMEOUT : p.timeout();
        try {
            process.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            while (process.isAlive()) { // Windows: the end may be seen a moment after onExit completes
                Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } catch (ExecutionException e) {
            // onExit does not fail
        } catch (TimeoutException e) {
            context.error(Messages.get("kill.timeout", process.pid(), entry.name() == null ? "?" : entry.name(),
                    Formats.duration(timeout)));
            return false;
        }
        return true;
    }
}
