package io.powerj.core.exec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import io.powerj.core.lang.Ast;

/**
 * Perf mode (FR-76): while it is on, each line executed is measured and a summary is printed after it. This class owns
 * what surrounds the measure: whether the mode is on, the profiler and its settings read from the session environment
 * ({@code POWERJ_PERF_*}), the files of the profiler's recordings, and the report kept and printed at the end of a line.
 * The measure itself is made by {@link PerfRecorder}, which the execution reaches as an {@link ExecutionObserver}.
 */
final class PerfMode {

    /**
     * Variable of the session environment ({@code env --set} in {@code profile.pj}): when it is {@code 1}, the perf mode
     * ends by itself after the next measured line.
     */
    static final String AUTOEXIT_VARIABLE = "POWERJ_PERF_AUTOEXIT";

    private static final Logger LOG = Logger.getLogger(PerfMode.class.getName());

    private final Session session;
    private final ShellIo io;
    private volatile boolean on;
    /** The profiler records each measured line (part B); only a test or a runtime without it turns it off. */
    private volatile boolean profiler = true;
    /** Folder of the profiler's recordings, created at the first one. */
    private Path recordings;
    /** Value of each variable the warning was last given for: one warning per value, not one per line. */
    private String warnedPeriod;
    private String warnedThrottle;

    PerfMode(Session session, ShellIo io) {
        this.session = session;
        this.io = io;
    }

    /** What measures a line, and what to do when it is over. */
    static final class Measurement {

        /** A line that is not measured. */
        static final Measurement NONE = new Measurement(null, null, null);

        private final PerfMode mode;
        private final PerfRecorder recorder;
        private final JfrCapture capture;

        private Measurement(PerfMode mode, PerfRecorder recorder, JfrCapture capture) {
            this.mode = mode;
            this.recorder = recorder;
            this.capture = capture;
        }

        /** Who receives what the execution reports. */
        ExecutionObserver observer() {
            return recorder == null ? ExecutionObserver.NONE : recorder;
        }

        /** The line is over, ended or cancelled: keeps its report and prints its summary. */
        void finish(boolean cancelled) {
            if (recorder != null) {
                mode.print(recorder, capture, cancelled);
            }
        }
    }

    /** {@code true} while the lines executed are measured. */
    boolean on() {
        return on;
    }

    void on(boolean on) {
        if (on) {
            PerfRecorder.warmUp();
            if (profiler && PerfJfr.available()) {
                Path file = recordingFile();
                if (file != null) {
                    JfrCapture.warmUp(file);
                }
            }
        }
        this.on = on;
    }

    /** Without the profiler, a line is measured with the counters only. */
    void profiler(boolean on) {
        profiler = on;
    }

    /**
     * Starts to measure a line if the mode is on and the line is measurable.
     *
     * @param begin  {@code System.nanoTime()} when the line started to be parsed
     * @param parsed duration of the parsing
     */
    Measurement start(String line, Ast.Script script, long begin, long parsed) {
        if (!on || !measurable(script)) {
            return Measurement.NONE;
        }
        long started = System.nanoTime();
        JfrCapture capture = startProfiler();
        // Starting the profiler is not part of the line.
        return new Measurement(this, new PerfRecorder(line, begin + (System.nanoTime() - started), parsed), capture);
    }

    /** A line is measured unless it is empty, runs in the background, or starts with {@code perf}. */
    private static boolean measurable(Ast.Script script) {
        if (script.steps().isEmpty()) {
            return false;
        }
        for (Ast.Step step : script.steps()) {
            if (step.statement().background()) {
                return false;
            }
        }
        List<Ast.Stage> first = script.steps().getFirst().statement().pipeline().stages();
        return first.isEmpty() || !(first.getFirst().body() instanceof Ast.Command(var name, var forceNative, _)
                && !forceNative && name.equals("perf"));
    }

    private JfrCapture startProfiler() {
        if (!profiler || !PerfJfr.available()) {
            return null;
        }
        Path file = recordingFile();
        return file == null ? null : JfrCapture.start(file, samplePeriod(), allocThrottle());
    }

