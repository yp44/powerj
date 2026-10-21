package io.powerj.core.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Retention of the Flight Recorder files written by perf mode (FR-78), driven by the {@value #VARIABLE} variable
 * of the session environment: {@code 0} (or unset, or not a number) deletes the file once analysed, {@code 1} keeps
 * the last one, {@code N} keeps the last N. Read at every line, so {@code env --set} takes effect at once.
 */
public final class PerfFiles {

    public static final String VARIABLE = "POWERJ_PERF_KEEP";

    static final String PREFIX = "perf-";
    static final String SUFFIX = ".jfr";

    private PerfFiles() {
    }

    /** Number of files to keep, {@code 0} if the variable is unset, negative or not a number. */
    public static int keep(Map<String, String> environment) {
        String value = environment.get(VARIABLE);
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.strip()));
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    /** File name for a recording started at {@code millis}; sorts by date. */
    static Path newFile(Path dir, long millis) {
        return dir.resolve(PREFIX + "%013d".formatted(millis) + SUFFIX);
    }

    /**
     * Applies the retention after {@code newest} was written: keeps the {@code keep} most recent files of
     * {@code dir} and deletes the others (all of them, {@code newest} included, for {@code 0}).
     *
     * @return the files kept, most recent first
     */
    static List<Path> retain(Path dir, int keep) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(PREFIX) && name.endsWith(SUFFIX);
            }).sorted(java.util.Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
        }
        for (int i = keep; i < files.size(); i++) {
            Files.deleteIfExists(files.get(i));
        }
        return files.subList(0, Math.min(keep, files.size()));
    }
}
