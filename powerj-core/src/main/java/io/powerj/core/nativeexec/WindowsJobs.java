package io.powerj.core.nativeexec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.powerj.core.nativeexec.BackgroundLauncher.Output;
import io.powerj.core.nativeexec.BackgroundLauncher.Stage;

/**
 * A background job on Windows: its stages are started by {@link WindowsSpawn}, each in a new process group
 * ({@code CREATE_NEW_PROCESS_GROUP}, which disables Ctrl+C for it), connected by pipes, and inheriting only the three
 * standard handles chosen for it.
 */
final class WindowsJobs {

    private WindowsJobs() {
    }

    static List<Process> start(List<Stage> stages, Path directory, Map<String, String> environment, Output out,
                               Output err) throws IOException {
        List<WindowsSpawn.Spawned> started = new ArrayList<>(stages.size());
        long[] feeding = null; // the pipe whose read end is the standard input of the next stage
        long[] pipe = null;
        try {
            for (int i = 0; i < stages.size(); i++) {
                Stage stage = stages.get(i);
                boolean last = i == stages.size() - 1;
                pipe = last ? null : WindowsSpawn.pipe();
                WindowsSpawn.Spec spec = WindowsSpawn.Spec.of(stage.executable(), stage.args().toArray(String[]::new))
                        .env(environment).directory(directory).flags(WindowsSpawn.CREATE_NEW_PROCESS_GROUP)
                        .in(feeding == null ? WindowsSpawn.Stdio.NULL : WindowsSpawn.Stdio.handle(feeding[0]))
                        .out(last ? stdio(out) : WindowsSpawn.Stdio.handle(pipe[1]))
                        .err(stage.errorsToOutput() ? WindowsSpawn.Stdio.STDOUT : stdio(err));
                started.add(WindowsSpawn.spawn(spec));
                // The child has its own copies: the parent keeps only the read end that the next stage needs.
                closeQuietly(feeding == null ? 0 : feeding[0]);
                closeQuietly(pipe == null ? 0 : pipe[1]);
                feeding = pipe;
                pipe = null;
            }
        } catch (IOException | RuntimeException e) {
            started.forEach(WindowsSpawn.Spawned::terminate);
            throw e;
        } finally {
            closeQuietly(feeding == null ? 0 : feeding[0]);
            if (pipe != null) {
                closeQuietly(pipe[0]);
                closeQuietly(pipe[1]);
            }
        }
        return started.stream().<Process>map(s -> new SpawnedProcess(s.pid(), s.exit(), s::terminate, s::terminate))
                .toList();
    }

    private static WindowsSpawn.Stdio stdio(Output output) {
        return switch (output.kind()) {
            case FILE -> WindowsSpawn.Stdio.file(output.file());
            case APPEND -> WindowsSpawn.Stdio.append(output.file());
            case INHERIT -> WindowsSpawn.Stdio.INHERIT;
            case DISCARD -> WindowsSpawn.Stdio.NULL;
        };
    }

    /** A handle of 0 is none. */
    private static void closeQuietly(long handle) {
        if (handle != 0) {
            WindowsSpawn.close(handle);
        }
    }
}
