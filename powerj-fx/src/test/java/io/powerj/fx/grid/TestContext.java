package io.powerj.fx.grid;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.powerj.api.CmdletContext;
import io.powerj.api.ScriptBlock;
import io.powerj.fx.model.Reflect;

/** In-memory context of a cmdlet. */
final class TestContext<O> implements CmdletContext<O> {

    final List<O> emitted = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    boolean hasInput = true;
    int stopInputCalls;
    Path configDirectory;

    @Override
    public java.util.Optional<Path> configDirectory() {
        return java.util.Optional.ofNullable(configDirectory);
    }

    @Override
    public void stopInput() {
        stopInputCalls++;
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
        return Path.of(".");
    }

    @Override
    public Map<String, String> environment() {
        return new HashMap<>();
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

    /** {@code $_.name}, read by reflection: what the shell does for a property. */
    @Override
    public ScriptBlock compile(String expression) {
        if (!expression.startsWith("$_.")) {
            throw new IllegalArgumentException(expression);
        }
        String name = expression.substring(3);
        return new ScriptBlock() {
            @Override
            public Object invoke(Object current) {
                return Reflect.read(current, name);
            }

            @Override
            public String source() {
                return expression;
            }
        };
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
