/**
 * PowerJ interactive shell: read loop, line editing, history, entry point.
 */
module io.powerj.shell {
    requires io.powerj.core;
    requires io.powerj.cmdlets;
    requires java.logging;
    requires org.jline.reader;
    // Native terminal provider (Windows console, Unix pty) via the FFM API, loaded by ServiceLoader.
    requires org.jline.terminal.ffm;

    exports io.powerj.shell;
}
