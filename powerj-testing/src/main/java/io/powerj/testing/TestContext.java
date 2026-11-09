package io.powerj.testing;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BiFunction;
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

    /** The accessors of a record class by exact name, looked up once per class (a test reads a million of them). */
    private static final ClassValue<Map<String, Method>> ACCESSORS = new ClassValue<>() {
        @Override
        protected Map<String, Method> computeValue(Class<?> type) {
            Map<String, Method> accessors = new HashMap<>();
            for (RecordComponent component : type.getRecordComponents()) {
                Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                accessors.put(component.getName(), accessor);
            }
            return accessors;
        }
    };

    /**
     * How {@link #readProperty} reads: by default {@linkplain #readDirectly the record or the map itself}; a test that has
     * to know who reads sets its own.
     */
    public BiFunction<Object, String, Object> reader = TestContext::readDirectly;

    @Override
    public Object readProperty(Object target, String name) {
        return reader.apply(target, name);
    }

    /**
     * Reads a property by its exact name, as the engine does: a component of a record or a key of a {@link Map}. This
     * context has no engine, so it reads the record itself; a test of the access between modules is a test of the image.
     * A failing accessor is reported as the engine does ({@code reading property 'x': cause}).
     */
    public static Object readDirectly(Object target, String name) {
        if (target instanceof Map<?, ?> map) {
            return map.get(name);
        }
        if (target instanceof Record record) {
            Method accessor = ACCESSORS.get(record.getClass()).get(name);
            if (accessor != null) {
                return read(accessor, record, name);
            }
        }
        throw new IllegalArgumentException("no property " + name + " in "
                + (target == null ? "null" : target.getClass().getName()));
    }

    private static Object read(Method accessor, Record record, String name) {
        try {
            return accessor.invoke(record);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof CancellationException cancelled) {
                throw cancelled;
            }
            throw new IllegalStateException("reading property '" + name + "': " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
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
