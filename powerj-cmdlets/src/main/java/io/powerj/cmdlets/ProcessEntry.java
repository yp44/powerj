package io.powerj.cmdlets;

import java.time.Duration;
import java.time.Instant;

import io.powerj.api.Display;

/**
 * A process of the machine, as listed by {@code ps} (specification FR-74). Information that the system does not give
 * (another user's process on Windows, a process that has just ended) is {@code null}.
 *
 * @param pid         process number
 * @param parent      number of the parent process, or {@code null}
 * @param name        file name of the command ({@code java}, {@code notepad.exe}), or {@code null}
 * @param command     full path of the executable, or {@code null}
 * @param commandLine command and arguments, or {@code null}
 * @param user        owner, or {@code null}
 * @param start       start time, or {@code null}
 * @param cpu         total CPU time, or {@code null}
 */
@Display(columns = {"pid", "name", "user", "cpu", "start"})
public record ProcessEntry(long pid, Long parent, String name, String command, String commandLine, String user,
                           Instant start, Duration cpu) {

    /** Entry of a process, read now. */
    static ProcessEntry of(ProcessHandle process) {
        ProcessHandle.Info info = process.info();
        String command = info.command().orElse(null);
        return new ProcessEntry(process.pid(), process.parent().map(ProcessHandle::pid).orElse(null), name(command),
                command, info.commandLine().orElse(null), info.user().orElse(null),
                info.startInstant().orElse(null), info.totalCpuDuration().orElse(null));
    }

    /** File name of a command: what follows its last {@code /} or {@code \}. */
    static String name(String command) {
        if (command == null) {
            return null;
        }
        int separator = Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\'));
        return command.substring(separator + 1);
    }
}
