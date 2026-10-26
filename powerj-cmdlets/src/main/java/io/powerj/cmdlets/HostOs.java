package io.powerj.cmdlets;

/**
 * The operating system the shell runs on, read once. The one place of the module that looks at {@code os.name}.
 * Only the systems that behave differently are named: Linux and the other Unixes are what is left, so nothing here
 * asks for them.
 */
final class HostOs {

    static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
    static final boolean MAC = System.getProperty("os.name", "").startsWith("Mac");

    /** Do file names ignore case on the usual file systems of this system? */
    static final boolean NAMES_IGNORE_CASE = WINDOWS || MAC;

    private HostOs() {
    }
}
