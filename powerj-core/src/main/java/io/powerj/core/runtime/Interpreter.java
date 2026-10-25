package io.powerj.core.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

import io.powerj.core.command.Help;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.command.ModuleLoader;
import io.powerj.core.command.NativeAccessPolicy;
import io.powerj.core.error.PjException;
import io.powerj.core.i18n.Messages;
import io.powerj.core.lang.Ast.Statement;
import io.powerj.core.lang.Ast;
import io.powerj.core.lang.Parser;
import io.powerj.core.lang.ParseContext;
import io.powerj.core.lang.SyntaxException;
import io.powerj.core.lang.RedirectedStream;
import io.powerj.core.nativeexec.CommandResolver;

/**
 * Executes a line: statements chained by {@code ;}, {@code &&}, {@code ||} (FR-04c) or launched in the background
 * by {@code &} (FR-75), pipelines
 * {@code |}, built-in commands, cmdlets, native commands, expressions, assignments and redirections. Resolution order
 * of a command (FR-13): built-in command, cmdlet, program on the {@code PATH}; {@code ^name} forces the
 * program.
 */
public final class Interpreter implements CommandCatalog {

    private final Session session;
    private final ShellIo io;
    private final Map<String, Builtin> builtins = new LinkedHashMap<>();
    private final CmdletRegistry registry;
    private final ModuleLoader modules;
    private final CommandResolution resolution;
    private final Evaluator evaluator;
    private final ErrorReporter reporter;
    private final PipelineExecutor executor;
    private final PreviewMode preview;
    private final LineMeasuring perf;
    /** Who measures the line being executed. */
    private volatile ExecutionObserver observer = ExecutionObserver.NONE;

    /**
     * @param extraBuiltins built-in commands provided by the shell (e.g. {@code history})
     */
    public Interpreter(Session session, ShellIo io, Map<String, Builtin> extraBuiltins, CmdletRegistry registry) {
        this(session, io, extraBuiltins, registry, LineMeasuring.OFF);
    }

    /**
     * @param measuring the perf mode (FR-76), plugged in from outside; {@link LineMeasuring#OFF} for none
     */
    public Interpreter(Session session, ShellIo io, Map<String, Builtin> extraBuiltins, CmdletRegistry registry,
                       LineMeasuring measuring) {
        this(session, io, extraBuiltins, registry, new CommandResolver(), new NativeRunner(), measuring);
    }

    Interpreter(Session session, ShellIo io, Map<String, Builtin> extraBuiltins, CmdletRegistry registry,
                CommandResolver resolver, NativeRunner nativeRunner) {
        this(session, io, extraBuiltins, registry, resolver, nativeRunner, LineMeasuring.OFF);
    }

