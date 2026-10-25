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
 * <p>
 * The line comes Base64-encoded: Windows strips the double quotes of a command-line argument, and a PowerJ line is
 * full of them.
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
        interpreter.execute(new String(java.util.Base64.getDecoder().decode(args[0]), java.nio.charset.StandardCharsets.UTF_8));
        out.println(ALIVE + " exitRequest=" + session.exitRequest());
    }
}
