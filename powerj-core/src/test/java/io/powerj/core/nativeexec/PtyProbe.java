package io.powerj.core.nativeexec;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.powerj.core.nativeexec.PosixSpawn.Spec;
import io.powerj.core.nativeexec.PosixSpawn.Stdio;


/**
 * Issue #128: the process that {@code powerj-core/src/test/tools/pty-check.py} runs on a pseudo-terminal, as PowerJ runs on a
 * real one. Test tooling, run by hand. It starts a background command B in one of three ways, and a
 * companion C (a plain {@code sleep}, in the group of the JVM, so in the foreground group): the script then sends
 * Ctrl+C to the terminal and looks at which of B and C are still alive.
 * <pre>
 *   PtyProbe plain|shield|group|launcher null|inherit &lt;sh script of B&gt;
 * </pre>
 * {@code plain}: {@link ProcessBuilder}, as a Windows console program is started today; {@code shield}: through
 * {@code sh}, ignoring SIGINT and SIGQUIT, as a Unix background command is started today; {@code group}:
 * {@link PosixSpawn} with its own process group; {@code launcher}: {@link BackgroundLauncher}, what the shell runs in group
 * mode (its standard input is always the null device).
 */
public final class PtyProbe {

    private static final String SH_SHIELD = "trap '' INT QUIT; exec \"$0\" \"$@\"";

    private PtyProbe() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        boolean inherit = args[1].equals("inherit");
        String script = args[2];
        long background = switch (mode) {
            case "plain" -> startWithProcessBuilder(new ProcessBuilder("/bin/sh", "-c", script), inherit);
            case "shield" -> startWithProcessBuilder(new ProcessBuilder("/bin/sh", "-c", SH_SHIELD, "/bin/sh", "-c", script), inherit);
            case "group" -> PosixSpawnSupport.spawn(Spec.of(Path.of("/bin/sh"), "-c", script).group(PosixSpawn.OWN_GROUP)
                    .in(inherit ? Stdio.INHERIT : Stdio.NULL).out(inherit ? Stdio.INHERIT : Stdio.NULL)
                    .err(inherit ? Stdio.INHERIT : Stdio.NULL).closeFds(true)).pid();
            case "launcher" -> {
                BackgroundLauncher.Output output = inherit ? BackgroundLauncher.Output.inherit() : BackgroundLauncher.Output.discard();
                yield BackgroundLauncher.start(List.of(new BackgroundLauncher.Stage(Path.of("/bin/sh"), List.of("-c", script), false)),
                        Path.of("").toAbsolutePath(), Map.of("PATH", "/usr/bin:/bin"), output, output).getFirst().pid();
            }
            default -> throw new IllegalArgumentException(mode);
        };
        long companion = startWithProcessBuilder(new ProcessBuilder("/usr/bin/sleep", "60"), false);
        System.out.println("B=" + background + " C=" + companion);
        System.out.flush();
        Thread.sleep(30_000);
    }

    /** Starts the process with {@link ProcessBuilder} (what the JDK does for a command today), not with {@link PosixSpawn}. */
    private static long startWithProcessBuilder(ProcessBuilder builder, boolean inherit) throws IOException {
        if (inherit) {
            builder.inheritIO();
        } else {
            builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        }
        return builder.start().pid();
    }
}