    private Interpreter(Session session, ShellIo io, Map<String, Builtin> extraBuiltins, CmdletRegistry registry,
                        CommandResolver resolver, NativeRunner nativeRunner, LineMeasuring measuring) {
        this.session = session;
        this.io = io;
        this.registry = registry;
        this.evaluator = new Evaluator(session, this::capture, this::parseContext);
        this.reporter = new ErrorReporter(session, io);
        this.perf = measuring;
        this.modules = new ModuleLoader(registry, Collections.unmodifiableSet(builtins.keySet()));
        // The order is the one of the overview printed by `help`; the shell's own commands come before `exit`.
        builtins.put("cd", Builtins::cd);
        builtins.put("pwd", Builtins::pwd);
        builtins.put("which", Builtins.which(this::resolution));
        builtins.put("help", (args, _) -> Help.run(args, registry, List.copyOf(builtins.keySet()), session.java()));
        builtins.put("import", Builtins::importClasses);
        builtins.put("mod-load", Builtins.modLoad(modules, io));
        builtins.put("mod-list", Builtins.modList(registry));
        builtins.put("jobs", (_, s) -> new ArrayList<>(s.jobs().list()));
        builtins.putAll(extraBuiltins);
        builtins.putIfAbsent("exit", Builtins::exit);
        this.resolution = new CommandResolution(builtins, registry, resolver);
        this.executor = new PipelineExecutor(session, io, resolution, nativeRunner, evaluator, reporter,
                () -> observer);
        this.preview = new PreviewMode(this::parseContext, executor);
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

    @Override
    public Session session() {
        return session;
    }

    public CmdletRegistry registry() {
        return registry;
    }

    /** Names of the built-in commands. */
    @Override
    public Set<String> builtinNames() {
        return resolution.builtinNames();
    }

    @Override
    public Collection<CmdletRegistry.Registered> cmdlets() {
        return registry.all();
    }

    @Override
    public Optional<CmdletRegistry.Registered> cmdlet(String name) {
        return resolution.cmdlet(name);
    }

    @Override
    public Resolved resolve(String name) {
        return resolution.resolve(name, session);
    }

    /** What a command name stands for (FR-13): the rule that execution, {@code which}, highlighting and completion share. */
    public CommandResolution resolution() {
        return resolution;
    }

    /** Java static names, and the commands whose cmdlet declares the short form {@code where size > 1mb}. */
    private ParseContext parseContext() {
        return new ParseContext(session.java()::isStaticReference, this::acceptsComparison);
    }

    /** {@code @CmdletInfo(comparison = true)} on the cmdlet the name runs (FR-13: a built-in command comes first). */
    private boolean acceptsComparison(String name) {
        return resolution.cmdlet(name).map(CmdletRegistry.Registered::comparison).orElse(false);
    }

    /** Resolves a command name as execution would (FR-13), without launching anything. */
    @Override
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
        Ast.Script script;
        try {
            script = Parser.parse(line, parseContext());
        } catch (SyntaxException e) {
            throw new PjException(e.getMessage()); // a line that does not parse is an error to show, like any other
        }
        long parsed = System.nanoTime() - begin;
        LineMeasuring.Measurement measurement = perf.start(line, script, begin, parsed);
        observer = measurement.observer();
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
            observer = ExecutionObserver.NONE;
            measurement.finish(cancelled);
        }
    }

    /** Perf mode (FR-76): {@code true} while the lines executed are measured. */
    public boolean perfMode() {
        return perf.on();
    }

    public void perfMode(boolean on) {
        perf.on(on);
    }

    private boolean statement(Statement statement) throws InterruptedException {
        Optional<NativeRunner.FileTarget> errTarget = Optional.empty();
        try {
            errTarget = Redirections.target(statement.pipeline(), RedirectedStream.ERR, evaluator, session);
            Optional<NativeRunner.FileTarget> outTarget =
                    Redirections.target(statement.pipeline(), RedirectedStream.OUT, evaluator, session);
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
            try (var output = new StatementOutput(io, outTarget, observer)) {
                return executor.execute(statement.pipeline(), output::accept, false, redirections);
            }
        } catch (PjException e) {
            reporter.blocking();
            reporter.report(reporter.describe(e.error()), errTarget);
            return false;
        }
    }

    /**
     * Preview mode (FR-66): evaluates the line as {@link #execute} would, but only with what changes nothing, and stops
     * after {@code limit} values. Nothing is displayed, no variable is set. Cancelled by interrupting the calling thread:
     * the values received so far are then returned.
     *
     * @param width  width of the formatted lines ({@link PreviewResult.Values#lines()})
     * @param colors {@code true} if the lines may contain ANSI colors
     */
    public PreviewResult preview(String line, int limit, int width, boolean colors) {
        return preview.preview(line, limit, width, colors);
    }

    /** {@code true} if the last executed line hit a blocking error (not just a failure). */
    public boolean hadBlockingError() {
        return reporter.hadBlocking();
    }

    /** Values of a parenthesized pipeline: {@code (ls).name}. */
    private List<Object> capture(Ast.Pipeline pipeline) throws InterruptedException {
        if (Redirections.target(pipeline, RedirectedStream.OUT, evaluator, session).isPresent()) {
            throw new PjException(Messages.get("redirect.subexpression"));
        }
        List<Object> values = new ArrayList<>();
        Optional<NativeRunner.FileTarget> errTarget =
                Redirections.target(pipeline, RedirectedStream.ERR, evaluator, session);
        executor.execute(pipeline, values::add, true, Redirections.errors(errTarget));
        return values;
    }
}
