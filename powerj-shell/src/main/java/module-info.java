/**
 * PowerJ interactive shell: read loop, line editing, history, entry point.
 */
module io.powerj.shell {
    requires io.powerj.core;
    // The built-in cmdlets: none of their classes is used here, they are found by the service loader; the shell requires
    // the module so that it is in the image whatever the way it is built.
    requires io.powerj.cmdlets;
    requires java.logging;
    requires org.jline.reader;
    requires org.jline.terminal;
    // Native terminal provider (Windows console, Unix pty) via the FFM API, loaded by ServiceLoader.
    requires org.jline.terminal.ffm;
}
