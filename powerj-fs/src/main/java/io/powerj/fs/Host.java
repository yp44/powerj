package io.powerj.fs;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The operating system the shell runs on, and what follows from it for files. <b>The one place</b> that looks at
 * {@code os.name} (and the one that finds the home folder in an environment), so that completion, the protection of
 * {@code rm}, the native programs and the environment all say the same thing about the same system.
 * <p>
 * Only the systems that behave differently are named: Linux and the other Unixes are what is left. The names of files
 * ignore case on Windows and on macOS (their usual file systems tell {@code docs} from {@code Docs} no more than a user
 * does) and count it elsewhere; the rule is by system, not by mount, so a case-sensitive APFS volume or a
 * case-insensitive mount on Linux is not told apart.
 */
public final class Host {

    /** Environment variables that designate the home folder, in order of priority. */
    private static final List<String> HOME_VARIABLES = List.of("HOME", "USERPROFILE");

    private static final String OS_NAME = System.getProperty("os.name", "");

    private Host() {
    }

    /** {@code true} on Windows. */
    public static boolean windows() {
        return windows(OS_NAME);
    }

    /** {@code true} on macOS. */
    public static boolean mac() {
        return mac(OS_NAME);
    }

    /** Do the names of files ignore case on the usual file systems of this system? Windows and macOS: yes. */
    public static boolean namesIgnoreCase() {
        return namesIgnoreCase(OS_NAME);
    }

    // The name of the system is a parameter below so that a test can try each of them.

    static boolean windows(String osName) {
        return startsWith(osName, "windows");
    }

    static boolean mac(String osName) {
        return startsWith(osName, "mac");
    }

    static boolean namesIgnoreCase(String osName) {
        return windows(osName) || mac(osName);
    }

    private static boolean startsWith(String osName, String prefix) {
        return osName != null && osName.toLowerCase(Locale.ROOT).startsWith(prefix);
    }

    /**
     * Home folder designated by an environment: {@code HOME}, else {@code USERPROFILE} (Windows), taken from the first
     * that is set to something that is a path, else {@code fallback}. Absolute and normalized.
     *
     * @param environment variables of the session (read with {@code get} only)
     * @param fallback    home folder to use when the environment designates none, such as {@code user.home}
     */
    public static Path home(Map<String, String> environment, Path fallback) {
        for (String name : HOME_VARIABLES) {
            String value = environment.get(name);
            if (value != null && !value.isBlank()) {
                try {
                    return Path.of(value.strip()).toAbsolutePath().normalize();
                } catch (InvalidPathException _) {
                    // not a path: try the next variable
                }
            }
        }
        return fallback.toAbsolutePath().normalize();
    }
}
