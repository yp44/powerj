package io.powerj.core.exec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import io.powerj.core.lang.Ast;
import io.powerj.core.lang.Ast.Argument;
import io.powerj.core.lang.Ast.Statement;
import io.powerj.core.lang.ExpressionParser;
import io.powerj.core.lang.Parser;
import io.powerj.core.lang.SyntaxException;
import io.powerj.core.lang.Token;

/**
 * Executes a line: statements chained by {@code ;}, {@code &&}, {@code ||} (FR-04c), pipelines
 * {@code |}, built-in commands, cmdlets, native commands, expressions, assignments and redirections. Resolution order
 * of a command (FR-13): built-in command, cmdlet, program on the {@code PATH}; {@code ^nom} forces the
 * program.
 */
public final class Interpreter {

    /** Width of the tables written to a file: no truncation. */
    private static final int FILE_WIDTH = 10_000;

    /** Maximum wait for the stages of a cancelled pipeline to stop. */
    private static final int JOIN_SECONDS = 5;

    private final Session session;
    private final ShellIo io;
    private final Map<String, Builtin> builtins = new LinkedHashMap<>();
    private final CmdletRegistry registry;
    private final ModuleLoader modules;
    private final CommandResolver resolver;
    private final NativeRunner nativeRunner;
    private final Evaluator evaluator;
    /** Standard input lines in non-interactive mode, read by the first stage; otherwise {@code null}. */
    private Source standardInput;
    private boolean inheritStandardInput;
    /** A blocking error occurred during the last line (non-interactive mode: the script stops). */
    private volatile boolean blockingError;

    /**
     * @param extraBuiltins built-in commands provided by the shell (e.g. {@code history})
     */
    public Interpreter(Session session, ShellIo io, Map<String, Builtin> extraBuiltins, CmdletRegistry registry) {
        this(session, io, extraBuiltins, registry, new CommandResolver(), new NativeRunner());
    }

    Interpreter(Session session, ShellIo io, Map<String, Builtin> extraBuiltins, CmdletRegistry registry,
                CommandResolver resolver, NativeRunner nativeRunner) {
        this.session = session;
        this.io = io;
        this.registry = registry;
        this.resolver = resolver;
        this.nativeRunner = nativeRunner;
        this.evaluator = new Evaluator(session, this::capture);
        builtins.put("cd", Builtins::cd);
        builtins.put("pwd", Builtins::pwd);
        builtins.put("exit", Builtins::exit);
        builtins.put("which", this::which);
        builtins.put("help", (args, _) -> Help.run(args, registry, session.java()));
        builtins.put("import", (args, _) -> importClasses(args));
        builtins.put("mod-load", this::modLoad);
        builtins.put("mod-list", this::modList);
        builtins.putAll(extraBuiltins);
        this.modules = new ModuleLoader(registry, java.util.Collections.unmodifiableSet(builtins.keySet()));
    }

    /**
     * Loads the third-party modules from the directory (at startup, §4.4).
     *
     * @return warnings to display (invalid module, conflicting name)
     */
    public List<String> loadModules(Path dir) {
        return modules.loadAll(dir).stream().flatMap(r -> r.warnings().stream()).toList();
    }

    /** {@code mod-load <chemin.jar>}: loads a module at runtime. */
    private List<Object> modLoad(List<Object> args, Session session) {
        if (args.isEmpty()) {
            throw new PjException("mod-load : chemin d'un module (.jar ou dossier) attendu");
        }
        List<Object> loaded = new ArrayList<>();
        for (Object arg : args) {
            String text = Values.text(arg);
            Path path = text.equals("~") ? session.home()
                    : text.startsWith("~/") || text.startsWith("~\\") ? session.home().resolve(text.substring(2))
                    : session.currentDirectory().resolve(text);
            ModuleLoader.Result result = modules.load(path);
            result.warnings().forEach(io.errors());
            if (result.cmdlets().isEmpty() && !result.warnings().isEmpty()) {
                throw new PjException("mod-load : " + path.getFileName() + " non chargé");
            }
            loaded.addAll(result.cmdlets());
        }
        return loaded;
    }

