package io.powerj.api.perf;

import java.util.List;

/**
 * The lines measured in perf mode (specification FR-76), which the session keeps. A service of the shell, for the one
 * cmdlet ({@code perf}) that shows them: ask for it with {@code context.service(PerfReports.class)}.
 */
@FunctionalInterface
public interface PerfReports {

    /** The measured lines, most recent first; empty when none was measured. */
    List<PerfReport> reports();
}
