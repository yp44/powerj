package io.powerj.core.exec;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The variables the shell defines itself: {@code $?}, {@code $exit}, {@code $last}, {@code $pwd}, {@code $home},
 * {@code $errors}, {@code $perf}. They take precedence over a variable of the same name set by the user. The value of
 * each one is read here, with a {@code switch} that the compiler checks is exhaustive.
 */
enum AutomaticVariable {

    /** Did the last command succeed? */
    SUCCEEDED("?"),
    /** Exit code of the last native command. */
    EXIT("exit"),
    /** The last native command. */
    LAST("last"),
    /** Current directory. */
    PWD("pwd"),
    /** Home directory at startup. */
    HOME("home"),
    /** Recent Java exceptions (FR-53). */
    ERRORS("errors"),
    /** Report of the last measured line (FR-76). */
    PERF("perf");

    private static final Map<String, AutomaticVariable> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(AutomaticVariable::variableName, Function.identity()));

    private final String variableName;

    AutomaticVariable(String variableName) {
        this.variableName = variableName;
    }

    /** The name after the {@code $}. */
    String variableName() {
        return variableName;
    }

    /** The automatic variable with this name, if there is one. */
    static Optional<AutomaticVariable> named(String name) {
        return Optional.ofNullable(BY_NAME.get(name));
    }

    /** The value of this variable, from the session's state and what it remembers. */
    Object valueIn(SessionState state, ShellStatistics statistics) {
        NativeRun last = statistics.lastNative().orElse(null); // read once: another thread may record a new one
        return switch (this) {
            case SUCCEEDED -> statistics.lastSucceeded();
            case EXIT -> last == null ? null : last.exitCode();
            case LAST -> last;
            case PWD -> state.currentDirectory();
            case HOME -> state.startupHome();
            case ERRORS -> statistics.recentErrors();
            case PERF -> statistics.perfReports().stream().findFirst().orElse(null);
        };
    }

    /** The names of all of them, in declaration order (completion). */
    static List<String> names() {
        return Arrays.stream(values()).map(AutomaticVariable::variableName).toList();
    }
}
