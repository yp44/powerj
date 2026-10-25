package io.powerj.core.exec;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.powerj.core.lang.Ast;
import io.powerj.core.lang.Ast.Statement;
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

    private final Session session;
    private final ShellIo io;
    private final Map<String, Builtin> builtins = new LinkedHashMap<>();
    private final CmdletRegistry registry;
    private final ModuleLoader modules;
    private final CommandResolution resolution;
    private final Evaluator evaluator;
    private final ErrorReporter reporter;
    private final PipelineExecutor executor;
    /**
     * Variable of the session environment ({@code env --set} in {@code profile.pj}): when it is {@code 1}, the perf mode
     * ends by itself after the next measured line (FR-76).
     */
    static final String PERF_AUTOEXIT_VARIABLE = "POWERJ_PERF_AUTOEXIT";

    /** Perf mode (FR-76): each line executed is measured, and a summary is printed after it. */
    private volatile boolean perfMode;
    /** Measure of the line being executed, or {@code null}. */
    private volatile PerfRecorder recorder;
    /** The profiler records each measured line (FR-76, part B); only a test or a runtime without it turns it off. */
    private volatile boolean perfProfiler = true;
    /** Folder of the profiler's recordings, created at the first one. */
    private Path recordings;

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
        this.evaluator = new Evaluator(session, this::capture, this::syntax);
        this.reporter = new ErrorReporter(session, io);
        // The order is the one of the overview printed by `help`; the shell's own commands come before `exit`.
        builtins.put("cd", Builtins::cd);
        builtins.put("pwd", Builtins::pwd);
        builtins.put("which", this::which);
        builtins.put("help", (args, _) -> Help.run(args, registry, List.copyOf(builtins.keySet()), session.java()));
        builtins.put("import", (args, _) -> importClasses(args));
        builtins.put("mod-load", this::modLoad);
        builtins.put("mod-list", this::modList);
        builtins.put("jobs", (_, s) -> new ArrayList<>(s.jobs().list()));
        builtins.putAll(extraBuiltins);
        builtins.putIfAbsent("exit", Builtins::exit);
        this.resolution = new CommandResolution(builtins, registry, resolver);
        this.executor = new PipelineExecutor(session, io, resolution, nativeRunner, evaluator, reporter,
                () -> recorder);
        this.modules = new ModuleLoader(registry, Collections.unmodifiableSet(builtins.keySet()));
    }

    /** Consent asked before loading a module that uses native code (FR-77); none by default: it is not loaded. */
    public void nativeAccess(NativeAccessPolicy policy) {
        modules.nativeAccess(policy);
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
    public Set<String> builtinNames() {
        return resolution.builtinNames();
    }

    /** What a command name stands for (FR-13): the rule that execution, {@code which}, highlighting and completion share. */
    CommandResolution resolution() {
        return resolution;
    }

    /** Java static names, and the commands whose cmdlet declares the short form {@code where size > 1mb}. */
    private Syntax syntax() {
        return new Syntax(session.java()::isStaticReference, this::acceptsComparison);
    }

    /** {@code @CmdletInfo(comparison = true)} on the cmdlet the name runs (FR-13: a built-in command comes first). */
    private boolean acceptsComparison(String name) {
        return resolution.cmdlet(name).map(CmdletRegistry.Registered::comparison).orElse(false);
    }

    /** Resolves a command name as execution would (FR-13), without launching anything. */
    public CommandKind commandKind(String name) {
        return switch (resolution.resolve(name, session)) {
            case Resolved.BuiltinCall _ -> CommandKind.BUILTIN;
            case Resolved.CmdletCall _ -> CommandKind.CMDLET;
            case Resolved.NativeCall _ -> CommandKind.NATIVE;
            case Resolved.Unknown _ -> CommandKind.UNKNOWN;
        };
    }

    /**
     * Non-interactive mode (FR-04d): the standard input lines feed the first stage that reads
     * objects, and native commands at the head of the pipeline read standard input directly.
     */
    public void useStandardInput(Iterator<String> lines) {
        executor.useStandardInput(lines);
    }

    /**
     * Executes the line. An error in a statement is displayed and counts as a failure (for
     * {@code &&} / {@code ||}); a syntax error prevents any execution.
     *
     * @throws InterruptedException if the line was cancelled by Ctrl+C
     */
    public void execute(String line) throws InterruptedException {
        reporter.reset();
        long begin = System.nanoTime();
        Ast.Script script = Parser.parse(line, syntax());
        long parsed = System.nanoTime() - begin;
        PerfRecorder perf = null;
        JfrCapture capture = null;
        if (perfMode && measurable(script)) {
            long started = System.nanoTime();
            capture = startProfiler();
            // Starting the profiler is not part of the line.
            perf = new PerfRecorder(line, begin + (System.nanoTime() - started), parsed);
        }
        recorder = perf;
        boolean cancelled = false;
        try {
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
        } catch (InterruptedException | CancellationException e) {
            cancelled = true;
            throw e;
        } finally {
            recorder = null;
            if (perf != null) {
                printPerf(perf, capture, cancelled);
            }
        }
    }

    /** Perf mode (FR-76): {@code true} while the lines executed are measured. */
    public boolean perfMode() {
        return perfMode;
    }

    public void perfMode(boolean on) {
        if (on) {
            PerfRecorder.warmUp();
            if (perfProfiler && PerfJfr.available()) {
                Path file = recordingFile();
                if (file != null) {
                    JfrCapture.warmUp(file);
                }
            }
        }
        perfMode = on;
    }

    /** Without the profiler, a line is measured with the counters only. */
    void perfProfiler(boolean on) {
        perfProfiler = on;
    }

    private JfrCapture startProfiler() {
        if (!perfProfiler || !PerfJfr.available()) {
            return null;
        }
        Path file = recordingFile();
        return file == null ? null : JfrCapture.start(file, samplePeriod(), allocThrottle());
    }

    /** Value of each variable the warning was last given for: one warning per value, not one per line. */
    private String warnedPeriod;
    private String warnedThrottle;

    /**
     * Limit of the profiler's allocation samples, from {@code POWERJ_PERF_JFR_ALLOCTHROTTLE} in the session environment
     * ({@code 100/s}, {@code off}), or {@code null} for the profiler's default. A value that is not a rate is
     * reported, and the default is used.
     */
    private String allocThrottle() {
        String value = session.environment().get(PerfJfr.ALLOC_THROTTLE_VARIABLE);
        if (value == null || value.isBlank()) {
            return null;
        }
        boolean warn = !value.equals(warnedThrottle);
        warnedThrottle = value;
        try {
            return PerfJfr.parseAllocThrottle(value);
        } catch (IllegalArgumentException _) {
            if (warn) {
                io.errors().accept(Messages.get("perf.allocThrottle.invalid", PerfJfr.ALLOC_THROTTLE_VARIABLE, value));
            }
            return null;
        }
    }

    /**
     * Sampling period of the profiler, from {@code POWERJ_PERF_JFR_SAMPLEPERIOD} in the session environment, so that
     * {@code env --set} (in {@code profile.pj} too) changes it from the next line. A value that is not a duration is
     * reported, and the default is used; a period under 1 ms, which the profiler cannot do, becomes 1 ms.
     */
    private Duration samplePeriod() {
        String value = session.environment().get(PerfJfr.SAMPLE_PERIOD_VARIABLE);
        if (value == null || value.isBlank()) {
            return PerfJfr.DEFAULT_SAMPLE_PERIOD;
        }
        boolean warn = !value.equals(warnedPeriod);
        warnedPeriod = value;
        try {
            Duration period = PerfJfr.parseSamplePeriod(value);
            if (period.compareTo(PerfJfr.MINIMUM_SAMPLE_PERIOD) < 0) {
                if (warn) {
                    io.errors().accept(Messages.get("perf.samplePeriod.floor", PerfJfr.SAMPLE_PERIOD_VARIABLE, value));
                }
                return PerfJfr.MINIMUM_SAMPLE_PERIOD;
            }
            return period;
        } catch (IllegalArgumentException _) {
            if (warn) {
                io.errors().accept(Messages.get("perf.samplePeriod.invalid", PerfJfr.SAMPLE_PERIOD_VARIABLE, value));
            }
            return PerfJfr.DEFAULT_SAMPLE_PERIOD;
        }
    }

    /** A new file for a recording, in a folder that goes away with the shell. */
    private synchronized Path recordingFile() {
        try {
            if (recordings == null) {
                recordings = Files.createTempDirectory("powerj-perf-");
                recordings.toFile().deleteOnExit();
            }
            Path file = Files.createTempFile(recordings, "line-", ".jfr");
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException | RuntimeException e) {
            Logger.getLogger(Interpreter.class.getName())
                    .log(Level.WARNING, "No file for the profiler's recording", e);
            return null;
        }
    }

    /** A line is measured unless it is empty, runs in the background, or starts with {@code perf}. */
    private static boolean measurable(Ast.Script script) {
        if (script.steps().isEmpty()) {
            return false;
        }
        for (Ast.Step step : script.steps()) {
            if (step.statement().background()) {
                return false;
            }
        }
        List<Ast.Stage> first = script.steps().getFirst().statement().pipeline().stages();
        return first.isEmpty() || !(first.getFirst().body() instanceof Ast.Command(var name, var forceNative, _)
                && !forceNative && name.equals("perf"));
    }

    /** Keeps the report of the line and prints its summary, even for a cancelled line. */
    private void printPerf(PerfRecorder perf, JfrCapture capture, boolean cancelled) {
        boolean interrupted = Thread.interrupted(); // a cancelled line must still print: keep the flag for later
        try {
            perf.stop(); // before reading the recording: that work is not the line's
            int number = session.nextPerfLine();
            var report = capture == null ? perf.finish(number, cancelled)
                    : perf.finish(number, cancelled, capture.finish(number, perf.probes()), capture.file());
            session.addPerfReport(report);
            PerfFooter.lines(report, io.colors()).forEach(io.out()::println);
            io.out().flush();
        } finally {
            if ("1".equals(session.environment().getOrDefault(PERF_AUTOEXIT_VARIABLE, "").strip())) {
                perfMode = false; // the summary is printed: the mode ends, the next prompt shows it
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean statement(Statement statement) throws InterruptedException {
        Optional<NativeRunner.FileTarget> errTarget = Optional.empty();
        try {
            errTarget = Redirections.target(statement.pipeline(), Token.Stream.ERR, evaluator, session);
            Optional<NativeRunner.FileTarget> outTarget =
                    Redirections.target(statement.pipeline(), Token.Stream.OUT, evaluator, session);
            var redirections = new Redirections(outTarget, errTarget);
            if (statement.background()) {
                return executor.background(statement.pipeline(), redirections);
            }
            if (statement.assignTo().isPresent()) {
                if (outTarget.isPresent()) {
                    throw new PjException(Messages.get("redirect.assignment"));
                }
                List<Object> values = new ArrayList<>();
                boolean succeeded = executor.execute(statement.pipeline(), values::add, true,
                        Redirections.errors(errTarget));
                session.setVariable(statement.assignTo().get(), Evaluator.single(values));
                return succeeded;
            }
            try (var output = new StatementOutput(io, outTarget, recorder)) {
                return executor.execute(statement.pipeline(), output::accept, false, redirections);
            }
        } catch (PjException e) {
            reporter.blocking();
            reporter.report(reporter.describe(e.error()), errTarget);
            return false;
        }
    }

    /** Stops a preview that has received enough values. */
    private static final class PreviewFull extends CancellationException { }

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
        List<Object> values = Collections.synchronizedList(new ArrayList<>());
        boolean more = false;
        try {
            guard.call(() -> executor.execute(statement.pipeline(), value -> previewValue(value, values, limit), true,
                    Redirections.NONE));
        } catch (PreviewFull _) {
            more = true;
        } catch (InterruptedException | CancellationException e) {
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
        var text = new StringWriter();
        try {
            guard.call(() -> {
                var formatter = new OutputFormatter(new PrintWriter(text), width, colors);
                shown.forEach(formatter::accept);
                formatter.close();
                return null;
            });
        } catch (CancellationException | PjException e) {
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
        if (value != null && !ValueUnroller.unroll(value, ValueUnroller.Mode.DISPLAY,
                item -> addPreviewValue(item, values, limit))) {
            addPreviewValue(value, values, limit);
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
        return reporter.hadBlocking();
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
            lines.add(switch (resolution.resolve(name, session)) {
                case Resolved.BuiltinCall _ -> Messages.get("which.builtin", name);
                case Resolved.CmdletCall(var cmdlet) -> Messages.get("which.cmdlet", name, cmdlet.module());
                case Resolved.NativeCall(var path) -> Messages.get("which.native", name, path);
                case Resolved.Unknown(var program, var forcedNative) -> throw new PjException(
                        Messages.get(forcedNative ? "which.programNotFound" : "which.notFound", forcedNative ? program : name));
            });
        }
        return lines;
    }

    /** Values of a parenthesized pipeline: {@code (ls).name}. */
    private List<Object> capture(Ast.Pipeline pipeline) throws InterruptedException {
        if (Redirections.target(pipeline, Token.Stream.OUT, evaluator, session).isPresent()) {
            throw new PjException(Messages.get("redirect.subexpression"));
        }
        List<Object> values = new ArrayList<>();
        Optional<NativeRunner.FileTarget> errTarget =
                Redirections.target(pipeline, Token.Stream.ERR, evaluator, session);
        executor.execute(pipeline, values::add, true, Redirections.errors(errTarget));
        return values;
    }
}
