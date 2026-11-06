package io.powerj.core.nativeexec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.powerj.core.nativeexec.BackgroundLauncher.Output;
import io.powerj.core.nativeexec.BackgroundLauncher.Stage;

/**
 * A background job on Linux and macOS: its stages are started by {@link PosixSpawn}, connected by pipes, the first one the
 * leader of a new process group and the others members of it.
 */
final class PosixJobs {

    private PosixJobs() {
    }

    static List<Process> start(List<Stage> stages, Path directory, Map<String, String> environment, Output out,
                               Output err) throws IOException {
        List<PosixSpawn.Spawned> started = new ArrayList<>(stages.size());
        int[] feeding = null; // the pipe whose read end is the standard input of the next stage
        int[] pipe = null;
        try {
            int group = PosixSpawn.OWN_GROUP;
            for (int i = 0; i < stages.size(); i++) {
                Stage stage = stages.get(i);
                boolean last = i == stages.size() - 1;
                pipe = last ? null : PosixSpawn.pipe();
                PosixSpawn.Spec spec = PosixSpawn.Spec.of(stage.executable(), stage.args().toArray(String[]::new))
                        .env(environment).directory(directory).group(group)
                        .in(feeding == null ? PosixSpawn.Stdio.NULL : PosixSpawn.Stdio.fd(feeding[0]))
                        .out(last ? stdio(out) : PosixSpawn.Stdio.fd(pipe[1]))
                        .err(stage.errorsToOutput() ? PosixSpawn.Stdio.STDOUT : stdio(err));
                PosixSpawn.Spawned spawned = PosixSpawn.spawnUnreaped(spec);
                started.add(spawned);
                if (i == 0) {
                    group = spawned.pid(); // the other stages join the group of the first
                }
                // The child has its own copies: the parent keeps only the read end that the next stage needs.
                closeQuietly(feeding == null ? -1 : feeding[0]);
                closeQuietly(pipe == null ? -1 : pipe[1]);
                feeding = pipe;
                pipe = null;
            }
        } catch (IOException | RuntimeException e) {
            started.forEach(s -> PosixSpawn.kill(s.pid(), PosixSpawn.SIGKILL));
            started.forEach(PosixSpawn::reap);
            throw e;
        } finally {
            closeQuietly(feeding == null ? -1 : feeding[0]);
            if (pipe != null) {
                closeQuietly(pipe[0]);
                closeQuietly(pipe[1]);
            }
        }
        // Only now, with every stage started and in its group, may an ended stage be reaped: a stage that joins the group of
        // the first one fails with EPERM once the first has ended and been reaped.
        started.forEach(PosixSpawn::reap);
        return started.stream().<Process>map(s -> new SpawnedProcess(s.pid(), s.exit(),
                () -> {
                    PosixSpawn.kill(s.pid(), PosixSpawn.SIGTERM);
                    PosixSpawn.kill(s.pid(), PosixSpawn.SIGCONT); // a job stopped by the terminal keeps SIGTERM pending
                }, () -> PosixSpawn.kill(s.pid(), PosixSpawn.SIGKILL)))
                .toList();
    }

    private static PosixSpawn.Stdio stdio(Output output) {
        return switch (output.kind()) {
            case FILE -> PosixSpawn.Stdio.file(output.file());
            case APPEND -> PosixSpawn.Stdio.append(output.file());
            case INHERIT -> PosixSpawn.Stdio.INHERIT;
            case DISCARD -> PosixSpawn.Stdio.NULL;
        };
    }

    private static void closeQuietly(int fd) {
        if (fd >= 0) {
            PosixSpawn.close(fd);
        }
    }
}
