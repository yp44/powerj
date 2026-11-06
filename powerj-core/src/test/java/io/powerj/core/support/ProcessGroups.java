package io.powerj.core.support;

import java.io.IOException;

/** The process group of a process, for the tests of the background jobs (issue #128). */
public final class ProcessGroups {

    private ProcessGroups() {
    }

    /** The process group of a process, from {@code ps}, which Linux and macOS both have. */
    public static long of(long pid) throws IOException, InterruptedException {
        Process ps = new ProcessBuilder("ps", "-o", "pgid=", "-p", Long.toString(pid)).redirectErrorStream(true).start();
        String text = new String(ps.getInputStream().readAllBytes()).strip();
        ps.waitFor();
        return Long.parseLong(text);
    }
}
