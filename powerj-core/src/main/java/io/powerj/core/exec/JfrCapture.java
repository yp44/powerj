package io.powerj.core.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import io.powerj.api.AllocStat;
import io.powerj.api.GcStat;
import io.powerj.api.HotMethod;

/**
 * Java Flight Recorder recording of one measured line (FR-76, part B): where the stages spend their time
 * (execution samples), what they allocate, and the garbage collections. A recording per line gives its exact window
 * and keeps the cost bounded; its file is kept with the report, to open in JDK Mission Control.
 */
final class JfrCapture {

    private static final Logger LOG = Logger.getLogger(JfrCapture.class.getName());

    /** The stage threads are named by {@link StageProbe#threadName}. */
    private static final Pattern STAGE_THREAD = Pattern.compile("powerj-etape-(\\d+)");
    /** Rows kept per stage. */
    private static final int TOP = 20;

    private final Recording recording;
    private final Path file;
    private final long callingThreadId = Thread.currentThread().threadId();

    private JfrCapture(Recording recording, Path file) {
        this.recording = recording;
        this.file = file;
    }

    /** The first recording of the JVM initializes the recorder, and the first read loads the parser: pay it once. */
    static void warmUp(Path file) {
        JfrCapture capture = start(file, PerfJfr.DEFAULT_SAMPLE_PERIOD);
        if (capture != null) {
            capture.finish(0, List.of());
            try {
                Files.deleteIfExists(file);
            } catch (IOException _) {
                // a temporary file: removed at exit
            }
        }
    }

    /**
     * Starts recording into {@code file}, sampling the execution every {@code samplePeriod}; {@code null} if the
     * profiler cannot start (the line is measured without it).
     */
    static JfrCapture start(Path file, Duration samplePeriod) {
        Recording recording = null;
        try {
            recording = new Recording();
            recording.setName("powerj-perf");
            recording.setDestination(file);
            recording.enable("jdk.ExecutionSample").withPeriod(samplePeriod).withStackTrace();
            recording.enable("jdk.GarbageCollection");
            recording.enable("jdk.ObjectAllocationSample");
            recording.start();
            return new JfrCapture(recording, file);
        } catch (IOException | RuntimeException | LinkageError e) {
            LOG.log(Level.WARNING, "Java Flight Recorder unavailable: the line is measured without it", e);
            if (recording != null) {
                recording.close();
            }
            return null;
        }
    }

    Path file() {
        return file;
    }

    /** Stops the recording and reads it. */
    JfrData finish(int line, List<StageProbe> stages) {
        try {
            recording.stop();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Java Flight Recorder did not stop", e);
            return JfrData.EMPTY;
        } finally {
            recording.close();
        }
        try (var events = new RecordingFile(file)) {
            return read(events, line, stages);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Java Flight Recorder file unreadable: " + file, e);
            return JfrData.EMPTY;
        }
    }

    private JfrData read(RecordingFile events, int line, List<StageProbe> stages) throws IOException {
        Map<Integer, Map<String, Long>> methods = new HashMap<>();
        Map<Integer, Map<String, Long>> allocations = new HashMap<>();
        List<GcStat> collections = new ArrayList<>();
        while (events.hasMoreEvents()) {
            RecordedEvent event = events.readEvent();
            switch (event.getEventType().getName()) {
                case "jdk.ExecutionSample" -> {
                    int stage = stageOf(event.getThread("sampledThread"), event.getStartTime(), stages);
                    String method = topMethod(event);
                    if (stage >= 0 && method != null) {
                        methods.computeIfAbsent(stage, _ -> new HashMap<>()).merge(method, 1L, Long::sum);
                    }
                }
                case "jdk.ObjectAllocationSample" -> {
                    int stage = stageOf(event.getThread(), event.getStartTime(), stages);
                    if (stage >= 0 && event.getClass("objectClass") != null) {
                        allocations.computeIfAbsent(stage, _ -> new HashMap<>())
                                .merge(typeName(event.getClass("objectClass").getName()), event.getLong("weight"), Long::sum);
                    }
                }
                case "jdk.GarbageCollection" -> collections.add(new GcStat(line, event.getString("name"),
                        event.getString("cause"), event.getDuration("sumOfPauses")));
                default -> { }
            }
        }
        List<HotMethod> hot = new ArrayList<>();
        methods.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(stage -> {
            long total = stage.getValue().values().stream().mapToLong(Long::longValue).sum();
            stage.getValue().entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .limit(TOP).forEach(method -> hot.add(new HotMethod(line, stage.getKey(), method.getKey(),
                            method.getValue(), Math.round(1000.0 * method.getValue() / total) / 10.0)));
        });
        List<AllocStat> alloc = new ArrayList<>();
        allocations.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(stage ->
                stage.getValue().entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                        .limit(TOP).forEach(type -> alloc.add(new AllocStat(line, stage.getKey(), type.getKey(),
                                type.getValue()))));
        collections.sort(Comparator.comparing(GcStat::pause).reversed());
        return new JfrData(hot, collections, alloc);
    }

    /**
     * The stage a thread belongs to: a stage thread by its name; the calling thread by the time, since it runs the last
     * stage of each pipeline and the rest of the shell (0). Other threads (editor, completion) are ignored (-1).
     */
    private int stageOf(RecordedThread thread, Instant time, List<StageProbe> stages) {
        if (thread == null) {
            return -1;
        }
        if (thread.getJavaThreadId() == callingThreadId) {
            for (StageProbe stage : stages) {
                if (stage.onCallingThread() && !time.isBefore(stage.startTime()) && !time.isAfter(stage.endTime())) {
                    return stage.index();
                }
            }
            return 0;
        }
        String name = thread.getJavaName();
        if (thread.isVirtual() && name != null) {
            Matcher matcher = STAGE_THREAD.matcher(name);
            if (matcher.matches()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return -1;
    }

    private static String topMethod(RecordedEvent event) {
        if (event.getStackTrace() == null || event.getStackTrace().getFrames().isEmpty()) {
            return null;
        }
        RecordedFrame frame = event.getStackTrace().getFrames().getFirst();
        return frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
    }

    /** {@code [B} → {@code byte[]}, {@code [Ljava.lang.Object;} → {@code java.lang.Object[]}. */
    static String typeName(String name) {
        int dimensions = 0;
        while (dimensions < name.length() && name.charAt(dimensions) == '[') {
            dimensions++;
        }
        if (dimensions == 0) {
            return name;
        }
        String element = name.substring(dimensions);
        String type = switch (element) {
            case "B" -> "byte";
            case "C" -> "char";
            case "D" -> "double";
            case "F" -> "float";
            case "I" -> "int";
            case "J" -> "long";
            case "S" -> "short";
            case "Z" -> "boolean";
            default -> element.startsWith("L") && element.endsWith(";") ? element.substring(1, element.length() - 1)
                    : element;
        };
        return type + "[]".repeat(dimensions);
    }
}
