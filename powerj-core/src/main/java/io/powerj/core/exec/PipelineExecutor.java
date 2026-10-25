package io.powerj.core.exec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.powerj.api.Collected;
import io.powerj.api.ScriptBlock;
import io.powerj.core.lang.Ast;

/**
 * Runs the pipelines of a line: resolves the stages (FR-13), groups the consecutive native commands, and runs the
 * groups in parallel on virtual threads connected by bounded queues. The last stage runs in the calling thread, which
 * receives the Ctrl+C interruption.
 */
final class PipelineExecutor {

    /** Maximum wait for the stages of a cancelled pipeline to stop. */
    private static final int JOIN_SECONDS = 5;

    private final Session session;
    private final ShellIo io;
    private final CommandResolution resolution;
    private final NativeRunner nativeRunner;
    private final Evaluator evaluator;
    private final ErrorReporter reporter;
    /** Measure of the line being executed, or {@code null}. */
    private final Supplier<PerfRecorder> recorder;
    /** Standard input lines in non-interactive mode, read by the first stage; otherwise {@code null}. */
    private Source standardInput;
    private boolean inheritStandardInput;

    /**
     * @param recorder gives the measure of the line being executed, or {@code null} if it is not measured
     */
    PipelineExecutor(Session session, ShellIo io, CommandResolution resolution, NativeRunner nativeRunner,
                     Evaluator evaluator, ErrorReporter reporter, Supplier<PerfRecorder> recorder) {
        this.session = session;
        this.io = io;
        this.resolution = resolution;
        this.nativeRunner = nativeRunner;
        this.evaluator = evaluator;
        this.reporter = reporter;
        this.recorder = recorder;
    }

    /**
     * Non-interactive mode (FR-04d): the standard input lines feed the first stage that reads
     * objects, and native commands at the head of the pipeline read standard input directly.
     */
    void useStandardInput(Iterator<String> lines) {
        this.standardInput = Source.of(lines);
        this.inheritStandardInput = true;
    }

    /** Prepared stage: arguments evaluated, command resolved. */
    private sealed interface Prepared { }

    /** Stage that produces objects (value, built-in command, cmdlet). */
    private record ObjectStep(ObjectStage stage, String label) implements Prepared { }

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

    private record ObjectSegment(ObjectStage stage, String label) implements Segment { }

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
    boolean execute(Ast.Pipeline pipeline, Consumer<Object> sink, boolean capture, Redirections redirections)
            throws InterruptedException {
        // Perf mode (FR-76): only the outermost pipeline of the line is measured; the others count as "resolve".
        PerfRecorder current = recorder.get();
        PerfRecorder perf = current != null && !PreviewGuard.active() && current.enterPipeline() ? current : null;
        try {
            long resolveStart = System.nanoTime();
            List<Segment> segments = segments(pipeline.stages());
            if (perf != null) {
                perf.addResolve(System.nanoTime() - resolveStart);
            }
            return run(segments, sink, capture, redirections, perf);
        } finally {
            if (perf != null) {
                perf.exitPipeline();
            }
        }
    }

    /**
     * {@code cmd &} (FR-75): starts the native commands of the pipeline without waiting for them, and records the job.
     * {@code $last} describes the last process, with no exit code, as for a detached application (FR-39).
     */
    boolean background(Ast.Pipeline pipeline, Redirections redirections) throws InterruptedException {
        List<Segment> segments = segments(pipeline.stages());
        if (segments.size() != 1 || !(segments.getFirst() instanceof NativeSegment(var commands))) {
            throw new PjException(Messages.get("background.nativeOnly"));
        }
        long start = System.nanoTime();
        var context = new ExecutionContext(session, io, null, false, redirections.withSharedErrors(), io.errors(),
                false);
        List<Process> processes = nativeRunner.startBackground(commands, context);
        String text = String.join(" | ", commands.stream().map(NativeRunner.Command::text).toList());
        Job job = session.jobs().add(text, processes);
        NativeRunner.Command last = commands.getLast();
        session.recordNative(new NativeRun(last.executable(), last.args(), job.pid(), null,
                Duration.ofNanos(System.nanoTime() - start)));
        if (io.interactive()) {
            io.out().println(Messages.get("background.started", job.id(), job.pid()));
            io.out().flush();
        }
        return true;
    }

