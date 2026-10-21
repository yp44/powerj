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
import io.powerj.core.lang.Syntax;
import io.powerj.core.lang.SyntaxException;
import io.powerj.core.lang.Token;

/**
 * Executes a line: statements chained by {@code ;}, {@code &&}, {@code ||} (FR-04c) or launched in the background
 * by {@code &} (FR-75), pipelines
 * {@code |}, built-in commands, cmdlets, native commands, expressions, assignments and redirections. Resolution order
 * of a command (FR-13): built-in command, cmdlet, program on the {@code PATH}; {@code ^name} forces the
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

    /** Figures of the line being executed (perf mode, FR-76), or {@code null}. */
    private volatile PerfRecorder perf;
    private volatile PerfRecorder.StatementTimer statementTimer;

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
        builtins.put("jobs", (_, s) -> new ArrayList<>(s.jobs().list()));
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

    /** {@code mod-load <path.jar>}: loads a module at runtime. */
    private List<Object> modLoad(List<Object> args, Session session) {
        if (args.isEmpty()) {
            throw new PjException(Messages.get("module.load.pathExpected"));
        }
        List<Object> loaded = new ArrayList<>();
        for (Object arg : args) {
            String text = Values.text(arg);
            Path path = session.currentDirectory().resolve(text); // ~ already expanded (FR-62)
            ModuleLoader.Result result = modules.load(path);
            result.warnings().forEach(io.errors());
            if (result.cmdlets().isEmpty() && !result.warnings().isEmpty()) {
                throw new PjException(Messages.get("module.load.notLoaded", path.getFileName()));
            }
            loaded.addAll(result.cmdlets());
        }
        return loaded;
    }

    /** {@code mod-list}: loaded modules and their cmdlets. */
    private List<Object> modList(List<Object> args, Session session) {
        if (!args.isEmpty()) {
            throw new PjException(Messages.get("module.list.noArguments"));
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

    /** Java static names, and the commands whose cmdlet declares the short form {@code where size > 1mb}. */
    private Syntax syntax() {
        return new Syntax(session.java()::isStaticReference, this::acceptsComparison);
    }

    /** {@code @CmdletInfo(comparison = true)} on the cmdlet the name runs (FR-13: a built-in command comes first). */
    private boolean acceptsComparison(String name) {
        return !builtins.containsKey(name) && registry.find(name).map(CmdletRegistry.Registered::comparison).orElse(false);
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
        Ast.Script script = Parser.parse(line, syntax());
        Profiling.Span span = Profiling.command(commandNames(script), script.steps().size());
        try {
            run(script);
        } finally {
            span.end(-1, -1);
        }
    }

    /** Names of the commands of the line, without arguments (the Flight Recorder events never carry those). */
    private static String commandNames(Ast.Script script) {
        return script.steps().stream().flatMap(step -> step.statement().pipeline().stages().stream())
                .map(stage -> stageText(stage).split(" ", 2)[0]).collect(java.util.stream.Collectors.joining(" | "));
    }

    private void run(Ast.Script script) throws InterruptedException {
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
                PerfRecorder recorder = perf != null ? perf : Profiling.enabled() ? PerfRecorder.profiling() : null;
                PerfRecorder.StatementTimer timer = recorder == null ? null : recorder.statement();
                statementTimer = timer;
                succeeded = false;
                try {
                    succeeded = statement(step.statement());
                } finally {
                    statementTimer = null;
                    if (timer != null) {
                        timer.finish(statementText(step.statement()), succeeded);
                    }
                }
                session.recordSuccess(succeeded);
            }
        }
    }

    private boolean statement(Statement statement) throws InterruptedException {
        Optional<NativeRunner.FileTarget> errTarget = Optional.empty();
        try {
            errTarget = redirect(statement.pipeline(), Token.Stream.ERR);
            Optional<NativeRunner.FileTarget> outTarget = redirect(statement.pipeline(), Token.Stream.OUT);
            if (statement.background()) {
                return background(statement.pipeline(), outTarget, errTarget);
            }
            if (statement.assignTo().isPresent()) {
                if (outTarget.isPresent()) {
                    throw new PjException(Messages.get("redirect.assignment"));
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

    /**
     * {@code cmd &} (FR-75): starts the native commands of the pipeline without waiting for them, and records the job.
     * {@code $last} describes the last process, with no exit code, as for a detached application (FR-39).
     */
    private boolean background(Ast.Pipeline pipeline, Optional<NativeRunner.FileTarget> outTarget,
                               Optional<NativeRunner.FileTarget> errTarget) throws InterruptedException {
        List<Segment> segments = segments(pipeline.stages());
        if (segments.size() != 1 || !(segments.getFirst() instanceof NativeSegment(var commands))) {
            throw new PjException(Messages.get("background.nativeOnly"));
        }
        long start = System.nanoTime();
        List<Process> processes = nativeRunner.startBackground(commands, session, io, outTarget, shared(errTarget));
        String text = String.join(" | ", commands.stream().map(Interpreter::commandText).toList());
        Job job = session.jobs().add(text, processes);
        NativeRunner.Command last = commands.getLast();
        session.recordNative(new NativeRun(last.executable(), last.args(), job.pid(), null,
                java.time.Duration.ofNanos(System.nanoTime() - start)));
        if (io.interactive()) {
            io.out().println(Messages.get("background.started", job.id(), job.pid()));
            io.out().flush();
        }
        return true;
    }

    /** {@code xeyes -geometry 100x100}: program name and arguments, on one line, for {@code jobs}. */
    private static String commandText(NativeRunner.Command command) {
        List<String> words = new ArrayList<>(command.args().size() + 1);
        words.add(command.executable().getFileName().toString());
        command.args().forEach(arg -> words.add(arg.isEmpty() || arg.chars().anyMatch(Character::isWhitespace)
                ? '"' + arg.replace("\n", "\\n").replace("\t", "\\t") + '"' : arg));
        return String.join(" ", words);
    }

    /** Stops a preview that has received enough values. */
    private static final class PreviewFull extends java.util.concurrent.CancellationException { }

    /**
     * Preview mode (FR-66): evaluates the line as {@link #execute} would, but only with what changes nothing — cmdlets
     * declared {@code preview = true}, expressions, Java calls of the preview set — and stops after {@code limit}
     * values. Nothing is displayed, no variable is set, {@code $?} and {@code $errors} are left as they are.
     * Cancelled by interrupting the calling thread: the values received so far are then returned.
     *
     * @param width  width of the formatted lines ({@link PreviewResult.Values#lines()})
     * @param colors {@code true} if the lines may contain ANSI colors
     */
    public PreviewResult preview(String line, int limit, int width, boolean colors) {
        if (line.isBlank()) {
            return new PreviewResult.Empty();
        }
        Ast.Script script;
        try {
            script = Parser.parse(line, syntax());
        } catch (SyntaxException e) {
            return new PreviewResult.Incomplete(e.getMessage());
        }
        if (script.steps().isEmpty()) {
            return new PreviewResult.Empty();
        }
        if (script.steps().size() > 1) {
            return new PreviewResult.Off(Messages.get("preview.off.several"));
        }
        Statement statement = script.steps().getFirst().statement();
        if (statement.background()) {
            return new PreviewResult.Off(Messages.get("preview.off.background"));
        }
        if (statement.assignTo().isPresent()) {
            return new PreviewResult.Off(Messages.get("preview.off.assignment"));
        }
        if (!statement.pipeline().redirects().isEmpty()) {
            return new PreviewResult.Off(Messages.get("preview.off.redirection"));
        }
        var guard = new PreviewGuard();
        List<Object> values = java.util.Collections.synchronizedList(new ArrayList<>());
        boolean more = false;
        try {
            guard.call(() -> pipeline(statement.pipeline(), value -> previewValue(value, values, limit), true,
                    Optional.empty(), Optional.empty()));
        } catch (PreviewFull _) {
            more = true;
        } catch (InterruptedException | java.util.concurrent.CancellationException e) {
            if (guard.reason() == null) {
                more = true; // cancelled by the caller (time limit): what has arrived is shown
            }
        } catch (PjException e) {
            if (guard.reason() == null) {
                return new PreviewResult.Failed(e.error().message());
            }
        } catch (RuntimeException | StackOverflowError e) {
            if (guard.reason() == null) {
                return new PreviewResult.Failed(Messages.get("error.internal", e));
            }
        }
        if (guard.reason() != null) {
            return new PreviewResult.Off(guard.reason());
        }
        List<Object> shown;
        synchronized (values) {
            shown = new ArrayList<>(values.subList(0, Math.min(limit, values.size())));
            more |= values.size() > limit;
        }
        var text = new java.io.StringWriter();
        try {
            guard.call(() -> {
                var formatter = new OutputFormatter(new PrintWriter(text), width, colors);
                shown.forEach(formatter::accept);
                formatter.close();
                return null;
            });
        } catch (java.util.concurrent.CancellationException | PjException e) {
            if (guard.reason() != null) {
                return new PreviewResult.Off(guard.reason());
            }
            more = true; // a lazy value could not be displayed in time: what was formatted is shown
        }
        String formatted = text.toString().stripTrailing();
        List<String> lines = formatted.isEmpty() ? List.of() : List.of(formatted.split("\\R"));
        return new PreviewResult.Values(shown, lines, more, guard.errors());
    }

    /** Adds a value of the preview, unrolled like the display does; stops once there is more than {@code limit}. */
    private static void previewValue(Object value, List<Object> values, int limit) {
        switch (value) {
            case null -> { }
            case Path path -> addPreviewValue(path, values, limit);
            case java.util.Map<?, ?> map -> addPreviewValue(map, values, limit);
            case Iterable<?> items -> items.forEach(item -> addPreviewValue(item, values, limit));
            case java.util.stream.BaseStream<?, ?> stream -> {
                try (stream) {
                    stream.iterator().forEachRemaining(item -> addPreviewValue(item, values, limit));
                }
            }
            case java.util.Iterator<?> iterator -> iterator.forEachRemaining(item -> addPreviewValue(item, values, limit));
            case Object array when array.getClass().isArray() -> {
                for (int i = 0; i < java.lang.reflect.Array.getLength(array); i++) {
                    addPreviewValue(java.lang.reflect.Array.get(array, i), values, limit);
                }
            }
            default -> addPreviewValue(value, values, limit);
        }
    }

    private static void addPreviewValue(Object value, List<Object> values, int limit) {
        values.add(value);
        if (values.size() > limit) {
            throw new PreviewFull();
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
        PerfRecorder.StatementTimer timer = statementTimer;
        statementTimer = null; // only the statement's own pipeline is timed, not those nested in an argument
        List<Segment> segments = segments(pipeline.stages());
        List<PerfRecorder.StageTimer> timers = timer == null || PreviewGuard.active() ? null
                : stageTimers(timer, pipeline, segments.size());
        Consumer<String> errors = synchronizedErrors(errorSink(errTarget));
        if (segments.size() == 1 && segments.getFirst() instanceof NativeSegment(var commands) && commands.size() == 1) {
            NativeRunner.Command command = commands.getFirst();
            PerfRecorder.StageTimer stageTimer = timers == null ? null : timers.getFirst();
            if (stageTimer != null) {
                stageTimer.started();
            }
            try {
                var result = nativeRunner.run(command.executable(), command.args(), session, io, capture, outTarget,
                        errTarget, command.errorsToOutput(), inheritsInput());
                session.recordNative(result.run());
                result.capturedLines().forEach(stageTimer == null ? sink : stageTimer.counting(sink));
                return result.run().succeeded();
            } finally {
                if (stageTimer != null) {
                    stageTimer.ended();
                }
            }
        }
        Optional<NativeRunner.FileTarget> sharedErrors = shared(errTarget);
        if (segments.size() == 1) {
            return segment(segments.getFirst(), standardInput, null, sink, capture, outTarget, sharedErrors, errors,
                    timers == null ? null : timers.getFirst());
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
            PerfRecorder.StageTimer stageTimer = timers == null ? null : timers.get(i);
            threads.add(Thread.ofVirtual().name("powerj-stage-" + (i + 1)).unstarted(PreviewGuard.propagate(() -> {
                output.onAbort(Thread.currentThread()::interrupt);
                try {
                    segment(segment, input, output, null, false, Optional.empty(), sharedErrors, errors, stageTimer);
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
                            .log(java.util.logging.Level.SEVERE, "Internal error in a pipeline stage", t);
                    errors.accept(Messages.get("error.internal", t));
                    closeQuietly(output);
                } finally {
                    if (input instanceof Pipe pipe) {
                        pipe.abort();
                    }
                }
            })));
        }
        threads.forEach(Thread::start);
        Pipe lastInput = pipes.getLast();
        boolean succeeded;
        try {
            succeeded = segment(segments.getLast(), lastInput, null, sink, capture, outTarget, sharedErrors, errors,
                    timers == null ? null : timers.getLast());
        } finally {
            lastInput.abort();
            joinAll(threads);
        }
        if (blockingErrors.get() && !PreviewGuard.active()) {
            blockingError = true;
        }
        return succeeded && !blockingErrors.get();
    }

    /**
     * Runs a segment; {@code output} is the queue to the next stage, or {@code null} at the end of the pipeline.
     *
     * @param timer figures of the stage in perf mode, or {@code null}
     */
    private boolean segment(Segment segment, Source input, Pipe output, Consumer<Object> sink, boolean capture,
                            Optional<NativeRunner.FileTarget> outTarget, Optional<NativeRunner.FileTarget> errTarget,
                            Consumer<String> errors, PerfRecorder.StageTimer timer) throws InterruptedException {
        if (timer != null) {
            timer.started();
        }
        try {
            return switch (segment) {
                case ObjectSegment(var stage) -> {
                    Consumer<Object> destination = output != null ? output::putUnrolled : sink;
                    try {
                        yield timer == null ? stage.run(input, destination, errors)
                                : stage.run(timer.counting(input), timer.counting(destination), errors);
                    } catch (InterruptedException | RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new PjException(PjError.of(e.toString(), e));
                    }
                }
                case NativeSegment(var commands) -> {
                    // between two native stages the values travel as bytes: only the last sink can be counted
                    List<NativeRun> runs = nativeRunner.runGroup(commands, session, io, input, inheritsInput(), output,
                            timer == null || sink == null ? sink : timer.counting(sink), capture, outTarget, errTarget,
                            errors);
                    if (output != null && output.aborted()) {
                        // FR-68b: stopped by the next stage (first, range…): not a failure, $exit is left as it is.
                        yield true;
                    }
                    synchronized (session) {
                        session.recordNative(runs.getLast());
                    }
                    yield runs.getLast().succeeded();
                }
            };
        } finally {
            if (timer != null) {
                timer.ended();
            }
        }
    }

    /** Stage figures for the segments of a pipeline; {@code null} if the plan and the segments disagree. */
    private List<PerfRecorder.StageTimer> stageTimers(PerfRecorder.StatementTimer timer, Ast.Pipeline pipeline,
                                                      int segments) {
        List<PerfPlan.Stage> stages = planStages(pipeline);
        if (stages.size() != segments) {
            return null;
        }
        return stages.stream().map(timer::stage).toList();
    }

    /** The stages of the pipeline as {@link #segments} groups them, resolved by name only (nothing is evaluated). */
    private List<PerfPlan.Stage> planStages(Ast.Pipeline pipeline) {
        List<Ast.Stage> stages = pipeline.stages();
        List<PerfPlan.Stage> plan = new ArrayList<>();
        List<String> natives = new ArrayList<>();
        for (int i = 0; i < stages.size(); i++) {
            PerfPlan.Kind kind = stageKind(stages.get(i));
            String text = stageText(stages.get(i));
            if (kind == PerfPlan.Kind.NATIVE) {
                natives.add(text);
                if (i < stages.size() - 1 && stageKind(stages.get(i + 1)) == PerfPlan.Kind.NATIVE) {
                    continue;
                }
                plan.add(new PerfPlan.Stage(String.join(" | ", natives), kind, placement(stages.size(),
                        i, natives.size() == stages.size())));
                natives.clear();
            } else {
                plan.add(new PerfPlan.Stage(text, kind, placement(stages.size(), i, false)));
            }
        }
        return plan;
    }

    /** A lone native command starts directly; otherwise the last stage runs in the caller, the others in a thread. */
    private static PerfPlan.Placement placement(int stageCount, int lastStage, boolean lone) {
        if (lone && stageCount == 1) {
            return PerfPlan.Placement.PROCESS;
        }
        return lastStage == stageCount - 1 ? PerfPlan.Placement.CALLER : PerfPlan.Placement.THREAD;
    }

    private PerfPlan.Kind stageKind(Ast.Stage stage) {
        if (!(stage.body() instanceof Ast.Command command)) {
            return PerfPlan.Kind.EXPRESSION;
        }
        String name = command.forceNative() ? "^" + command.name() : command.name();
        return switch (commandKind(name)) {
            case BUILTIN -> PerfPlan.Kind.BUILTIN;
            case CMDLET -> PerfPlan.Kind.CMDLET;
            case NATIVE -> PerfPlan.Kind.NATIVE;
            case UNKNOWN -> PerfPlan.Kind.UNKNOWN;
        };
    }

    /** The stage as typed, as far as the syntax tree allows: computed arguments are shown as {@code …}. */
    private static String stageText(Ast.Stage stage) {
        if (!(stage.body() instanceof Ast.Command command)) {
            return "{expression}";
        }
        List<String> words = new ArrayList<>();
        words.add((command.forceNative() ? "^" : "") + command.name());
        for (Argument argument : command.arguments()) {
            words.add(argument instanceof Ast.WordArgument(var text) ? text : "…");
        }
        return String.join(" ", words);
    }

    private static String statementText(Statement statement) {
        return String.join(" | ", statement.pipeline().stages().stream().map(Interpreter::stageText).toList());
    }

    /**
     * Perf mode (FR-76): how the line will run, worked out without running or evaluating anything — commands are
     * resolved by name as {@link #commandKind} does.
     */
    public PerfPlan plan(String line) {
        if (line.isBlank()) {
            return new PerfPlan.Empty();
        }
        Ast.Script script;
        try {
            script = Parser.parse(line, syntax());
        } catch (SyntaxException e) {
            return new PerfPlan.Incomplete(e.getMessage());
        }
        if (script.steps().isEmpty()) {
            return new PerfPlan.Empty();
        }
        List<PerfPlan.Statement> statements = new ArrayList<>();
        for (Ast.Step step : script.steps()) {
            Statement statement = step.statement();
            statements.add(new PerfPlan.Statement(step.connector(), statement.assignTo().orElse(null),
                    statement.background(), statement.pipeline().redirects().size(), planStages(statement.pipeline())));
        }
        return new PerfPlan.Statements(statements);
    }

    /** Installs the recorder of the line about to be executed, or {@code null} to stop recording. */
    public void record(PerfRecorder recorder) {
        this.perf = recorder;
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
                    // $l | map { … }: a value at the head of a pipeline is always unrolled, like any list;
                    // only a list passed from one command to the next stays whole (FR-36d).
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
                PreviewGuard.deny(Messages.get("preview.off.builtin", command.name()));
                if (index > 0) {
                    throw new PjException(Messages.get("pipeline.noInput", command.name()));
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
                    throw new PjException(Messages.get("pipeline.noInput", command.name()));
                }
                if (!cmdlet.preview()) {
                    PreviewGuard.deny(Messages.get("preview.off.cmdlet", command.name()));
                }
                return new ObjectStep((input, output, errors) -> runCmdlet(cmdlet, args,
                        cmdlet.readsInput() ? input : null, output,
                        stage.errorsToOutput() ? message -> output.accept(message) : errors));
            }
        }
        Path executable = resolver.resolve(command.name(), session).orElseThrow(() -> unknownCommand(command));
        PreviewGuard.deny(Messages.get("preview.off.native", command.name()));
        List<String> textArgs = args.stream().map(Values::text).toList();
        return new NativeStep(new NativeRunner.Command(executable, textArgs, stage.errorsToOutput()));
    }

    /** Command not found; {@code Math.NOPE}: the static field is missing rather than the command. */
    private PjException unknownCommand(Ast.Command command) {
        if (command.forceNative()) {
            return new PjException(Messages.get("command.nativeNotFound", command.name()));
        }
        int dot = command.name().lastIndexOf('.');
        if (dot > 0 && command.arguments().isEmpty()) {
            var owner = session.java().find(command.name().substring(0, dot));
            if (owner.isPresent()) {
                return new PjException(Messages.get("java.noStaticField", owner.get().getSimpleName(),
                        command.name().substring(dot + 1)));
            }
        }
        return new PjException(Messages.get("command.unknown", command.name()));
    }

    private boolean runCmdlet(CmdletRegistry.Registered cmdlet, List<Object> args, Source input,
                              Consumer<Object> output, Consumer<String> errors) throws Exception {
        try {
            return CmdletRunner.run(cmdlet, args, session, errors, output, input, this::compile);
        } catch (PjException | InterruptedException | java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            throw new PjException(PjError.of(Messages.get("command.error", cmdlet.name(), e), e));
        }
    }

    /** Compiles the text of a block for a cmdlet ({@code CmdletContext.compile}). */
    private io.powerj.api.ScriptBlock compile(String source) {
        try {
            var function = ExpressionParser.function(source, syntax());
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
            throw new PjException(PjError.of(Messages.get("file.writeFailed", target.get().file(), e.getMessage()), e));
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
            // pipeline already stopped
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
                    break; // stage blocked in a non-interruptible call: abandoned (FR-57)
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
            throw new PjException(PjError.of(Messages.get("command.error", name, e.getMessage()), e));
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
            throw new PjException(Messages.get("which.nameExpected"));
        }
        List<Object> lines = new ArrayList<>();
        for (Object value : names) {
            String name = Values.text(value);
            if (name.startsWith("^") && name.length() > 1) {
                String program = name.substring(1);
                Path path = resolver.resolve(program, session)
                        .orElseThrow(() -> new PjException(Messages.get("which.programNotFound", program)));
                lines.add(Messages.get("which.native", name, path));
            } else if (builtins.containsKey(name)) {
                lines.add(Messages.get("which.builtin", name));
            } else if (registry.find(name).isPresent()) {
                lines.add(Messages.get("which.cmdlet", name, registry.find(name).get().module()));
            } else {
                Path path = resolver.resolve(name, session)
                        .orElseThrow(() -> new PjException(Messages.get("which.notFound", name)));
                lines.add(Messages.get("which.native", name, path));
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
            case Ast.WordArgument(var text) -> session.expandTilde(text); // ~, ~/x (FR-62); not in quotes
            case Ast.ExpressionArgument(var expression) -> evaluator.evaluate(expression);
        };
    }

    /** Values of a parenthesized pipeline: {@code (ls).name}. */
    private List<Object> capture(Ast.Pipeline pipeline) throws InterruptedException {
        if (redirect(pipeline, Token.Stream.OUT).isPresent()) {
            throw new PjException(Messages.get("redirect.subexpression"));
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
        PreviewGuard guard = PreviewGuard.current();
        if (guard != null) {
            return guard::error; // nothing is written while the user types (FR-66)
        }
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
        if (PreviewGuard.active()) {
            return error.message(); // $errors only keeps the errors of executed commands
        }
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
                throw new PjException(PjError.of(Messages.get("file.writeFailed", target.file(), e.getMessage()), e));
            }
        }
    }

    private static void append(Path file, String line) {
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new PjException(PjError.of(Messages.get("file.writeFailed", file, e.getMessage()), e));
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
                        throw new PjException(PjError.of(Messages.get("file.writeFailed", target.get().file(),
                                e.getMessage()), e));
                    }
                    formatter = new OutputFormatter(file, FILE_WIDTH);
                } else {
                    formatter = new OutputFormatter(io.out(), io.width().getAsInt(), io.colors());
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
                    throw new PjException(Messages.get("file.writeFailed.noReason", target.orElseThrow().file()));
                }
            }
        }
    }
}