    /** {@code mod-list}: loaded modules and their cmdlets. */
    private List<Object> modList(List<Object> args, Session session) {
        if (!args.isEmpty()) {
            throw new PjException("mod-list : aucun argument attendu");
        }
        return List.copyOf(registry.modules());
    }

    public Session session() {
        return session;
    }

    public CmdletRegistry registry() {
        return registry;
    }

    /** Kind of a command, for highlighting and completion (FR-08, FR-21). */
    public enum CommandKind { BUILTIN, CMDLET, NATIVE, UNKNOWN }

    /** Names of the built-in commands. */
    public java.util.Set<String> builtinNames() {
        return java.util.Collections.unmodifiableSet(builtins.keySet());
    }

    /** Resolves a command name as execution would (FR-13), without launching anything. */
    public CommandKind commandKind(String name) {
        boolean forceNative = name.startsWith("^");
        String bare = forceNative ? name.substring(1) : name;
        if (!forceNative && builtins.containsKey(bare)) {
            return CommandKind.BUILTIN;
        }
        if (!forceNative && registry.find(bare).isPresent()) {
            return CommandKind.CMDLET;
        }
        return !bare.isEmpty() && resolver.resolve(bare, session).isPresent() ? CommandKind.NATIVE : CommandKind.UNKNOWN;
    }

    /**
     * Non-interactive mode (FR-04d): the standard input lines feed the first stage that reads
     * objects, and native commands at the head of the pipeline read standard input directly.
     */
    public void useStandardInput(java.util.Iterator<String> lines) {
        this.standardInput = Source.of(lines);
        this.inheritStandardInput = true;
    }

    /**
     * Executes the line. An error in a statement is displayed and counts as a failure (for
     * {@code &&} / {@code ||}); a syntax error prevents any execution.
     *
     * @throws InterruptedException if the line was cancelled by Ctrl+C
     */
    public void execute(String line) throws InterruptedException {
        blockingError = false;
        Ast.Script script = Parser.parse(line, session.java()::isStaticReference);
        boolean succeeded = true;
        for (Ast.Step step : script.steps()) {
            if (session.exitRequest().isPresent()) {
                return;
            }
            boolean run = switch (step.connector()) {
                case ALWAYS -> true;
                case IF_SUCCESS -> succeeded;
                case IF_FAILURE -> !succeeded;
            };
            if (run) {
                succeeded = statement(step.statement());
                session.recordSuccess(succeeded);
            }
        }
    }

    private boolean statement(Statement statement) throws InterruptedException {
        Optional<NativeRunner.FileTarget> errTarget = Optional.empty();
        try {
            errTarget = redirect(statement.pipeline(), Token.Stream.ERR);
            Optional<NativeRunner.FileTarget> outTarget = redirect(statement.pipeline(), Token.Stream.OUT);
            if (statement.assignTo().isPresent()) {
                if (outTarget.isPresent()) {
                    throw new PjException("une affectation ne peut pas rediriger sa sortie avec >");
                }
                List<Object> values = new ArrayList<>();
                boolean succeeded = pipeline(statement.pipeline(), values::add, true, Optional.empty(), errTarget);
                session.setVariable(statement.assignTo().get(), Evaluator.single(values));
                return succeeded;
            }
            try (var output = new Output(outTarget)) {
                return pipeline(statement.pipeline(), output::accept, false, outTarget, errTarget);
            }
        } catch (PjException e) {
            blockingError = true;
            reportError(describe(e.error()), errTarget);
            return false;
        }
    }

    /** {@code true} if the last executed line hit a blocking error (not just a failure). */
    public boolean hadBlockingError() {
        return blockingError;
    }

    /** Prepared stage: arguments evaluated, command resolved. */
    private sealed interface Prepared { }

    /** Stage that produces objects (value, built-in command, cmdlet). */
    private record ObjectStep(ObjectStage stage) implements Prepared { }

    /** Native stage. */
    private record NativeStep(NativeRunner.Command command) implements Prepared { }

    @FunctionalInterface
    private interface ObjectStage {
        /**
         * @param input  received objects, or {@code null} for the first stage
         * @param output receives the produced objects
         * @param errors receives the non-blocking errors
         * @return success
         */
        boolean run(Source input, Consumer<Object> output, Consumer<String> errors) throws Exception;
    }

    /** Sequence of stages run by the same thread: an object stage, or consecutive native ones. */
    private sealed interface Segment { }

