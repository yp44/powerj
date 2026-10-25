/**
 * PowerJ core: parsing, interpretation, command execution, Java interoperability.
 * <p>
 * Nothing here is an extension point: a third-party module depends on {@code io.powerj.api} only. The packages are
 * exported to the shell, which is the one client.
 */
@SuppressWarnings("module") // the shell is not on the module path when the core is compiled alone
module io.powerj.core {
    requires transitive io.powerj.api;
    requires java.logging;
    requires java.management;
    requires static jdk.jfr;

    exports io.powerj.core to io.powerj.shell;
    exports io.powerj.core.assist to io.powerj.shell;
    exports io.powerj.core.command to io.powerj.shell;
    exports io.powerj.core.error to io.powerj.shell;
    exports io.powerj.core.format to io.powerj.shell;
    exports io.powerj.core.java to io.powerj.shell;
    exports io.powerj.core.lang to io.powerj.shell;
    exports io.powerj.core.nativeexec to io.powerj.shell;
    exports io.powerj.core.runtime to io.powerj.shell;

    uses io.powerj.api.CmdletProvider;
    uses io.powerj.core.perf.Profiler;
    provides io.powerj.core.perf.Profiler with io.powerj.core.perf.JfrProfiler;
}