    private boolean run(List<Segment> segments, Consumer<Object> sink, boolean capture, Redirections redirections,
                        PerfRecorder perf) throws InterruptedException {
        Consumer<String> errorSink = synchronizedErrors(reporter.sink(redirections.err()));
        int n = segments.size();
        List<StageProbe> probes = new ArrayList<>(n);
        for (Segment segment : segments) {
            probes.add(perf == null ? null : perf.newStage(label(segment), segment instanceof NativeSegment));
        }
        if (perf != null) {
            // The last stage hands its objects to the display, or to the variable that captures them.
            sink = probes.getLast().sink(sink, !capture);
        }
        if (n == 1 && segments.getFirst() instanceof NativeSegment(var commands) && commands.size() == 1) {
            NativeRunner.Command command = commands.getFirst();
            StageProbe probe = probes.getFirst();
            long start = System.nanoTime();
            NativeRunner.Result result;
            try {
                result = nativeRunner.run(command, new ExecutionContext(session, io, sink, capture, redirections,
                        errorSink, inheritsInput()));
            } finally {
                if (probe != null) {
                    probe.measured(System.nanoTime() - start);
                }
            }
            if (probe != null) {
                probe.natives(List.of(result.run()));
            }
            session.recordNative(result.run());
            result.capturedLines().forEach(sink);
            return result.run().succeeded();
        }
        // The last stage's surroundings; the others write the errors file too but have no destination for values.
        var last = new ExecutionContext(session, io, sink, capture, redirections.withSharedErrors(), errorSink,
                inheritsInput());
        if (n == 1) {
            return segment(segments.getFirst(), standardInput, null, last, probes.getFirst());
        }
        var intermediate = last.forIntermediateStage();

        List<Pipe> pipes = new ArrayList<>(n - 1);
        for (int i = 0; i < n - 1; i++) {
            pipes.add(new Pipe(perf != null));
            if (perf != null) {
                probes.get(i).output(pipes.get(i));
                probes.get(i + 1).input(pipes.get(i));
            }
        }
        var blockingErrors = new AtomicBoolean();
        List<Thread> threads = new ArrayList<>(n - 1);
        for (int i = 0; i < n - 1; i++) {
            Segment segment = segments.get(i);
            Source input = i == 0 ? standardInput : pipes.get(i - 1);
            Pipe output = pipes.get(i);
            StageProbe probe = probes.get(i);
            String threadName = StageProbe.threadName(probe == null ? i + 1 : probe.index());
            threads.add(Thread.ofVirtual().name(threadName).unstarted(PreviewGuard.propagate(() -> {
                output.onAbort(Thread.currentThread()::interrupt);
                try {
                    segment(segment, input, output, intermediate, probe);
                    output.close();
                } catch (InterruptedException | CancellationException e) {
                    closeQuietly(output);
                } catch (PjException e) {
                    blockingErrors.set(true);
                    errorSink.accept(reporter.describe(e.error()));
                    closeQuietly(output);
                } catch (Throwable t) {
                    blockingErrors.set(true);
                    Logger.getLogger(PipelineExecutor.class.getName())
                            .log(Level.SEVERE, "Internal error in a pipeline stage", t);
                    errorSink.accept(Messages.get("error.internal", t));
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
            succeeded = segment(segments.getLast(), lastInput, null, last, probes.getLast());
        } finally {
            lastInput.abort();
            joinAll(threads);
        }
        if (blockingErrors.get() && !PreviewGuard.active()) {
            reporter.blocking();
        }
        return succeeded && !blockingErrors.get();
    }

    /** Runs a segment; {@code output} is the queue to the next stage, or {@code null} at the end of the pipeline. */
    private boolean segment(Segment segment, Source input, Pipe output, ExecutionContext context, StageProbe probe)
            throws InterruptedException {
        if (probe != null) {
            return probe.run(() -> runSegment(segment, input, output, context, probe));
        }
        return runSegment(segment, input, output, context, null);
    }

    /** @param measuring probe that receives the native processes of the stage, or {@code null} */
    private boolean runSegment(Segment segment, Source input, Pipe output, ExecutionContext context,
                               StageProbe measuring) throws InterruptedException {
        return switch (segment) {
            case ObjectSegment(var stage, _) -> {
                try {
                    yield stage.run(input, output != null ? output::putUnrolled : context.sink(), context.errors());
                } catch (InterruptedException | RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new PjException(PjError.of(e.toString(), e));
                }
            }
            case NativeSegment(var commands) -> {
                List<NativeRun> runs = nativeRunner.runGroup(commands, context, input, output);
                if (measuring != null) {
                    measuring.natives(runs);
                }
                if (output != null && output.aborted()) {
                    // FR-68b: stopped by the next stage (first, range…): not a failure, $exit is left as it is.
                    yield true;
                }
                session.recordNative(runs.getLast());
                yield runs.getLast().succeeded();
            }
        };
    }

    /** Name of a stage in the perf summary: the command and its arguments, shortened. */
    private static String label(Segment segment) {
        return switch (segment) {
            case ObjectSegment(_, var label) -> label;
            case NativeSegment(var commands) -> shorten(String.join(" | ", commands.stream()
                    .map(NativeRunner.Command::text).toList()));
        };
    }

    private static String label(String name, List<Object> args) {
        var text = new StringBuilder(name);
        for (Object arg : args) {
            text.append(' ').append(arg instanceof ScriptBlock ? "{…}" : Values.text(arg));
        }
        return shorten(text.toString());
    }

    private static String shorten(String text) {
        String line = text.replaceAll("\\s+", " ").strip();
        return line.length() > 30 ? line.substring(0, 29) + "…" : line;
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
            var step = (ObjectStep) prepared;
            segments.add(new ObjectSegment(step.stage(), step.label()));
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
                if (followed && value instanceof Collected<?> list) {
                    // $l | map { … }: a value at the head of a pipeline is always unrolled, like any list;
                    // only a list passed from one command to the next stays whole (FR-36d).
                    list.forEach(output);
                } else {
                    output.accept(value);
                }
                return !(value instanceof Boolean b) || b;
            }, "expression");
        }
        var command = (Ast.Command) stage.body();
        List<Object> args = evaluator.arguments(command.arguments());
        return switch (resolution.resolve(command.name(), command.forceNative(), session)) {
            case Resolved.BuiltinCall(var name, var builtin) -> {
                PreviewGuard.deny(Messages.get("preview.off.builtin", name));
                if (index > 0) {
                    throw new PjException(Messages.get("pipeline.noInput", name));
                }
                yield new ObjectStep((_, output, _) -> {
                    invokeBuiltin(name, builtin, args).forEach(output);
                    return true;
                }, label(name, args));
            }
            case Resolved.CmdletCall(var cmdlet) -> {
                if (args.contains("--help")) {
                    yield new ObjectStep((_, output, _) -> {
                        Help.cmdlet(cmdlet).forEach(output);
                        return true;
                    }, label(command.name(), args));
                }
                if (index > 0 && !cmdlet.readsInput()) {
                    throw new PjException(Messages.get("pipeline.noInput", command.name()));
                }
                if (!cmdlet.preview()) {
                    PreviewGuard.deny(Messages.get("preview.off.cmdlet", command.name()));
                }
                yield new ObjectStep((input, output, errors) -> runCmdlet(cmdlet, args,
                        cmdlet.readsInput() ? input : null, output,
                        stage.errorsToOutput() ? message -> output.accept(message) : errors),
                        label(command.name(), args));
            }
            case Resolved.NativeCall(var executable) -> {
                PreviewGuard.deny(Messages.get("preview.off.native", command.name()));
                List<String> textArgs = args.stream().map(Values::text).toList();
                yield new NativeStep(new NativeRunner.Command(executable, textArgs, stage.errorsToOutput()));
            }
            case Resolved.Unknown _ -> throw unknownCommand(command);
        };
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
            return CmdletRunner.run(cmdlet, args, session, new CmdletRunner.Connections(input, output, errors),
                    evaluator::compile);
        } catch (PjException | InterruptedException | CancellationException e) {
            throw e;
        } catch (Exception e) {
            throw new PjException(PjError.of(Messages.get("command.error", cmdlet.name(), e), e));
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

    /** Does the first process read the shell's standard input directly? */
    private boolean inheritsInput() {
        return io.interactive() || inheritStandardInput;
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
        } catch (CancellationException _) {
            // pipeline already stopped
        }
    }

    /** Waits for the stages to finish (stopped if needed), without letting itself be interrupted. */
    private static void joinAll(List<Thread> threads) {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(JOIN_SECONDS);
        for (Thread thread : threads) {
            while (thread.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break; // stage blocked in a non-interruptible call: abandoned (FR-57)
                }
                try {
                    thread.join(Duration.ofNanos(remaining));
                } catch (InterruptedException _) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
