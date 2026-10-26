package io.powerj.shell;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

import io.powerj.core.runtime.Outcome;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.Supervisor;

/**
 * Startup profile (FR-63): runs the commands of {@code profile.pj} like typed lines; their errors are prefixed with the
 * file and the line, and do not stop the following commands. Ctrl+C stops the whole profile.
 */
final class ProfileRunner {

    private final Supervisor supervisor;
    private final Session session;
    private final LineRunner lines;
    private final ErrorPrinter errors;
    private final PrintWriter out;

    ProfileRunner(Supervisor supervisor, Session session, LineRunner lines, ErrorPrinter errors, PrintWriter out) {
        this.supervisor = supervisor;
        this.session = session;
        this.lines = lines;
        this.errors = errors;
        this.out = out;
    }

    /** {@code $profile} designates {@code file}, which is run if {@code run} ({@code false} with {@code --no-profile}). */
    void run(Path file, boolean run) {
        if (file == null) {
            return;
        }
        session.setVariable("profile", file);
        if (!run) {
            return;
        }
        List<Profile.Command> commands;
        try {
            commands = Profile.read(file);
        } catch (IOException e) {
            errors.print(Messages.get("profile.readFailed", file, e.getMessage()));
            return;
        }
        String name = file.getFileName().toString();
        try {
            for (var command : commands) {
                errors.prefix(name + ":" + command.line() + ": ");
                var outcome = supervisor.run(command.text(), () -> {
                    lines.run(command.text());
                    return List.of();
                });
                switch (outcome) {
                    case Outcome.Success _ -> { }
                    case Outcome.Failure(var error) -> errors.print(error.message());
                    case Outcome.Cancelled() -> {
                        errors.print(Messages.get("profile.cancelled"));
                        return;
                    }
                    case Outcome.Abandoned(var commandLine) -> {
                        errors.print(Messages.get("command.abandoned", commandLine));
                        return;
                    }
                }
                out.flush();
                if (session.exitRequest().isPresent()) {
                    return;
                }
            }
        } finally {
            errors.prefix(null);
            out.flush();
        }
    }
}
