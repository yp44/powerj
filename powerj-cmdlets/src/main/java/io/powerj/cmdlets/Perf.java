package io.powerj.cmdlets;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.PerfReport;

/**
 * {@code perf}: what was measured in perf mode (Alt+P), as records to process in a pipeline (specification FR-76).
 */
@CmdletInfo(name = "perf", preview = true, category = "System", summary = "Shows what perf mode measured",
        examples = {"perf", "perf | sort active -d | first 1", "perf 2 --view blocks", "perf --view jvm --all",
                "perf --view lines | sort total -d | first 5", "perf --view hot", "perf --save ~/slow.jfr"})
public final class Perf implements Cmdlet<Perf.Params, Void, Object> {

    /** What {@code perf} lists. */
    public enum View { STAGES, BLOCKS, NATIVES, PHASES, JVM, LINES, HOT, GC, ALLOC }

    /** Parameters of {@code perf}. */
    public record Params(
            @Option(position = 0, description = "Number of the line to show, as in the line column (default: the last measured line)")
            Integer line,
            @Option(longName = "view", description = "Records listed: stages (default), blocks, natives, phases, jvm, lines, hot, gc, alloc")
            View view,
            @Option(shortName = 'a', longName = "all", description = "Every kept line, oldest first")
            boolean all,
            @Option(longName = "save", description = "Writes the Flight Recorder file of the line here (a file, or a folder)")
            Path save) {
    }

    /** Writing a file is a side effect: not in a preview. */
    @Override
    public boolean previewable(Params p) {
        return p.save() == null;
    }

    @Override
    public void begin(Params p, CmdletContext<Object> context) {
        View view = p.view() == null ? View.STAGES : p.view();
        if (p.line() != null && p.line() < 1) {
            throw new IllegalArgumentException(Messages.get("perf.line.invalid", p.line()));
        }
        if (p.line() != null && p.all()) {
            throw new IllegalArgumentException(Messages.get("perf.line.all"));
        }
        if (p.save() != null && (p.view() != null || p.all())) {
            throw new IllegalArgumentException(Messages.get("perf.save.exclusive"));
        }
        List<PerfReport> kept = context.perfReports();
        if (kept.isEmpty()) {
            context.error(Messages.get("perf.none"));
            return;
        }
        List<PerfReport> selected;
        if (p.all() || view == View.LINES && p.line() == null) {
            selected = new ArrayList<>(kept);
            java.util.Collections.reverse(selected);
        } else {
            // The number of a line is the one of the line column: it does not go back when old lines are dropped.
            int n = p.line() == null ? kept.getFirst().line() : p.line();
            var found = kept.stream().filter(report -> report.line() == n).findFirst();
            if (found.isEmpty()) {
                context.error(Messages.get("perf.line.missing", n, kept.getLast().line(), kept.getFirst().line()));
                return;
            }
            selected = List.of(found.get());
        }
        if (p.save() != null) {
            save(selected.getFirst(), p.save(), context);
            return;
        }
        if (view.ordinal() >= View.HOT.ordinal() && selected.stream().noneMatch(r -> r.recording() != null)) {
            context.error(Messages.get("perf.jfr.none"));
            return;
        }
        for (PerfReport report : selected) {
            switch (view) {
                case STAGES -> report.stages().forEach(context::emit);
                case BLOCKS -> report.blocks().forEach(context::emit);
                case NATIVES -> report.natives().forEach(context::emit);
                case PHASES -> report.phases().forEach(context::emit);
                case JVM -> context.emit(report.jvm());
                case LINES -> context.emit(report.summary());
                case HOT -> report.hot().forEach(context::emit);
                case GC -> report.gc().forEach(context::emit);
                case ALLOC -> report.alloc().forEach(context::emit);
            }
        }
    }

    /** Copies the Flight Recorder file of the line: {@code target} is a file, or a folder that receives {@code perf-<n>.jfr}. */
    private static void save(PerfReport report, Path save, CmdletContext<Object> context) {
        Path recording = report.recording();
        if (recording == null || !Files.isRegularFile(recording)) {
            context.error(Messages.get("perf.jfr.none"));
            return;
        }
        Path target = context.currentDirectory().resolve(save);
        if (Files.isDirectory(target)) {
            target = target.resolve("perf-" + report.line() + ".jfr");
        }
        try {
            Files.copy(recording, target); // never replaces: a recording is not worth losing a file for
            context.emit(target);
        } catch (FileAlreadyExistsException e) {
            context.error(Messages.get("perf.save.exists", target));
        } catch (IOException e) {
            context.error(Messages.get("perf.save.failed", target, e.getMessage()));
        }
    }
}
