package io.powerj.shell;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import io.powerj.api.Language;
import io.powerj.core.runtime.StandardInput;

/**
 * What the shell reads from the process around it, gathered once at startup: the variables and properties, the folders,
 * the language of the system and the three standard streams. Nothing below {@link Main} reads {@code System} again,
 * which is what lets {@link ShellBootstrap} start a shell in a test with a folder, a few streams and nothing else.
 *
 * @param variables        environment variables
 * @param properties       the system properties that matter: {@code powerj.language}, {@code stdout.encoding},
 *                         {@code native.encoding}
 * @param userHome         the user's home folder
 * @param workingDirectory current directory at startup
 * @param systemLocale     language of the system, last resort for the language of the messages
 * @param stdin            standard input
 * @param stdout           standard output, as bytes
 * @param stderr           standard error, as bytes
 * @param messages         where the shell talks to the user before its own output exists (startup problems), and where
 *                         it reports a usage error
 * @param console          the output is an interactive console, not a file or a pipe
 * @param stdinIsTerminal  the input is a keyboard, not a file or a pipe
 */
record Environment(Map<String, String> variables, Map<String, String> properties, Path userHome,
                   Path workingDirectory, Locale systemLocale, InputStream stdin, OutputStream stdout,
                   OutputStream stderr, PrintStream messages, boolean console, boolean stdinIsTerminal) {

    static final String STDOUT_ENCODING = "stdout.encoding";
    static final String NATIVE_ENCODING = "native.encoding";

    Environment {
        Objects.requireNonNull(variables, "variables");
        Objects.requireNonNull(properties, "properties");
        userHome = Objects.requireNonNull(userHome, "userHome");
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory").toAbsolutePath();
    }

    /** The real process: its variables, folders and standard streams. */
    static Environment system() {
        var console = System.console();
        Map<String, String> properties = new java.util.HashMap<>();
        for (String name : new String[] {Language.PROPERTY, STDOUT_ENCODING, NATIVE_ENCODING}) {
            String value = System.getProperty(name);
            if (value != null) {
                properties.put(name, value);
            }
        }
        return new Environment(System.getenv(), Map.copyOf(properties), Path.of(System.getProperty("user.home")),
                Path.of(""), Locale.getDefault(Locale.Category.DISPLAY), System.in,
                new FileOutputStream(FileDescriptor.out), new FileOutputStream(FileDescriptor.err), System.err,
                console != null && console.isTerminal(), StandardInput.isTerminal());
    }

    /** Character set of the console when there is one, otherwise UTF-8 (a file or a pipe is read as UTF-8). */
    Charset outputCharset() {
        if (!console) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(properties.getOrDefault(STDOUT_ENCODING, properties.getOrDefault(NATIVE_ENCODING, "UTF-8")));
        } catch (RuntimeException _) {
            return StandardCharsets.UTF_8;
        }
    }

    PrintWriter stdoutWriter() {
        return new PrintWriter(new OutputStreamWriter(stdout, outputCharset()), true);
    }

    PrintWriter stderrWriter() {
        return new PrintWriter(new OutputStreamWriter(stderr, outputCharset()), true);
    }

    /** Width of the console ({@code COLUMNS}), 120 by default. */
    int columns() {
        try {
            return Integer.parseInt(variables.getOrDefault("COLUMNS", "120"));
        } catch (NumberFormatException _) {
            return 120;
        }
    }
}
