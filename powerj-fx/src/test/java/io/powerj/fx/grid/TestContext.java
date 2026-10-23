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
}
