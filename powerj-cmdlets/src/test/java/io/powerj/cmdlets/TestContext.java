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

    @Override
    public boolean cancelled() {
        return false;
    }

    /** Simulated compilation: not available by default. */
    Function<String, ScriptBlock> compiler = source -> {
        throw new UnsupportedOperationException(source);
    };

    @Override
    public ScriptBlock compile(String expression) {
        return compiler.apply(expression);
    }
}
