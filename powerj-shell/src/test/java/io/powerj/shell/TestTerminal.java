package io.powerj.shell;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
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

    private final PipedOutputStream keyboard;

    /** Terminal dont toutes les touches sont connues d'avance. */
    TestTerminal(String keys) {
        this(new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)), null);
    }

    /** Terminal « au clavier » : les touches sont envoyées au fil de l'eau avec {@link #type(String)}. */
    static TestTerminal interactive() {
        try {
            var keyboard = new PipedOutputStream();
            return new TestTerminal(new PipedInputStream(keyboard, 4096), keyboard);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private TestTerminal(InputStream keys, PipedOutputStream keyboard) {
        this.keyboard = keyboard;
        // Attributs fixés avant de lire la moindre touche : Ctrl+C doit lever le signal INT.
        var attributes = new Attributes();
        attributes.setLocalFlag(Attributes.LocalFlag.ISIG, true);
        attributes.setControlChar(Attributes.ControlChar.VINTR, 3);
        try {
            terminal = new ExternalTerminal(null, "test", "xterm-256color",
                    keys, output,
                    StandardCharsets.UTF_8, Terminal.SignalHandler.SIG_DFL, true, attributes, new Size(160, 50));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Commence à lire les touches ; à appeler une fois le lecteur de ligne configuré. */
    void startTyping() {
        terminal.resume();
    }

    /** Tape des touches (terminal interactif uniquement). */
    void type(String keys) {
        try {
            keyboard.write(keys.getBytes(StandardCharsets.UTF_8));
            keyboard.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Ferme le clavier : le lecteur de ligne verra la fin de l'entrée. */
    void endOfInput() {
        try {
            keyboard.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
