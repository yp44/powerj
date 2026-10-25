package io.powerj.core.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SortedSet;
import java.util.TreeSet;

import io.powerj.api.PerfReport;
import io.powerj.core.java.JavaClasses;
import io.powerj.core.nativeexec.NativeRun;

/**
 * State of a shell session. It is what the interpreter, the cmdlets and the shell hold on to, and it is made of
 * {@link SessionState} (directories and environment), {@link Variables} (what the user defines),
 * {@link ShellStatistics} (what happened: last command, recent errors, measured lines), the Java classes, the jobs and
 * the exit request.
 * The command thread changes it while the stages of a pipeline, a background job or the completion read it; each part
 * says how it is guarded (review C-13).
 */
public final class Session {

    private final SessionState state;
    private final Variables variables = new Variables();
    private final ShellStatistics statistics = new ShellStatistics();
    private final JavaClasses java = new JavaClasses();
    private final Jobs jobs = new Jobs();
    private volatile Integer exitRequest;

    /**
     * @param home             user home directory, used for {@code ~} when the environment has neither
     *                         {@code HOME} nor {@code USERPROFILE}
     * @param currentDirectory initial current directory
     * @param environment      initial environment (copied)
     */
    public Session(Path home, Path currentDirectory, Map<String, String> environment) {
        this.state = new SessionState(home, currentDirectory, environment);
    }

    /** Home directory, designated by {@code ~} (FR-62): {@code HOME}, else {@code USERPROFILE}, else the startup one. */
    public Path home() {
        return state.home();
    }

    /** Tilde expansion of an unquoted word (FR-62): {@code ~} and {@code ~/x}; any other word is unchanged. */
    public String expandTilde(String word) {
        return state.expandTilde(word);
    }

    public Path currentDirectory() {
        return state.currentDirectory();
    }

    public Optional<Path> previousDirectory() {
        return state.previousDirectory();
    }

    /** Changes the current directory; the directory must exist. */
    public void changeDirectory(Path target) {
        state.changeDirectory(target);
    }

    /** PowerJ configuration folder (§8), given to the cmdlets ({@code CmdletContext.configDirectory()}). */
    public Optional<Path> configDirectory() {
        return state.configDirectory();
    }

    public void configDirectory(Path directory) {
        state.configDirectory(directory);
    }

    /**
     * Environment of the session (modifiable). Each operation on the map is atomic; iterating over it, or copying it,
     * must be done while holding its lock ({@code synchronized (map)}), or on {@link #environmentSnapshot()}.
     */
    public Map<String, String> environment() {
        return state.environment();
    }

    /** A consistent copy of the environment that nobody changes: what a native command starts with. */
    public Map<String, String> environmentSnapshot() {
        return state.environmentSnapshot();
    }

    public void setVariable(String name, Object value) {
        variables.set(name, value);
    }

    /** Names of the defined and automatic variables, sorted (completion). */
    public SortedSet<String> variableNames() {
        var names = new TreeSet<>(variables.names());
        names.addAll(AutomaticVariable.names());
        return names;
    }

    /** Value of a variable, automatic ({@code $exit}, {@code $last}, {@code $?}…) or defined. */
    public Object variable(String name) {
        Optional<AutomaticVariable> automatic = AutomaticVariable.named(name);
        return automatic.isPresent() ? automatic.get().valueIn(state, statistics) : variables.get(name);
    }

    /** {@code $debug}: show the Java stack trace of errors (FR-43). */
    public boolean debug() {
        return variables.debug();
    }

    public Optional<NativeRun> lastNative() {
        return statistics.lastNative();
    }

    void recordNative(NativeRun run) {
        statistics.recordNative(run);
    }

    public boolean lastSucceeded() {
        return statistics.lastSucceeded();
    }

    void recordSuccess(boolean succeeded) {
        statistics.recordSuccess(succeeded);
    }

    /** Keeps the original exception of an error for {@code $errors} (FR-53). */
    public void recordError(Throwable error) {
        statistics.recordError(error);
    }

    public List<Throwable> recentErrors() {
        return statistics.recentErrors();
    }

    /** Number of the next line measured in perf mode. */
    public int nextPerfLine() {
        return statistics.nextPerfLine();
    }

    /** Keeps the report of a measured line, for {@code perf} and {@code $perf} (FR-76). */
    public void addPerfReport(PerfReport report) {
        statistics.addPerfReport(report);
    }

    /** The lines measured in perf mode, most recent first. */
    public List<PerfReport> perfReports() {
        return statistics.perfReports();
    }

    /** Accessible Java classes and imports (FR-47). */
    public JavaClasses java() {
        return java;
    }

    /** Commands launched in the background with {@code &} (FR-75). */
    public Jobs jobs() {
        return jobs;
    }

    /** Requests that the shell close with this code ({@code exit}). */
    public void requestExit(int code) {
        exitRequest = code;
    }

    public OptionalInt exitRequest() {
        Integer code = exitRequest;
        return code == null ? OptionalInt.empty() : OptionalInt.of(code);
    }
}
