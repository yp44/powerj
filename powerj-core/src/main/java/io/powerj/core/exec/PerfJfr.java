package io.powerj.core.exec;

/**
 * Is the profiler there? The module {@code jdk.jfr} is optional ({@code requires static}): a custom runtime may lack it, and
 * perf mode then works without the profiler. This class names no type of the module, so it can always be loaded.
 */
final class PerfJfr {

    private PerfJfr() {
    }

    static boolean available() {
        return ModuleLayer.boot().findModule("jdk.jfr").isPresent();
    }
}
