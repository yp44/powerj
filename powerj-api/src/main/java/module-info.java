/**
 * Public PowerJ API for writing cmdlets (specification §4). It is the only dependency
 * a third-party module needs.
 */
// The built-in modules named below are not compiled yet when this one is: javac would warn that they are not found.
@SuppressWarnings("module")
module io.powerj.api {
    exports io.powerj.api;

    /**
     * What the perf mode measured (FR-75): the data of the built-in {@code perf} cmdlet, produced by the core. Not API: its
     * records change with what is measured, and a third-party cmdlet has no use for them (audit v2, B-04). The user reads a
     * report through {@code $perf}, which the core shows, not through this package.
     */
    exports io.powerj.api.perf to io.powerj.core, io.powerj.cmdlets;

    /**
     * Where the language is written. Not API: the built-in modules only, so that a third-party cmdlet can read the
     * language but not change it (#79). The tests of those modules write it to try both languages.
     */
    exports io.powerj.api.internal to io.powerj.core, io.powerj.cmdlets, io.powerj.fx, io.powerj.shell, io.powerj.text;
}
