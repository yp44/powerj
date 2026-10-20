package io.powerj.shell;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.jline.keymap.KeyMap;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import io.powerj.core.exec.Interpreter;
import io.powerj.core.exec.PerfPlan;
import io.powerj.core.exec.PerfRecorder;
import io.powerj.core.exec.PerfProfile;
import io.powerj.core.exec.PerfReport;
import io.powerj.core.lang.Connector;

/**
 * Perf mode (FR-76): Alt+P switches it on and off, shown by the {@code PJ⏱} prompt. While the line is typed, the plan
 * of the whole line is shown below it ({@link Interpreter#plan}: nothing runs). Once the line is accepted it runs as
 * usual, then a report of the wall-clock time of every stage, statement and of the whole line is displayed.
 */
final class PerfMode {

    /** Alt+P ("Perf"): ESC then {@code p}, as terminals send Alt+letter. */
    static final String TOGGLE_KEY = "\033p";
    static final String TOGGLE_WIDGET = "powerj-perf-toggle";

    private static final int MAX_LINES = 12;

    private static final AttributedStyle BANNER = AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.BLACK);
    private static final AttributedStyle DIM = AttributedStyle.DEFAULT.faint();

    private final PreviewLineReader reader;
    private final Interpreter interpreter;
    private final IntSupplier width;

    private volatile boolean on;
    private volatile Runnable onEnable = () -> { };
    /** Text last planned, to ignore redisplays that change nothing. */
    private String lastText;

    PerfMode(PreviewLineReader reader, Interpreter interpreter, IntSupplier width) {
        this.reader = reader;
        this.interpreter = interpreter;
        this.width = width;
    }

    boolean on() {
        return on;
    }

    /** Action run when the mode is switched on (the preview mode is switched off). */
    void onEnable(Runnable action) {
        this.onEnable = action;
    }

    /** Binds Alt+P; {@code prompt} gives the prompt to show after the switch. */
    void bind(Supplier<String> prompt) {
        reader.getWidgets().put(TOGGLE_WIDGET, () -> {
            toggle();
            reader.setPrompt(prompt.get());
            return true;
        });
        KeyMap<org.jline.reader.Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
        main.bind(new Reference(TOGGLE_WIDGET), TOGGLE_KEY);
        reader.onLine(this::changed);
    }

    /** Switches the mode; the line typed is kept. */
    void toggle() {
        on = !on;
        lastText = null;
        if (on) {
            onEnable.run();
        } else {
            reader.preview(null);
        }
    }

    /** Switches the mode off if it is on (the preview mode was switched on). */
    void off() {
        if (on) {
            toggle();
        }
    }

    /** New line: nothing of the previous one stays below it. */
    void reset() {
        lastText = null;
        reader.preview(null);
    }

    /** The line changed: shows its plan. Planning only parses and resolves names, so it is done in place. */
    private void changed(String buffer, int cursor) {
        if (!on || buffer.equals(lastText)) {
            return;
        }
        lastText = buffer;
        reader.preview(render(interpreter.plan(buffer)));
    }

    private int columns() {
        return Math.max(20, width.getAsInt() - 1);
    }

    /** Lines shown below the input: a banner, then one line per stage. */
    AttributedString render(PerfPlan plan) {
        int columns = columns();
        List<AttributedString> lines = new ArrayList<>();
        switch (plan) {
            case PerfPlan.Empty() -> {
                return null;
            }
            case PerfPlan.Incomplete(var message) ->
                    lines.add(banner(Messages.get("perf.banner.incomplete", message), columns));
            case PerfPlan.Statements(var statements) -> {
                int count = statements.stream().mapToInt(statement -> statement.stages().size()).sum();
                lines.add(banner(Messages.get("perf.banner.plan", Messages.get("perf.banner.stages", count)), columns));
                for (var statement : statements) {
                    lines.add(new AttributedString(statementLine(statement), DIM));
                    for (var stage : statement.stages()) {
                        lines.add(new AttributedString("  " + stageLine(stage)));
                    }
                }
            }
        }
        return AttributedString.join(new AttributedString("\n"),
                lines.stream().limit(MAX_LINES).map(line -> line.columnSubSequence(0, Math.min(columns, line.columnLength()))).toList());
    }

    private static String statementLine(PerfPlan.Statement statement) {
        List<String> notes = new ArrayList<>();
        notes.add(connector(statement.connector()));
        if (statement.background()) {
            notes.add(Messages.get("perf.statement.background"));
        }
        if (statement.assignTo() != null) {
            notes.add(Messages.get("perf.statement.assign", statement.assignTo()));
        }
        if (statement.redirects() > 0) {
            notes.add(Messages.get("perf.statement.redirects", statement.redirects()));
        }
        return String.join(" · ", notes.stream().filter(note -> !note.isEmpty()).toList());
    }

    private static String connector(Connector connector) {
        return switch (connector) {
            case ALWAYS -> ";";
            case IF_SUCCESS -> "&&";
            case IF_FAILURE -> "||";
        };
    }

    private static String stageLine(PerfPlan.Stage stage) {
        return pad(stage.text(), 28) + " " + pad(kind(stage.kind()), 14) + " " + placement(stage.placement());
    }

    static String kind(PerfPlan.Kind kind) {
        return Messages.get("perf.kind." + kind.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static String placement(PerfPlan.Placement placement) {
        return Messages.get("perf.placement." + placement.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** The figures of a line that has run, as lines to print below its output. */
    List<AttributedString> report(PerfReport report) {
        List<AttributedString> lines = new ArrayList<>();
        int columns = columns();
        lines.add(banner(Messages.get("perf.report.banner", duration(report.total())), columns));
        int number = 0;
        for (var statement : report.statements()) {
            number++;
            lines.add(new AttributedString(Messages.get("perf.report.statement", number, duration(statement.duration()),
                    statement.succeeded() ? "" : Messages.get("perf.report.failed")), DIM));
            for (var stage : statement.stages()) {
                lines.add(new AttributedString("  " + stageReport(stage)));
            }
        }
        lines.add(new AttributedString(Messages.get("perf.report.note"), BANNER));
        return lines;
    }

    /** The Flight Recorder summary of a line (FR-78): the whole line, then one line per stage. */
    List<AttributedString> profile(PerfProfile profile) {
        List<AttributedString> lines = new ArrayList<>();
        long wall = Math.max(1, profile.wall().toNanos());
        String gc = profile.gc().count() == 0 ? Messages.get("perf.profile.noGc")
                : Messages.get("perf.profile.gc", profile.gc().count(), duration(profile.gc().pause()));
        lines.add(new AttributedString(Messages.get("perf.profile.header", duration(profile.cpu()),
                Math.round(100.0 * profile.cpu().toNanos() / wall), bytes(profile.allocated()), gc,
                duration(profile.waited())), DIM));
        for (var stage : profile.stages()) {
            String hot = stage.hot().isEmpty() ? "" : Messages.get("perf.profile.hot", String.join(", ",
                    stage.hot().stream().map(method -> shortMethod(method.method()) + ":" + method.line() + " "
                            + Math.round(method.percent()) + " %").toList()));
            lines.add(new AttributedString("  " + Messages.get("perf.profile.stage", pad(stage.command(), 12),
                    duration(stage.cpu()), bytes(stage.allocated()), duration(stage.waited()), hot)));
        }
        if (profile.low()) {
            lines.add(new AttributedString(Messages.get("perf.profile.low", profile.samples()), BANNER));
        }
        if (profile.file() != null) {
            lines.add(new AttributedString(Messages.get("perf.profile.file", profile.file()), BANNER));
        }
        return lines;
    }

    /** {@code PerfProfileTest$Spin.begin} from {@code io.powerj.core.exec.PerfProfileTest$Spin.begin}. */
    static String shortMethod(String method) {
        int dot = method.lastIndexOf('.');
        int previous = dot < 0 ? -1 : method.lastIndexOf('.', dot - 1);
        return method.substring(previous + 1);
    }

    /** {@code 512 B}, {@code 48.2 MB}. */
    static String bytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return "%.1f %s".formatted(value, units[unit]);
    }

    private static String stageReport(PerfReport.Stage stage) {
        return Messages.get("perf.report.stage", pad(stage.text(), 28), pad(kind(stage.kind()), 14),
                padStart(duration(stage.duration()), 9), padStart(count(stage.in()), 6), padStart(count(stage.out()), 6),
                padStart(stage.firstOutput() == null ? "-" : duration(stage.firstOutput()), 9));
    }

    private static String count(long value) {
        return value < 0 ? "-" : Long.toString(value);
    }

    /** {@code 950 µs}, {@code 45.3 ms}, {@code 1.82 s}. */
    static String duration(Duration duration) {
        long nanos = duration.toNanos();
        if (nanos < 1_000_000) {
            return nanos / 1_000 + " µs";
        }
        if (nanos < 1_000_000_000) {
            return "%.1f ms".formatted(nanos / 1e6);
        }
        return "%.2f s".formatted(nanos / 1e9);
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text.substring(0, width - 1) + "…" : text + " ".repeat(width - text.length());
    }

    private static String padStart(String text, int width) {
        return text.length() >= width ? text : " ".repeat(width - text.length()) + text;
    }

    /** {@code ── Plan · 3 stage(s) ─────}: dimmed line that separates the input from the plan. */
    private static AttributedString banner(String text, int columns) {
        var builder = new AttributedStringBuilder();
        builder.style(BANNER).append("── ").append(text).append(" ");
        while (builder.columnLength() < columns) {
            builder.append('─');
        }
        return builder.toAttributedString().columnSubSequence(0, columns);
    }

    /** A recorder for the next line, or {@code null} if perf mode is off. */
    PerfRecorder recorder() {
        return on ? new PerfRecorder() : null;
    }
}