    private record ObjectSegment(ObjectStage stage) implements Segment { }

    private record NativeSegment(List<NativeRunner.Command> commands) implements Segment { }

    /**
     * Runs a pipeline, sending each value produced by the last stage to {@code sink}. The stages
     * run in parallel (virtual threads), connected by bounded queues; the last one runs in the
     * calling thread, which receives the Ctrl+C interruption.
     *
     * @param capture {@code true} if the values are captured (assignment, subexpression): the output
     *                of a native command is then read as lines instead of being displayed
     * @return success: that of the last stage, and no blocking error in the others
     */
    private boolean pipeline(Ast.Pipeline pipeline, Consumer<Object> sink, boolean capture,
                             Optional<NativeRunner.FileTarget> outTarget, Optional<NativeRunner.FileTarget> errTarget)
            throws InterruptedException {
        List<Segment> segments = segments(pipeline.stages());
        Consumer<String> errors = synchronizedErrors(errorSink(errTarget));
        if (segments.size() == 1 && segments.getFirst() instanceof NativeSegment(var commands) && commands.size() == 1) {
            NativeRunner.Command command = commands.getFirst();
            var result = nativeRunner.run(command.executable(), command.args(), session, io, capture, outTarget,
                    errTarget, command.errorsToOutput(), inheritsInput());
            session.recordNative(result.run());
            result.capturedLines().forEach(sink);
            return result.run().succeeded();
        }
        Optional<NativeRunner.FileTarget> sharedErrors = shared(errTarget);
        if (segments.size() == 1) {
            return segment(segments.getFirst(), standardInput, null, sink, capture, outTarget, sharedErrors, errors);
        }

        int n = segments.size();
        List<Pipe> pipes = new ArrayList<>(n - 1);
        for (int i = 0; i < n - 1; i++) {
            pipes.add(new Pipe());
        }
        var blockingErrors = new java.util.concurrent.atomic.AtomicBoolean();
        List<Thread> threads = new ArrayList<>(n - 1);
        for (int i = 0; i < n - 1; i++) {
            Segment segment = segments.get(i);
            Source input = i == 0 ? standardInput : pipes.get(i - 1);
            Pipe output = pipes.get(i);
            threads.add(Thread.ofVirtual().name("powerj-etape-" + (i + 1)).unstarted(() -> {
                output.onAbort(Thread.currentThread()::interrupt);
                try {
                    segment(segment, input, output, null, false, Optional.empty(), sharedErrors, errors);
                    output.close();
                } catch (InterruptedException | java.util.concurrent.CancellationException e) {
                    closeQuietly(output);
                } catch (PjException e) {
                    blockingErrors.set(true);
                    errors.accept(describe(e.error()));
                    closeQuietly(output);
                } catch (Throwable t) {
                    blockingErrors.set(true);
                    java.util.logging.Logger.getLogger(Interpreter.class.getName())
                            .log(java.util.logging.Level.SEVERE, "Erreur interne dans une étape de pipeline", t);
                    errors.accept("erreur interne : " + t + " (détails dans le journal)");
                    closeQuietly(output);
                } finally {
                    if (input instanceof Pipe pipe) {
                        pipe.abort();
                    }
                }
            }));
        }
        threads.forEach(Thread::start);
        Pipe lastInput = pipes.getLast();
        boolean succeeded;
        try {
            succeeded = segment(segments.getLast(), lastInput, null, sink, capture, outTarget, sharedErrors, errors);
        } finally {
            lastInput.abort();
            joinAll(threads);
        }
        if (blockingErrors.get()) {
            blockingError = true;
        }
        return succeeded && !blockingErrors.get();
    }

    /** Runs a segment; {@code output} is the queue to the next stage, or {@code null} at the end of the pipeline. */
    private boolean segment(Segment segment, Source input, Pipe output, Consumer<Object> sink, boolean capture,
                            Optional<NativeRunner.FileTarget> outTarget, Optional<NativeRunner.FileTarget> errTarget,
                            Consumer<String> errors) throws InterruptedException {
        return switch (segment) {
            case ObjectSegment(var stage) -> {
                try {
                    yield stage.run(input, output != null ? output::putUnrolled : sink, errors);
                } catch (InterruptedException | RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new PjException(PjError.of(e.toString(), e));
                }
            }
            case NativeSegment(var commands) -> {
                List<NativeRun> runs = nativeRunner.runGroup(commands, session, io, input, inheritsInput(), output,
                        sink, capture, outTarget, errTarget, errors);
                synchronized (session) {
                    session.recordNative(runs.getLast());
                }
                yield runs.getLast().succeeded();
            }
        };
    }

