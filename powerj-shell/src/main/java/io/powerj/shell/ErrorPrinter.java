package io.powerj.shell;

import java.io.PrintWriter;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

/**
 * The PowerJ error stream of the interactive shell: one line per message, in red on a terminal that supports colors, and
 * prefixed ({@code profile.pj:3: }) while the profile runs so that the user knows which line it comes from.
 */
final class ErrorPrinter {

    private final Terminal terminal;
    private final PrintWriter out;
    /** Prefix of the error lines while the profile runs, otherwise {@code null}. */
    private volatile String prefix;

    ErrorPrinter(Terminal terminal, PrintWriter out) {
        this.terminal = terminal;
        this.out = out;
    }

    /** Prefixes the following messages, or {@code null} to stop doing so. */
    void prefix(String prefix) {
        this.prefix = prefix;
    }

    void print(String message) {
        String current = prefix;
        String text = current == null ? message : current + message;
        synchronized (out) {
            out.println(new AttributedString(text, AttributedStyle.DEFAULT.foreground(AttributedStyle.RED))
                    .toAnsi(terminal));
            out.flush();
        }
    }
}
