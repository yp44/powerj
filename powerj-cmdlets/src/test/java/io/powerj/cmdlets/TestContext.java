package io.powerj.cmdlets;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import io.powerj.api.CmdletContext;
import io.powerj.api.ScriptBlock;

/** In-memory context: collects the objects and the errors. */
final class TestContext<O> implements CmdletContext<O> {

    final List<O> emitted = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final Map<String, String> environment = new HashMap<>();
    private final Path cwd;

    TestContext(Path cwd) {
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
        return environment;
    }

    @Override
    public Optional<Object> variable(String name) {
        return Optional.empty();
    }

    /** Simulates a cmdlet placed after a {@code |}. */
    boolean hasInput;

    @Override
    public boolean hasInput() {
        return hasInput;
    }

    @Override
    public boolean cancelled() {
        return false;
    }

    /** Configuration folder given to the cmdlet ({@code null}: none). */
    Path configDirectory;

    @Override
    public Optional<Path> configDirectory() {
        return Optional.ofNullable(configDirectory);
    }

    /** Lines measured in perf mode, most recent first. */
    List<io.powerj.api.PerfReport> perfReports = List.of();

    @Override
    public List<io.powerj.api.PerfReport> perfReports() {
        return perfReports;
    }

    /** Number of calls to {@link #stopInput()}. */
    int stopInputCalls;

    @Override
    public void stopInput() {
        stopInputCalls++;
    }

    /** Simulated compilation: not available by default. */
    Function<String, ScriptBlock> compiler = source -> {
        throw new UnsupportedOperationException(source);
    };

    @Override
    public ScriptBlock compile(String expression) {
        return compiler.apply(expression);
    }

    /** State of the execution, by kind (what {@code CmdletRunner} offers a cmdlet in the shell). */
    private final Map<Class<?>, Object> states = new java.util.LinkedHashMap<>();

    @Override
    public <S> S state(Class<S> type, java.util.function.Supplier<? extends S> initial) {
        return type.cast(states.computeIfAbsent(type, _ -> initial.get()));
    }

    /** Closes the states that can be, as the shell does when the execution is over. */
    void closeStates() throws Exception {
        for (Object state : states.values().stream().toList().reversed()) {
            if (state instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
        states.clear();
    }
}
