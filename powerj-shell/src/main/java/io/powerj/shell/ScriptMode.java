package io.powerj.shell;

import java.io.PrintWriter;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import io.powerj.core.exec.CmdletRegistry;
import io.powerj.core.exec.Interpreter;
import io.powerj.core.exec.NativeRun;
import io.powerj.core.exec.Outcome;
import io.powerj.core.exec.Session;
import io.powerj.core.exec.ShellIo;
import io.powerj.core.exec.Supervisor;

/**
 * Non-interactive mode (specification FR-04d): {@code powerj -c "ligne"} or {@code powerj fichier.pj}. No
 * prompt or history; a blocking error stops execution.
 * <p>
 * Exit code: that of {@code exit n} if it is called; otherwise 0 if the last line succeeded, the code of
 * the last native command if it is the one that failed, 1 for a blocking PowerJ error.
 */
final class ScriptMode {

    private final Session session;
    private final Interpreter interpreter;
    private final Consumer<String> errors;
    private final Supervisor supervisor = new Supervisor();

    /**
     * @param input lines of standard input when it is not a terminal: they feed the first
     *              stage that reads objects ({@code dir /b | powerj -c "where { … }"})
     */
    ScriptMode(Session session, CmdletRegistry registry, PrintWriter out, Consumer<String> errors, int width,
               Optional<Iterator<String>> input) {
        this.session = session;
        this.errors = errors;
        // Les commandes natives écrivent directement sur la sortie du process, même redirigée.
        this.interpreter = new Interpreter(session, new ShellIo(out, errors, true, () -> width), Map.of(), registry);
        input.ifPresent(interpreter::useStandardInput);
    }

    /** Loads third-party modules (§4.4); warnings go to the error output. */
    void loadModules(java.nio.file.Path dir) {
        interpreter.loadModules(dir).forEach(errors);
    }

    /** Executes the lines in order; blank lines and {@code #} comments are ignored. */
    int run(List<String> lines) {
        int code = 0;
        for (String line : lines) {
            if (line.isBlank() || line.strip().startsWith("#")) {
                continue;
            }
            NativeRun nativeBefore = session.lastNative().orElse(null);
            Outcome outcome = supervisor.run(line, () -> {
                interpreter.execute(line);
                return List.of();
            });
            if (session.exitRequest().isPresent()) {
                return session.exitRequest().getAsInt();
            }
            switch (outcome) {
                case Outcome.Success _ -> { }
                case Outcome.Failure(var error) -> {
                    errors.accept(error.message());
                    return 1;
                }
                case Outcome.Cancelled() -> {
                    return 130;
                }
                case Outcome.Abandoned _ -> {
                    return 1;
                }
            }
            if (session.lastSucceeded()) {
                code = 0;
            } else if (interpreter.hadBlockingError()) {
                return 1;
            } else {
                NativeRun nativeAfter = session.lastNative().orElse(null);
                code = nativeAfter != null && nativeAfter != nativeBefore && nativeAfter.exitCode() != null
                        && nativeAfter.exitCode() != 0 ? nativeAfter.exitCode() : 1;
            }
        }
        return code;
    }
}
