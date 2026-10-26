package io.powerj.shell;

/** What the shell is doing with the line being typed (FR-66, FR-76): nothing special, showing a preview, or measuring. */
enum ShellMode {
    /** The prompt {@code PJ }. */
    NORMAL,
    /** Alt+V, the prompt {@code PJ?}: the line is evaluated while it is typed and its first results are shown. */
    PREVIEW,
    /** Alt+P, the prompt {@code PJ%}: each line is measured and a summary is printed after it. */
    PERF
}
