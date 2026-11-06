package io.powerj.core.nativeexec;

import java.util.List;
import java.util.Map;

/**
 * The two strings that {@code CreateProcessW} wants and that {@code ProcessBuilder} builds for us otherwise, the
 * command line and the environment block (issue #128). Pure, so that it is tested on
 * every system.
 * <p>
 * The command line follows the rules that {@code CommandLineToArgvW} and the C runtime read back (2n backslashes before a
 * quote are n backslashes and the quote closes; 2n+1 are n backslashes and a literal quote; backslashes elsewhere are
 * literal), as described in "Everyone quotes command line arguments the wrong way". It is meant for programs that read
 * their command line that way. {@code cmd.exe} and the batch files read it by other rules: they are not started this way
 * ({@link WindowsSpawn} refuses them).
 */
final class WindowsCommandLine {

    private WindowsCommandLine() {
    }

    /** The program, always quoted, then its arguments. The program is also given to {@code CreateProcessW} apart. */
    static String of(String executable, List<String> args) {
        StringBuilder line = new StringBuilder().append('"').append(executable).append('"');
        for (String arg : args) {
            line.append(' ').append(quote(arg));
        }
        return line.toString();
    }

    /** One argument, quoted only if it has to be. */
    static String quote(String arg) {
        if (arg.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("an argument cannot hold a NUL character");
        }
        if (!arg.isEmpty() && arg.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '"')) {
            return arg;
        }
        StringBuilder quoted = new StringBuilder("\"");
        int backslashes = 0;
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\\') {
                backslashes++;
            } else if (c == '"') {
                quoted.append("\\".repeat(backslashes * 2 + 1)).append('"');
                backslashes = 0;
            } else {
                quoted.append("\\".repeat(backslashes)).append(c);
                backslashes = 0;
            }
        }
        // The backslashes before the closing quote must not escape it.
        return quoted.append("\\".repeat(backslashes * 2)).append('"').toString();
    }

    /**
     * The environment block: {@code NAME=value} entries each ended by NUL, sorted by name without regard to case as Windows
     * wants, and a last NUL. It is passed with {@code CREATE_UNICODE_ENVIRONMENT}, so as UTF-16.
     */
    static String environmentBlock(Map<String, String> environment) {
        if (environment.isEmpty()) {
            return "\0\0";
        }
        StringBuilder block = new StringBuilder();
        environment.entrySet().stream().sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> block.append(entry.getKey()).append('=').append(entry.getValue()).append('\0'));
        return block.append('\0').toString();
    }
}