    /** Keeps the report of the line and prints its summary, even for a cancelled line. */
    private void print(PerfRecorder perf, JfrCapture capture, boolean cancelled) {
        boolean interrupted = Thread.interrupted(); // a cancelled line must still print: keep the flag for later
        try {
            perf.stop(); // before reading the recording: that work is not the line's
            int number = session.nextPerfLine();
            var report = capture == null ? perf.finish(number, cancelled)
                    : perf.finish(number, cancelled, capture.finish(number, perf.probes()), capture.file());
            session.addPerfReport(report);
            PerfFooter.lines(report, io.colors()).forEach(io.out()::println);
            io.out().flush();
        } finally {
            if ("1".equals(session.environment().getOrDefault(AUTOEXIT_VARIABLE, "").strip())) {
                on = false; // the summary is printed: the mode ends, the next prompt shows it
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Limit of the profiler's allocation samples, from {@code POWERJ_PERF_JFR_ALLOCTHROTTLE} in the session environment
     * ({@code 100/s}, {@code off}), or {@code null} for the profiler's default. A value that is not a rate is
     * reported, and the default is used.
     */
    private String allocThrottle() {
        String value = session.environment().get(PerfJfr.ALLOC_THROTTLE_VARIABLE);
        if (value == null || value.isBlank()) {
            return null;
        }
        boolean warn = !value.equals(warnedThrottle);
        warnedThrottle = value;
        try {
            return PerfJfr.parseAllocThrottle(value);
        } catch (IllegalArgumentException _) {
            if (warn) {
                io.errors().accept(Messages.get("perf.allocThrottle.invalid", PerfJfr.ALLOC_THROTTLE_VARIABLE, value));
            }
            return null;
        }
    }

    /**
     * Sampling period of the profiler, from {@code POWERJ_PERF_JFR_SAMPLEPERIOD} in the session environment, so that
     * {@code env --set} (in {@code profile.pj} too) changes it from the next line. A value that is not a duration is
     * reported, and the default is used; a period under 1 ms, which the profiler cannot do, becomes 1 ms.
     */
    private Duration samplePeriod() {
        String value = session.environment().get(PerfJfr.SAMPLE_PERIOD_VARIABLE);
        if (value == null || value.isBlank()) {
            return PerfJfr.DEFAULT_SAMPLE_PERIOD;
        }
        boolean warn = !value.equals(warnedPeriod);
        warnedPeriod = value;
        try {
            Duration period = PerfJfr.parseSamplePeriod(value);
            if (period.compareTo(PerfJfr.MINIMUM_SAMPLE_PERIOD) < 0) {
                if (warn) {
                    io.errors().accept(Messages.get("perf.samplePeriod.floor", PerfJfr.SAMPLE_PERIOD_VARIABLE, value));
                }
                return PerfJfr.MINIMUM_SAMPLE_PERIOD;
            }
            return period;
        } catch (IllegalArgumentException _) {
            if (warn) {
                io.errors().accept(Messages.get("perf.samplePeriod.invalid", PerfJfr.SAMPLE_PERIOD_VARIABLE, value));
            }
            return PerfJfr.DEFAULT_SAMPLE_PERIOD;
        }
    }

    /**
     * A new file for a recording, in a folder that goes away with the shell. The session deletes the file of a report it
     * no longer keeps; the folder is removed, with what is left in it, by one hook at exit (a {@code deleteOnExit} per
     * file would remember every line of a long session).
     */
    private synchronized Path recordingFile() {
        try {
            if (recordings == null) {
                recordings = Files.createTempDirectory("powerj-perf-");
                Path folder = recordings;
                Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("powerj-perf-cleanup")
                        .unstarted(() -> deleteFolder(folder)));
            }
            return Files.createTempFile(recordings, "line-", ".jfr");
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "No file for the profiler's recording", e);
            return null;
        }
    }

    /** Removes the folder and its files; whatever cannot be removed is left (it is a temporary folder). */
    static void deleteFolder(Path folder) {
        try (Stream<Path> files = Files.walk(folder)) {
            files.sorted(Comparator.reverseOrder()).forEach(file -> {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException _) {
                    // left behind: the system cleans the temporary folder
                }
            });
        } catch (IOException | UncheckedIOException _) {
            // the folder is already gone
        }
    }

    /** The folder of the recordings, or {@code null} while there has been none. */
    synchronized Path recordings() {
        return recordings;
    }
}
