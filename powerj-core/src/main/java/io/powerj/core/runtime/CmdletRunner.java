package io.powerj.core.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.ScriptBlock;
import io.powerj.api.perf.PerfReports;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.command.OptionBinder;
import io.powerj.core.error.PjError;
import io.powerj.core.error.PjException;
import io.powerj.core.format.Values;
import io.powerj.core.i18n.Messages;
import io.powerj.core.java.PreviewGuard;

/** Runs a cmdlet: option binding, common option {@code --on-error} (FR-42), context. */
public final class CmdletRunner {

    /** Handling of non-blocking errors. */
    enum OnError { STOP, CONTINUE, SILENT }

    private CmdletRunner() {
    }

    /**
     * What a cmdlet is connected to.
     *
     * @param input  objects received from the pipeline, or {@code null} for the first stage
     * @param output receives each produced object
     * @param errors receives the non-blocking errors
     */
    public record Connections(Source input, Consumer<Object> output, Consumer<String> errors) { }

    /**
     * @param compiler compiles the text of a block ({@link CmdletContext#compile})
     * @return {@code true} if no error was reported
     */
    public static boolean run(CmdletRegistry.Registered registered, List<Object> args, Session session,
                       Connections connections, Function<String, ScriptBlock> compiler) throws Exception {
        List<Object> remaining = new ArrayList<>(args);
        OnError onError = extractOnError(registered.name(), remaining);
        Record params = OptionBinder.bind(registered.name(), registered.parameters(), remaining);
        if (PreviewGuard.active() && !registered.previewable(params)) {
            PreviewGuard.deny(Messages.get("preview.off.cmdletOptions", registered.name()));
        }
        var context = new Context(registered.name(), session, connections, onError, compiler);
        try (context) { // releases the state of the execution, however it ends (review C-01)
            invoke(registered, params, context, connections.input());
        } catch (PjException | CancellationException | InterruptedException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            // Convention: a cmdlet reports incorrect usage with IllegalArgumentException.
            throw new PjException(PjError.of(Messages.get("command.error", registered.name(), e.getMessage()), e));
        }
        return !context.hadErrors;
    }

    @SuppressWarnings("unchecked")
    private static <P extends Record, I, O> void invoke(CmdletRegistry.Registered registered, Record params,
                                                        Context context, Source input) throws Exception {
        var cmdlet = (Cmdlet<P, I, O>) registered.cmdlet();
        var typedContext = (CmdletContext<O>) context;
        cmdlet.begin((P) params, typedContext);
        if (input != null) {
            Class<?> expected = registered.input();
            Object value;
            while (!context.inputStopped && (value = input.next()) != Source.END) {
                if (value != null && !expected.isInstance(value)) {
                    context.error(Messages.get("cmdlet.inputIgnored", value.getClass().getSimpleName(),
                            expected.getSimpleName()));
                    continue;
                }
                cmdlet.process((P) params, (I) value, typedContext);
            }
            if (context.inputStopped) {
                // FR-68b: the previous stage is stopped; its interruption is not an error.
                input.abort();
            }
        }
        cmdlet.end((P) params, typedContext);
    }

    private static OnError extractOnError(String command, List<Object> args) {
        OnError result = OnError.CONTINUE;
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i) instanceof String word && (word.equals("--on-error") || word.startsWith("--on-error="))) {
                String value;
                if (word.contains("=")) {
                    value = word.substring(word.indexOf('=') + 1);
                    args.remove(i);
                } else {
                    if (i + 1 >= args.size()) {
                        throw new PjException(Messages.get("onError.valueExpected", command));
                    }
                    value = Values.text(args.get(i + 1));
                    args.remove(i + 1);
                    args.remove(i);
                }
                result = switch (value) {
                    case "stop" -> OnError.STOP;
                    case "continue" -> OnError.CONTINUE;
                    case "silent" -> OnError.SILENT;
                    default -> throw new PjException(Messages.get("onError.invalid", command));
                };
                i--;
            }
        }
        return result;
    }

    /** Context provided to the cmdlet; closing it closes the state of the execution. */
    private static final class Context implements CmdletContext<Object>, AutoCloseable {

        private final String command;
        private final Session session;
        private final Consumer<String> errors;
        private final Consumer<Object> sink;
        private final OnError onError;
        private final Function<String, ScriptBlock> compiler;
        private final boolean hasInput;
        private boolean hadErrors;
        private volatile boolean inputStopped;
        /** State of the execution by kind, in order of creation (guarded by itself). */
        private final Map<Class<?>, Object> states = new LinkedHashMap<>();

        Context(String command, Session session, Connections connections, OnError onError,
                Function<String, ScriptBlock> compiler) {
            this.command = command;
            this.session = session;
            this.errors = connections.errors();
            this.sink = connections.output();
            this.onError = onError;
            this.compiler = compiler;
            this.hasInput = connections.input() != null;
        }

        @Override
        public void emit(Object value) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException();
            }
            sink.accept(value);
        }

        @Override
        public void error(String message) {
            hadErrors = true;
            switch (onError) {
                case STOP -> throw new PjException(Messages.get("command.error", command, message));
                case CONTINUE -> errors.accept(Messages.get("command.error", command, message));
                case SILENT -> { }
            }
        }

        @Override
        public Path currentDirectory() {
            return session.currentDirectory();
        }

        @Override
        public Map<String, String> environment() {
            return session.environmentSnapshot();
        }

        @Override
        public void setEnvironment(String name, String value) {
            session.environment().put(name, value);
        }

        @Override
        public boolean unsetEnvironment(String name) {
            return session.environment().remove(name) != null;
        }

        @Override
        public Optional<Object> variable(String name) {
            try {
                return Optional.ofNullable(session.variable(name));
            } catch (PjException _) {
                return Optional.empty();
            }
        }

        @Override
        public boolean hasInput() {
            return hasInput;
        }

        @Override
        public boolean cancelled() {
            return Thread.currentThread().isInterrupted();
        }

        @Override
        public ScriptBlock compile(String expression) {
            return compiler.apply(expression);
        }

        @Override
        public void stopInput() {
            inputStopped = true;
        }

        @Override
        public <S> S state(Class<S> type, Supplier<? extends S> initial) {
            synchronized (states) {
                Object state = states.get(type);
                if (state == null) {
                    state = Objects.requireNonNull(initial.get(), "the initial state of " + type.getName());
                    states.put(type, state);
                }
                return type.cast(state);
            }
        }

        /** Closes the states that can be, last created first; the first failure is thrown, the others added to it. */
        @Override
        public void close() throws Exception {
            List<Object> created;
            synchronized (states) {
                created = new ArrayList<>(states.values());
                states.clear();
            }
            Collections.reverse(created);
            Exception failure = null;
            for (Object state : created) {
                if (state instanceof AutoCloseable closeable) {
                    try {
                        closeable.close();
                    } catch (Exception e) {
                        if (failure == null) {
                            failure = e;
                        } else {
                            failure.addSuppressed(e);
                        }
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public Optional<Path> configDirectory() {
            return session.configDirectory();
        }

        @Override
        public <S> Optional<S> service(Class<S> type) {
            return type == PerfReports.class ? Optional.of(type.cast((PerfReports) session::perfReports))
                    : Optional.empty();
        }
    }
}
