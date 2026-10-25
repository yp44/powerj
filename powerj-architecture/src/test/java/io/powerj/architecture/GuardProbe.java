package io.powerj.architecture;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;

import io.powerj.core.exec.CmdletRegistry;
import io.powerj.core.exec.Interpreter;
import io.powerj.core.exec.Session;
import io.powerj.core.exec.ShellIo;

/**
 * Runs one PowerJ line in a throw-away JVM, then says it is still alive. Used by {@link ExitGuardTest}: a line that
 * really calls {@code System.exit} ends this process before the sentinel is printed, without ending the test JVM.
 */
public final class GuardProbe {

    static final String ALIVE = "STILL-ALIVE";

    private GuardProbe() {
    }

    public static void main(String[] args) throws Exception {
        PrintWriter out = new PrintWriter(System.out, true);
        Session session = new Session(Path.of(System.getProperty("user.home")), Path.of("").toAbsolutePath(), Map.of());
        Interpreter interpreter = new Interpreter(session, new ShellIo(out, out::println, false, () -> 120), Map.of(),
                CmdletRegistry.discover());
        interpreter.execute(args[0]);
        out.println(ALIVE + " exitRequest=" + session.exitRequest());
    }
}
