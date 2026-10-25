package io.powerj.core.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.powerj.api.perf.PerfReport;
import io.powerj.core.nativeexec.NativeRun;

/**
 * What the shell remembers about what happened: the outcome of the last command ({@code $?}, {@code $exit},
 * {@code $last}), the recent Java exceptions ({@code $errors}, FR-53) and the lines measured in perf mode
 * ({@code $perf}, FR-76). The command thread records, other threads read: the outcome is {@code volatile}, the two
 * histories are guarded by the lock of this object.
 */
public final class ShellStatistics {

    static final int MAX_ERRORS = 20;
    public static final int MAX_PERF_REPORTS = 50;

    private volatile NativeRun lastNative;
    private volatile boolean lastSucceeded = true;
    /** Recent Java exceptions, most recent first. */
    private final Deque<Throwable> errors = new ArrayDeque<>();
    /** Lines measured in perf mode, most recent first. */
    private final Deque<PerfReport> perfReports = new ArrayDeque<>();
    private int perfLines;

    Optional<NativeRun> lastNative() {
        return Optional.ofNullable(lastNative);
    }

    void recordNative(NativeRun run) {
        lastNative = Objects.requireNonNull(run);
    }

    boolean lastSucceeded() {
        return lastSucceeded;
    }

    void recordSuccess(boolean succeeded) {
        lastSucceeded = succeeded;
    }

    /** Keeps the original exception of an error for {@code $errors}; the oldest go once there are {@value #MAX_ERRORS}. */
    synchronized void recordError(Throwable error) {
        errors.addFirst(error);
        while (errors.size() > MAX_ERRORS) {
            errors.removeLast();
        }
    }

    synchronized List<Throwable> recentErrors() {
        return List.copyOf(errors);
    }

    /** Number of the next line measured in perf mode: from 1, whatever the reports kept. */
    synchronized int nextPerfLine() {
        return ++perfLines;
    }

    /**
     * Keeps the report of a measured line, for {@code perf} and {@code $perf}. Beyond {@value #MAX_PERF_REPORTS} the
     * oldest goes, and so does the recording of the profiler that came with it.
     */
    synchronized void addPerfReport(PerfReport report) {
        perfReports.addFirst(report);
        while (perfReports.size() > MAX_PERF_REPORTS) {
            var dropped = perfReports.removeLast();
            if (dropped.recording() != null) {
                try {
                    Files.deleteIfExists(dropped.recording());
                } catch (IOException _) {
                    // a temporary file: removed at exit
                }
            }
        }
    }

    /** The lines measured in perf mode, most recent first. */
    synchronized List<PerfReport> perfReports() {
        return List.copyOf(perfReports);
    }
}
