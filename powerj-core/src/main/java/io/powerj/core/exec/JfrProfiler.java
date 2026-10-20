package io.powerj.core.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/** {@link PerfProfiler} on the Flight Recorder; only loaded when {@code jdk.jfr} is present. */
final class JfrProfiler implements PerfProfiler.Recording {

    /** Sampling period of the Java threads. */
    static final Duration PERIOD = Duration.ofMillis(5);
    private static final Duration THRESHOLD = Duration.ofMillis(1);
    private static final int HOT_METHODS = 3;

    static final List<String> EVENTS = List.of("jdk.ExecutionSample", "jdk.ObjectAllocationSample",
            "jdk.GarbageCollection", "jdk.GCHeapSummary", "jdk.ThreadPark", "jdk.JavaMonitorEnter",
            "jdk.JavaMonitorWait", "jdk.ThreadSleep", "jdk.FileRead", "jdk.FileWrite", "jdk.VirtualThreadPinned", "powerj.Command",
            "powerj.Stage");

    private final Recording recording;
    private final long started = System.currentTimeMillis();
    private boolean closed;

    private JfrProfiler(Recording recording) {
        this.recording = recording;
    }

    static PerfProfiler.Recording start() {
        var recording = new Recording();
        try {
            recording.setName("powerj-perf");
            recording.enable("jdk.ExecutionSample").withPeriod(PERIOD).withStackTrace();
            recording.enable("jdk.ObjectAllocationSample").with("throttle", "5000/s").withStackTrace();
            recording.enable("jdk.GarbageCollection");
            recording.enable("jdk.GCHeapSummary");
            for (String name : List.of("jdk.ThreadPark", "jdk.ThreadSleep", "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait",
                    "jdk.FileRead", "jdk.FileWrite", "jdk.VirtualThreadPinned")) {
                recording.enable(name).withThreshold(THRESHOLD).withStackTrace();
            }
            recording.enable("powerj.Command");
            recording.enable("powerj.Stage");
            recording.start();
            return new JfrProfiler(recording);
        } catch (RuntimeException e) {
            recording.close();
            throw e;
        }
    }

