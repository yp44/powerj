package io.powerj.core.exec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Function;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.ScriptBlock;

/** Runs a cmdlet: option binding, common option {@code --on-error} (FR-42), context. */
final class CmdletRunner {

    /** Handling of non-blocking errors. */
    enum OnError { STOP, CONTINUE, SILENT }

    private CmdletRunner() {
    }

    /**
     * @param input    objects received from the pipeline, or {@code null} for the first stage
     * @param compiler compiles the text of a block ({@link CmdletContext#compile})
     * @param sink     receives each produced object
     * @return {@code true} if no error was reported
     */
    static boolean run(CmdletRegistry.Registered registered, List<Object> args, Session session,
                       Consumer<String> errors, Consumer<Object> sink, Source input,
                       Function<String, ScriptBlock> compiler) throws Exception {
        List<Object> remaining = new ArrayList<>(args);
        OnError onError = extractOnError(registered.name(), remaining);
        Record params = OptionBinder.bind(registered.name(), registered.parameters(), remaining);
        var context = new Context(registered.name(), session, errors, sink, onError, compiler);
        try {
            invoke(registered, params, context, input);
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
            while ((value = input.next()) != Source.END) {
                if (value != null && !expected.isInstance(value)) {
                    context.error(Messages.get("cmdlet.inputIgnored", value.getClass().getSimpleName(),
                            expected.getSimpleName()));
                    continue;
                }
                cmdlet.process((P) params, (I) value, typedContext);
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

    /** Context provided to the cmdlet. */
    private static final class Context implements CmdletContext<Object> {

        private final String command;
        private final Session session;
        private final Consumer<String> errors;
        private final Consumer<Object> sink;
        private final OnError onError;
        private final Function<String, ScriptBlock> compiler;
        private boolean hadErrors;

        Context(String command, Session session, Consumer<String> errors, Consumer<Object> sink, OnError onError,
                Function<String, ScriptBlock> compiler) {
            this.command = command;
            this.session = session;
            this.errors = errors;
            this.sink = sink;
            this.onError = onError;
            this.compiler = compiler;
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
            return session.environment();
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
        public boolean cancelled() {
            return Thread.currentThread().isInterrupted();
        }

        @Override
        public ScriptBlock compile(String expression) {
            return compiler.apply(expression);
        }
    }
}