    /** Resolves the stages and groups consecutive native commands. */
    private List<Segment> segments(List<Ast.Stage> stages) throws InterruptedException {
        List<Segment> segments = new ArrayList<>();
        List<NativeRunner.Command> natives = new ArrayList<>();
        for (int i = 0; i < stages.size(); i++) {
            Prepared prepared = prepare(stages.get(i), i, i < stages.size() - 1);
            if (prepared instanceof NativeStep(var command)) {
                natives.add(command);
                continue;
            }
            if (!natives.isEmpty()) {
                segments.add(new NativeSegment(List.copyOf(natives)));
                natives.clear();
            }
            segments.add(new ObjectSegment(((ObjectStep) prepared).stage()));
        }
        if (!natives.isEmpty()) {
            segments.add(new NativeSegment(List.copyOf(natives)));
        }
        return segments;
    }

    /**
     * Prepares a stage: evaluates its arguments and resolves the command (FR-13: built-in command, cmdlet,
     * program on the {@code PATH}).
     *
     * @param index    position in the pipeline: beyond the first, the stage must read objects
     * @param followed another stage follows this one
     */
    private Prepared prepare(Ast.Stage stage, int index, boolean followed) throws InterruptedException {
        if (stage.body() instanceof Ast.ExpressionBody(var expression)) {
            return new ObjectStep((_, output, _) -> {
                Object value = evaluator.evaluate(expression);
                if (followed && value instanceof io.powerj.api.Collected<?> list) {
                    // $l | map { … } : une valeur en tête de pipeline est toujours déroulée, comme toute liste ;
                    // seule une liste passée d'une commande à la suivante reste entière (FR-36d).
                    list.forEach(output);
                } else {
                    output.accept(value);
                }
                return !(value instanceof Boolean b) || b;
            });
        }
        var command = (Ast.Command) stage.body();
        List<Object> args = arguments(command.arguments());
        if (!command.forceNative()) {
            Builtin builtin = builtins.get(command.name());
            if (builtin != null) {
                if (index > 0) {
                    throw new PjException("« " + command.name() + " » ne lit pas les objets du pipeline");
                }
                return new ObjectStep((_, output, _) -> {
                    invokeBuiltin(command.name(), builtin, args).forEach(output);
                    return true;
                });
            }
            var found = registry.find(command.name());
            if (found.isPresent()) {
                var cmdlet = found.get();
                if (args.contains("--help")) {
                    return new ObjectStep((_, output, _) -> {
                        Help.cmdlet(cmdlet).forEach(output);
                        return true;
                    });
                }
                if (index > 0 && !cmdlet.readsInput()) {
                    throw new PjException("« " + command.name() + " » ne lit pas les objets du pipeline");
                }
                return new ObjectStep((input, output, errors) -> runCmdlet(cmdlet, args,
                        cmdlet.readsInput() ? input : null, output,
                        stage.errorsToOutput() ? message -> output.accept(message) : errors));
            }
        }
        Path executable = resolver.resolve(command.name(), session).orElseThrow(() -> unknownCommand(command));
        List<String> textArgs = args.stream().map(Values::text).toList();
        return new NativeStep(new NativeRunner.Command(executable, textArgs, stage.errorsToOutput()));
    }

    /** Command not found; {@code Math.NOPE}: the static field is missing rather than the command. */
    private PjException unknownCommand(Ast.Command command) {
        if (command.forceNative()) {
            return new PjException("commande native introuvable : " + command.name());
        }
        int dot = command.name().lastIndexOf('.');
        if (dot > 0 && command.arguments().isEmpty()) {
            var owner = session.java().find(command.name().substring(0, dot));
            if (owner.isPresent()) {
                return new PjException(owner.get().getSimpleName() + " n'a pas de champ statique "
                        + command.name().substring(dot + 1));
            }
        }
        return new PjException("commande inconnue : " + command.name());
    }

