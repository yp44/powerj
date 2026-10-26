package io.powerj.shell;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.WriterOutputStream;

/** The terminal of the process: JLine's system terminal, which also takes over {@code System.out} and {@code System.err}. */
final class SystemTerminals {

    private SystemTerminals() {
    }

    static Terminal open(Environment environment) throws IOException {
        var builder = TerminalBuilder.builder().system(true).name("PowerJ");
        // Redirected input or output (file, pipe): UTF-8, whatever the system code page.
        if (!environment.console()) {
            builder.encoding(StandardCharsets.UTF_8).stdinEncoding(StandardCharsets.UTF_8)
                    .stdoutEncoding(StandardCharsets.UTF_8).stderrEncoding(StandardCharsets.UTF_8);
        }
        Terminal terminal = builder.build();
        // The terminal is restored (raw mode disabled) even on an abrupt shutdown (FR-59).
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> InteractiveRun.close(terminal)));
        // System.out.println(…) called from a Java expression goes through the JLine terminal (FR-58).
        System.setOut(stream(terminal));
        System.setErr(stream(terminal));
        return terminal;
    }

    private static PrintStream stream(Terminal terminal) {
        return new PrintStream(new WriterOutputStream(terminal.writer(), terminal.encoding()), true,
                terminal.encoding());
    }
}