    @Override
    public PerfProfile finish(Path dir, int keep) throws IOException {
        if (closed) {
            throw new IllegalStateException("recording already finished");
        }
        closed = true;
        Files.createDirectories(dir);
        Path file = PerfFiles.newFile(dir, started);
        try {
            recording.stop();
            recording.dump(file);
        } finally {
            recording.close();
        }
        PerfProfile profile;
        try {
            profile = analyse(file);
        } finally {
            // the file is kept only if the retention says so, whatever the analysis did
            List<Path> kept = PerfFiles.retain(dir, keep);
            if (!kept.contains(file)) {
                Files.deleteIfExists(file);
            }
        }
        return new PerfProfile(Files.exists(file) ? file : null, profile.wall(), profile.cpu(), profile.samples(),
                profile.allocated(), profile.waited(), profile.gc(), profile.stages());
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            recording.close();
        }
    }

    // --- analysis ---------------------------------------------------------------------------------------------

    /** A stage of the line: its window, and what was seen in it. */
    private static final class Stage {
        int number;
        final RecordedEvent event;
        int samples;
        long allocated;
        long waitNanos;
        int pinned;
        final Map<String, Integer> methods = new HashMap<>();
        final Map<String, Long> sites = new HashMap<>();
        final Map<String, long[]> waits = new HashMap<>(); // cause -> {nanos, count}

        Stage(int number, RecordedEvent event) {
            this.number = number;
            this.event = event;
        }

        boolean contains(RecordedEvent other) {
            RecordedThread thread = thread(other);
            return thread != null && thread.getJavaThreadId() == event.getThread().getJavaThreadId()
                    && !other.getStartTime().isBefore(event.getStartTime())
                    && !other.getStartTime().isAfter(event.getEndTime());
        }

        String command() {
            return event.getString("command");
        }
    }

    static PerfProfile analyse(Path file) throws IOException {
        List<RecordedEvent> events = RecordingFile.readAllEvents(file);
        List<Stage> stages = new ArrayList<>();
        Duration wall = Duration.ZERO;
        int number = 0;
        for (RecordedEvent event : events) {
            switch (event.getEventType().getName()) {
                case "powerj.Stage" -> stages.add(new Stage(++number, event));
                case "powerj.Command" -> wall = wall.plus(event.getDuration());
                default -> { }
            }
        }
        stages.sort(Comparator.<Stage>comparingInt(stage -> stage.event.getInt("statement"))
                .thenComparingInt(stage -> stage.event.getInt("position")));
        for (int i = 0; i < stages.size(); i++) {
            stages.get(i).number = i + 1; // numbered in the order of the line: statement, then position in the pipeline
        }
        int samples = 0;
        long allocated = 0;
        long waitNanos = 0;
        int gcCount = 0;
        Duration pause = Duration.ZERO;
        long before = -1;
        long after = -1;
        for (RecordedEvent event : events) {
            String name = event.getEventType().getName();
            Stage stage = stageOf(stages, event);
            switch (name) {
                case "jdk.ExecutionSample" -> {
                    samples++;
                    if (stage != null) {
                        stage.samples++;
                        stage.methods.merge(top(event.getStackTrace()), 1, Integer::sum);
                    }
                }
                case "jdk.ObjectAllocationSample" -> {
                    long weight = event.getLong("weight");
                    allocated += weight;
                    if (stage != null) {
                        stage.allocated += weight;
                        stage.sites.merge(site(event.getStackTrace()), weight, Long::sum);
                    }
                }
                case "jdk.GarbageCollection" -> {
                    gcCount++;
                    pause = pause.plus(event.getDuration("sumOfPauses"));
                }
                case "jdk.GCHeapSummary" -> {
                    if ("Before GC".equals(event.getString("when")) && before < 0) {
                        before = event.getLong("heapUsed");
                    } else if ("After GC".equals(event.getString("when"))) {
                        after = event.getLong("heapUsed");
                    }
                }
                case "jdk.ThreadPark", "jdk.ThreadSleep", "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.FileRead",
                     "jdk.FileWrite" -> {
                    long nanos = event.getDuration().toNanos();
                    if (stage != null) {
                        stage.waitNanos += nanos;
                        long[] total = stage.waits.computeIfAbsent(cause(name), _ -> new long[2]);
                        total[0] += nanos;
                        total[1]++;
                    }
                    waitNanos += nanos;
                }
                case "jdk.VirtualThreadPinned" -> {
                    if (stage != null) {
                        stage.pinned++;
                    }
                }
                default -> { }
            }
        }
        List<PerfProfile.StageProfile> profiles = new ArrayList<>();
        for (Stage stage : stages) {
            profiles.add(profile(stage));
        }
        if (wall.isZero() && !events.isEmpty()) {
            wall = Duration.between(events.getFirst().getStartTime(), events.getLast().getEndTime());
        }
        return new PerfProfile(file, wall, cpu(samples), samples, allocated, Duration.ofNanos(waitNanos),
                new PerfProfile.Gc(gcCount, pause, before, after), profiles);
    }

    private static PerfProfile.StageProfile profile(Stage stage) {
        List<PerfProfile.HotMethod> hot = stage.methods.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(HOT_METHODS)
                .map(entry -> {
                    int line = entry.getKey().lastIndexOf(':');
                    return new PerfProfile.HotMethod(stage.command(), entry.getKey().substring(0, line),
                            Integer.parseInt(entry.getKey().substring(line + 1)), entry.getValue(),
                            100.0 * entry.getValue() / stage.samples);
                }).toList();
        List<PerfProfile.AllocationSite> sites = stage.sites.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(HOT_METHODS)
                .map(entry -> new PerfProfile.AllocationSite(stage.command(), entry.getKey(), entry.getValue(),
                        100.0 * entry.getValue() / stage.allocated)).toList();
        List<PerfProfile.Wait> waits = stage.waits.entrySet().stream()
                .sorted(Map.Entry.<String, long[]>comparingByKey())
                .map(entry -> new PerfProfile.Wait(stage.command(), entry.getKey(),
                        Duration.ofNanos(entry.getValue()[0]), (int) entry.getValue()[1])).toList();
        return new PerfProfile.StageProfile(stage.number, stage.command(), stage.event.getString("kind"),
                stage.event.getDuration(), cpu(stage.samples), stage.samples, stage.allocated,
                Duration.ofNanos(stage.waitNanos), stage.pinned, hot, sites, waits);
    }

    private static Duration cpu(int samples) {
        return PERIOD.multipliedBy(samples);
    }

    private static Stage stageOf(List<Stage> stages, RecordedEvent event) {
        Stage found = null;
        for (Stage stage : stages) {
            if (stage.contains(event) && (found == null || stage.event.getStartTime().isAfter(found.event.getStartTime()))) {
                found = stage; // the innermost window when statements follow each other on the same thread
            }
        }
        return found;
    }

    private static RecordedThread thread(RecordedEvent event) {
        RecordedThread thread = event.hasField("sampledThread") ? event.getThread("sampledThread") : null;
        return thread != null ? thread : event.getThread();
    }

    private static String cause(String event) {
        return switch (event) {
            case "jdk.ThreadPark" -> "park";
            case "jdk.ThreadSleep" -> "sleep";
            case "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait" -> "monitor";
            default -> "file";
        };
    }

    /** {@code Class.method:line} of the top frame. */
    private static String top(RecordedStackTrace trace) {
        if (trace == null || trace.getFrames().isEmpty()) {
            return "unknown:0";
        }
        RecordedFrame frame = trace.getFrames().getFirst();
        return frame.getMethod().getType().getName() + "." + frame.getMethod().getName() + ":" + frame.getLineNumber();
    }

    /** The first frame of the stack that is not the allocator's own plumbing. */
    private static String site(RecordedStackTrace trace) {
        return top(trace);
    }
}