    private boolean runCmdlet(CmdletRegistry.Registered cmdlet, List<Object> args, Source input,
                              Consumer<Object> output, Consumer<String> errors) throws Exception {
        try {
            return CmdletRunner.run(cmdlet, args, session, errors, output, input, this::compile);
        } catch (PjException | InterruptedException | java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            throw new PjException(PjError.of(cmdlet.name() + " : " + e, e));
        }
    }

    /** Compiles the text of a block for a cmdlet ({@code CmdletContext.compile}). */
    private io.powerj.api.ScriptBlock compile(String source) {
        try {
            var function = ExpressionParser.function(source, session.java()::isStaticReference);
            return (io.powerj.api.ScriptBlock) evaluator.evaluate(function);
        } catch (SyntaxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException();
        }
    }

    /** Does the first process read the shell's standard input directly? */
    private boolean inheritsInput() {
        return io.interactive() || inheritStandardInput;
    }

    /** {@code 2>} shared by several processes: the file is truncated once, then each one appends to it. */
    private static Optional<NativeRunner.FileTarget> shared(Optional<NativeRunner.FileTarget> target) {
        if (target.isEmpty() || target.get().append()) {
            return target;
        }
        try {
            Files.writeString(target.get().file(), "", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PjException(PjError.of("écriture impossible dans " + target.get().file() + " : "
                    + e.getMessage(), e));
        }
        return Optional.of(new NativeRunner.FileTarget(target.get().file(), true));
    }

    private static Consumer<String> synchronizedErrors(Consumer<String> errors) {
        Object lock = new Object();
        return message -> {
            synchronized (lock) {
                errors.accept(message);
            }
        };
    }

    private static void closeQuietly(Pipe pipe) {
        try {
            pipe.close();
        } catch (java.util.concurrent.CancellationException _) {
            // pipeline déjà arrêté
        }
    }

    /** Waits for the stages to finish (stopped if needed), without letting itself be interrupted. */
    private static void joinAll(List<Thread> threads) {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(JOIN_SECONDS);
        for (Thread thread : threads) {
            while (thread.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break; // étape bloquée dans un appel non interruptible : abandonnée (FR-57)
                }
                try {
                    thread.join(java.time.Duration.ofNanos(remaining));
                } catch (InterruptedException _) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private List<Object> invokeBuiltin(String name, Builtin builtin, List<Object> args) throws InterruptedException {
        try {
            return builtin.run(args, session);
        } catch (PjException | InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new PjException(PjError.of(name + " : " + e.getMessage(), e));
        }
    }

    /** {@code import java.security.*}, {@code import javax.crypto.Cipher}; without arguments: active imports (FR-47). */
    private List<Object> importClasses(List<Object> args) {
        if (args.isEmpty()) {
            return List.copyOf(session.java().imports());
        }
        for (Object arg : args) {
            session.java().addImport(Values.text(arg));
        }
        return List.of();
    }

    private List<Object> which(List<Object> names, Session session) {
        if (names.isEmpty()) {
            throw new PjException("which : nom de commande attendu");
        }
        List<Object> lines = new ArrayList<>();
        for (Object value : names) {
            String name = Values.text(value);
            if (name.startsWith("^") && name.length() > 1) {
                String program = name.substring(1);
                Path path = resolver.resolve(program, session)
                        .orElseThrow(() -> new PjException("which : programme introuvable : " + program));
                lines.add(name + " → natif " + path);
            } else if (builtins.containsKey(name)) {
                lines.add(name + " → commande interne");
            } else if (registry.find(name).isPresent()) {
                lines.add(name + " → cmdlet (" + registry.find(name).get().module() + ")");
            } else {
                Path path = resolver.resolve(name, session)
                        .orElseThrow(() -> new PjException("which : introuvable : " + name));
                lines.add(name + " → natif " + path);
            }
        }
        return lines;
    }

    private List<Object> arguments(List<Argument> arguments) throws InterruptedException {
        List<Object> values = new ArrayList<>(arguments.size());
        for (Argument argument : arguments) {
            values.add(argumentValue(argument));
        }
        return values;
    }

    private Object argumentValue(Argument argument) throws InterruptedException {
        return switch (argument) {
            case Ast.WordArgument(var text) -> text;
            case Ast.ExpressionArgument(var expression) -> evaluator.evaluate(expression);
        };
    }

    /** Values of a parenthesized pipeline: {@code (ls).name}. */
    private List<Object> capture(Ast.Pipeline pipeline) throws InterruptedException {
        if (redirect(pipeline, Token.Stream.OUT).isPresent()) {
            throw new PjException("« > » impossible dans une sous-expression ( )");
        }
        List<Object> values = new ArrayList<>();
        Optional<NativeRunner.FileTarget> errTarget = redirect(pipeline, Token.Stream.ERR);
        pipeline(pipeline, values::add, true, Optional.empty(), errTarget);
        return values;
    }

    private Optional<NativeRunner.FileTarget> redirect(Ast.Pipeline pipeline, Token.Stream stream)
            throws InterruptedException {
        Ast.Redirect last = null;
        for (Ast.Redirect redirect : pipeline.redirects()) {
            if (redirect.stream() == stream) {
                last = redirect;
            }
        }
        if (last == null) {
            return Optional.empty();
        }
        Path file = session.currentDirectory().resolve(Values.text(argumentValue(last.target())));
        return Optional.of(new NativeRunner.FileTarget(file, last.append()));
    }

    private Consumer<String> errorSink(Optional<NativeRunner.FileTarget> errTarget) {
        return errTarget.<Consumer<String>>map(target -> message -> append(target.file(), message))
                .orElse(io.errors());
    }

    /**
     * Message of a blocking error; the original Java exception is kept in {@code $errors} and its
     * stack trace appended to the message if {@code $debug} is {@code true} (FR-43, FR-53).
     */
    private String describe(PjError error) {
        if (error.cause().isEmpty()) {
            return error.message();
        }
        Throwable cause = error.cause().get();
        session.recordError(cause);
        if (!session.debug()) {
            return error.message();
        }
        var trace = new java.io.StringWriter();
        cause.printStackTrace(new PrintWriter(trace));
        return error.message() + System.lineSeparator() + trace.toString().stripTrailing();
    }

    private void reportError(String message, Optional<NativeRunner.FileTarget> target) {
        if (target.isPresent()) {
            write(target.get(), message);
        } else {
            io.errors().accept(message);
        }
    }

    private static void write(NativeRunner.FileTarget target, String message) {
        if (target.append()) {
            append(target.file(), message);
        } else {
            try {
                Files.writeString(target.file(), message + System.lineSeparator(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new PjException(PjError.of("écriture impossible dans " + target.file() + " : " + e.getMessage(), e));
            }
        }
    }

    private static void append(Path file, String line) {
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new PjException(PjError.of("écriture impossible dans " + file + " : " + e.getMessage(), e));
        }
    }

    /**
     * Destination of a statement's values: the terminal, or the file of a {@code >} redirection.
     * The file is opened only at the first value: a redirected native command writes it itself.
     */
    private final class Output implements AutoCloseable {

        private final Optional<NativeRunner.FileTarget> target;
        private OutputFormatter formatter;
        private PrintWriter file;

        Output(Optional<NativeRunner.FileTarget> target) {
            this.target = target;
        }

        void accept(Object value) {
            formatter().accept(value);
        }

        private OutputFormatter formatter() {
            if (formatter == null) {
                if (target.isPresent()) {
                    try {
                        var options = target.get().append()
                                ? new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                                : new StandardOpenOption[0];
                        file = new PrintWriter(Files.newBufferedWriter(target.get().file(), StandardCharsets.UTF_8, options));
                    } catch (IOException e) {
                        throw new PjException(PjError.of("écriture impossible dans " + target.get().file()
                                + " : " + e.getMessage(), e));
                    }
                    formatter = new OutputFormatter(file, FILE_WIDTH);
                } else {
                    formatter = new OutputFormatter(io.out(), io.width().getAsInt());
                }
            }
            return formatter;
        }

        @Override
        public void close() {
            if (formatter != null) {
                formatter.close();
            }
            if (file != null) {
                file.close();
                if (file.checkError()) {
                    throw new PjException("écriture impossible dans " + target.orElseThrow().file());
                }
            }
        }
    }
}
