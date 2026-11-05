package io.powerj.core.nativeexec.prototype;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import io.powerj.core.nativeexec.prototype.PosixSpawn.Spec;
import io.powerj.core.nativeexec.prototype.PosixSpawn.Stdio;

/**
 * PROTOTYPE of issue #128, not shipped: the process that {@code powerj-core/src/test/prototype/pty-check.py} runs on a
 * pseudo-terminal, as PowerJ runs on a real one. It starts a background command B in one of three ways, and a
 * companion C (a plain {@code sleep}, in the group of the JVM, so in the foreground group): the script then sends
 * Ctrl+C to the terminal and looks at which of B and C are still alive.
 * <pre>
 *   PtyProbe plain|shield|group null|inherit &lt;sh script of B&gt;
 * </pre>
 * {@code plain}: {@link ProcessBuilder}, as a Windows console program is started today; {@code shield}: through
 * {@code sh}, ignoring SIGINT and SIGQUIT, as a Unix background command is started today; {@code group}:
 * {@link PosixSpawn} with its own process group.
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
            case "plain" -> java(new ProcessBuilder("/bin/sh", "-c", script), inherit);
            case "shield" -> java(new ProcessBuilder("/bin/sh", "-c", SH_SHIELD, "/bin/sh", "-c", script), inherit);
            case "group" -> PosixSpawn.spawn(Spec.of(Path.of("/bin/sh"), "-c", script).group(PosixSpawn.OWN_GROUP)
                    .in(inherit ? Stdio.INHERIT : Stdio.NULL).out(inherit ? Stdio.INHERIT : Stdio.NULL)
                    .err(inherit ? Stdio.INHERIT : Stdio.NULL).closeFds(true)).pid();
            default -> throw new IllegalArgumentException(mode);
        };
        long companion = java(new ProcessBuilder("/usr/bin/sleep", "60"), false);
        System.out.println("B=" + background + " C=" + companion);
        System.out.flush();
        Thread.sleep(30_000);
    }

    private static long java(ProcessBuilder builder, boolean inherit) throws IOException {
        if (inherit) {
            builder.inheritIO();
        } else {
            builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        }
        return builder.start().pid();
    }
}
