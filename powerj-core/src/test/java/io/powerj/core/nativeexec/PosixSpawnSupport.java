package io.powerj.core.nativeexec;

import java.io.IOException;

/** What the tests of {@link PosixSpawn} need that production does not: a start that reaps its child at once. */
final class PosixSpawnSupport {

    private PosixSpawnSupport() {
    }

    /** Starts a process and the thread that reaps it (the launcher reaps a job only once all its stages are started). */
    static PosixSpawn.Spawned spawn(PosixSpawn.Spec spec) throws IOException {
        PosixSpawn.Spawned spawned = PosixSpawn.spawnUnreaped(spec);
        PosixSpawn.reap(spawned);
        return spawned;
    }
}
