package io.powerj.cmdlets;

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
                "perf --view lines | sort total -d | first 5"})
public final class Perf implements Cmdlet<Perf.Params, Void, Object> {

    /** What {@code perf} lists. */
    public enum View { STAGES, BLOCKS, NATIVES, PHASES, JVM, LINES }

    /** Parameters of {@code perf}. */
    public record Params(
            @Option(position = 0, description = "Line to show: 1 is the last measured line (default: 1)")
            Integer line,
            @Option(longName = "view", description = "Records listed: stages (default), blocks, natives, phases, jvm, lines")
            View view,
            @Option(shortName = 'a', longName = "all", description = "Every kept line, oldest first")
            boolean all) {
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
            int n = p.line() == null ? 1 : p.line();
            if (n > kept.size()) {
                context.error(Messages.get("perf.line.missing", n, kept.size()));
                return;
            }
            selected = List.of(kept.get(n - 1));
        }
        for (PerfReport report : selected) {
            switch (view) {
                case STAGES -> report.stages().forEach(context::emit);
                case BLOCKS -> report.blocks().forEach(context::emit);
                case NATIVES -> report.natives().forEach(context::emit);
                case PHASES -> report.phases().forEach(context::emit);
                case JVM -> context.emit(report.jvm());
                case LINES -> context.emit(report.summary());
            }
        }
    }
}
