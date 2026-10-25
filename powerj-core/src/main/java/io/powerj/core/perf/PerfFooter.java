package io.powerj.core.perf;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import io.powerj.api.PerfReport;
import io.powerj.api.StageStat;
import io.powerj.core.format.OutputFormatter;
import io.powerj.core.format.Values;
import io.powerj.core.i18n.Messages;

/** Summary printed after a line in perf mode (FR-76). */
final class PerfFooter {

    private static final String GRAY = "\033[90m";
    private static final String RESET = "\033[0m";
    private static final String RULE = "─";
    private static final int RULE_WIDTH = 60;

    private PerfFooter() {
    }

    /** Lines of the footer, gray when {@code colors}. */
    static List<String> lines(PerfReport report, boolean colors) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {Messages.get("perf.footer.stage"), "in", "out", "active", "starved", "blocked", ""});
        for (StageStat stage : report.stages()) {
            rows.add(new String[] {stage.command(), count(stage.in()), count(stage.out()), duration(stage.active()),
                    duration(stage.starved()), duration(stage.blocked()),
                    stage.bottleneck() ? "← " + Messages.get("perf.footer.bottleneck") : ""});
        }
        Duration display = report.phases().stream().filter(p -> p.name().equals("display")).map(p -> p.time())
                .findFirst().orElse(Duration.ZERO);
        rows.add(new String[] {"display", "", "", duration(display), "", "", ""});

        int[] widths = new int[7];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], row[i].length());
            }
        }
        List<String> lines = new ArrayList<>();
        String title = "── perf · " + Values.text(report.total())
                + (report.cancelled() ? " · " + Messages.get("perf.footer.cancelled") : "") + " ";
        lines.add(title + RULE.repeat(Math.max(3, RULE_WIDTH - title.length())));
        for (String[] row : rows) {
            var line = new StringBuilder();
            for (int i = 0; i < row.length; i++) {
                line.append(i < row.length - 1 ? pad(row[i], widths[i] + 2) : row[i]);
            }
            lines.add(line.toString().stripTrailing());
        }
        var jvm = report.jvm();
        lines.add(Messages.get("perf.footer.jvm", jvm.gcCount(), jvm.gcTime().isZero() ? "0" : Values.text(jvm.gcTime()),
                OutputFormatter.humanBytes(jvm.heapPeak()), jvm.classesLoaded(),
                jvm.jitTime().isZero() ? "0" : Values.text(jvm.jitTime())));
        String hot = hot(report);
        if (hot != null) {
            lines.add(hot);
        }
        return colors ? lines.stream().map(line -> GRAY + line + RESET).toList() : lines;
    }

    /** The methods most found running in the bottleneck stage (else in the stage with the most samples), or {@code null}. */
    private static String hot(PerfReport report) {
        int stage = report.stages().stream().filter(StageStat::bottleneck).mapToInt(StageStat::index).findFirst()
                .orElseGet(() -> report.hot().stream().filter(h -> h.stage() > 0)
                        .collect(Collectors.groupingBy(io.powerj.api.HotMethod::stage,
                                Collectors.summingLong(io.powerj.api.HotMethod::samples)))
                        .entrySet().stream().max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey).orElse(0));
        List<String> methods = report.hot().stream().filter(h -> h.stage() == stage).limit(3)
                .map(h -> shortMethod(h.method()) + " " + Math.round(h.percent()) + "%").toList();
        if (methods.isEmpty()) {
            return null;
        }
        String name = report.stages().stream().filter(st -> st.index() == stage).map(StageStat::command).findFirst()
                .orElse(Integer.toString(stage));
        return Messages.get("perf.footer.hot", name, String.join(" · ", methods));
    }

    /** {@code java.util.ArrayList.add} → {@code ArrayList.add}. */
    static String shortMethod(String method) {
        int last = method.lastIndexOf('.');
        int before = last > 0 ? method.lastIndexOf('.', last - 1) : -1;
        return method.substring(before + 1);
    }

    private static String pad(String text, int width) {
        return text + " ".repeat(Math.max(0, width - text.length()));
    }

    private static String duration(Duration duration) {
        return duration.isZero() ? "-" : Values.text(duration);
    }

    /** {@code 412000} → {@code 412k}; no value → {@code -}. */
    static String count(Long value) {
        if (value == null) {
            return "-";
        }
        if (value >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", value / 1e6);
        }
        if (value >= 10_000) {
            return (value / 1000) + "k";
        }
        return Long.toString(value);
    }
}
