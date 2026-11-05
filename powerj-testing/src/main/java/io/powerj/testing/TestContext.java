package io.powerj.testing;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import io.powerj.api.CmdletContext;
import io.powerj.api.ScriptBlock;
import io.powerj.api.perf.PerfReport;
import io.powerj.api.perf.PerfReports;

/**
 * In-memory {@link CmdletContext} for the tests of a cmdlet: it collects what the cmdlet emits and the errors it reports,
 * and everything else is a field that the test sets before the run (#98). Not thread-safe.
 *
 * @param <O> type of the objects the cmdlet under test emits
 */
public final class TestContext<O> implements CmdletContext<O> {

    /** The objects emitted, in order. */
    public final List<O> emitted = new ArrayList<>();
    /** The non-blocking errors reported, in order. */
    public final List<String> errors = new ArrayList<>();
    /** The environment of the shell; a test can fill it directly. */
    public final Map<String, String> environment = new HashMap<>();
    /** Simulates a cmdlet placed after a {@code |}. */
    public boolean hasInput;
    /** Number of calls to {@link #stopInput()}. */
    public int stopInputCalls;
    /** Objects that the last {@link CmdletDriver} run sent to the cmdlet: fewer than it was given if it stopped its input. */
    public int objectsSent;
    /** Configuration folder given to the cmdlet ({@code null}: none). */
    public Path configDirectory;
    /** Lines measured in perf mode, most recent first. */
    public List<PerfReport> perfReports = List.of();
    /** Simulated compilation: refused by default, the message is the source. */
    public Function<String, ScriptBlock> compiler = source -> {
        throw new UnsupportedOperationException(source);
    };

    private final Path cwd;
    /** State of the execution, by kind (what the shell offers a cmdlet). */
    private final Map<Class<?>, Object> states = new LinkedHashMap<>();

    /** A context whose current directory is {@code .}. */
    public TestContext() {
        this(Path.of("."));
    }

    /** A context whose current directory is {@code cwd}. */
    public TestContext(Path cwd) {
        this.cwd = cwd;
    }

    @Override
    public void emit(O value) {
        emitted.add(value);
    }

    @Override
    public void error(String message) {
        errors.add(message);
    }

    @Override
    public Path currentDirectory() {
        return cwd;
    }

    @Override
    public Map<String, String> environment() {
        return Collections.unmodifiableMap(environment);
    }

    @Override
    public void setEnvironment(String name, String value) {
        environment.put(name, value);
    }

    @Override
    public boolean unsetEnvironment(String name) {
        return environment.remove(name) != null;
    }

    @Override
    public Optional<Object> variable(String name) {
        return Optional.empty();
    }

    @Override
    public boolean hasInput() {
        return hasInput;
    }

    @Override
    public boolean cancelled() {
        return false;
    }

    @Override
    public Optional<Path> configDirectory() {
        return Optional.ofNullable(configDirectory);
    }

    @Override
    public <S> Optional<S> service(Class<S> type) {
        return type == PerfReports.class ? Optional.of(type.cast((PerfReports) () -> perfReports)) : Optional.empty();
    }

    @Override
    public void stopInput() {
        stopInputCalls++;
    }

    @Override
    public ScriptBlock compile(String expression) {
        return compiler.apply(expression);
    }

    @Override
    public <S> S state(Class<S> type, Supplier<? extends S> initial) {
        return type.cast(states.computeIfAbsent(type, _ -> initial.get()));
    }

    /** Closes the states that can be, last created first, as the shell does when the execution is over. */
    public void closeStates() throws Exception {
        for (Object state : states.values().stream().toList().reversed()) {
            if (state instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
        states.clear();
    }
}
