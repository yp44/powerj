package io.powerj.shell;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.ExternalTerminal;

/** Terminal xterm en mémoire auquel on « tape » une suite de touches. */
final class TestTerminal implements AutoCloseable {

    static final String ENTER = "\r";
    static final String UP = "\033[A";
    static final String DOWN = "\033[B";
    static final String CTRL_C = "\003";
    static final String CTRL_D = "\004";
    static final String CTRL_R = "\022";

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final Terminal terminal;

    TestTerminal(String keys) {
        // Attributs fixés avant de lire la moindre touche : Ctrl+C doit lever le signal INT.
        var attributes = new Attributes();
        attributes.setLocalFlag(Attributes.LocalFlag.ISIG, true);
        attributes.setControlChar(Attributes.ControlChar.VINTR, 3);
        try {
            terminal = new ExternalTerminal(null, "test", "xterm-256color",
                    new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)), output,
                    StandardCharsets.UTF_8, Terminal.SignalHandler.SIG_DFL, true, attributes, new Size(160, 50));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Commence à lire les touches ; à appeler une fois le lecteur de ligne configuré. */
    void startTyping() {
        terminal.resume();
    }

    Terminal terminal() {
        return terminal;
    }

    /** Tout ce qui a été affiché, séquences d'échappement ANSI retirées. */
    String screen() {
        terminal.flush();
        return output.toString(StandardCharsets.UTF_8).replaceAll("\033\\[[0-9;?]*[A-Za-z]", "");
    }

    @Override
    public void close() throws IOException {
        terminal.close();
    }
}
