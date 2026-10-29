package io.powerj.core.runtime;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

import io.powerj.core.error.PjException;
import io.powerj.core.format.OutputFormatter;
import io.powerj.core.format.ValueUnroller;
import io.powerj.core.i18n.Messages;
import io.powerj.core.java.PreviewGuard;
import io.powerj.core.lang.Ast.Statement;
import io.powerj.core.lang.Ast;
import io.powerj.core.lang.Parser;
import io.powerj.core.lang.ParseContext;
import io.powerj.core.lang.SyntaxException;

/**
 * Preview mode (FR-66): what the line would show, computed while it is typed, with nothing that changes anything. It
 * runs the line through the same {@link PipelineExecutor} as execution, under a {@link PreviewGuard} that stops it at
 * the first thing that would have an effect.
 */
final class PreviewMode {

    /** Stops a preview that has received enough values. */
    private static final class PreviewFull extends CancellationException { }

    private final Supplier<ParseContext> parseContext;
    private final PipelineExecutor executor;
    private final Supplier<PreviewSettings> settings;

    /** @param settings read at each preview: the limit of the stages is the one of the moment */
    PreviewMode(Supplier<ParseContext> parseContext, PipelineExecutor executor, Supplier<PreviewSettings> settings) {
        this.parseContext = parseContext;
        this.executor = executor;
        this.settings = settings;
    }

    /**
     * Preview mode (FR-66): evaluates the line as {@link Interpreter#execute} would, but only with what changes nothing — cmdlets
     * declared {@code preview = true}, expressions, Java calls of the preview set — and stops after {@code limit}
     * values. Nothing is displayed, no variable is set, {@code $?} and {@code $errors} are left as they are.
     * Cancelled by interrupting the calling thread: the values received so far are then returned, or
     * {@link PreviewResult.TimedOut} if there are none. Each stage but the last sends at most
     * {@link PreviewSettings#stageLimit()} objects to the next one, so that a stage that emits only when its input
     * has ended ({@code sort}, {@code group}) has something to show; the result then says it is
     * {@link PreviewResult.Values#partial() partial}.
     *
     * @param width  width of the formatted lines ({@link PreviewResult.Values#lines()})
     * @param colors {@code true} if the lines may contain ANSI colors
     */
    PreviewResult preview(String line, int limit, int width, boolean colors) {
        if (line.isBlank()) {
            return new PreviewResult.Empty();
        }
        Ast.Script script;
        try {
            script = Parser.parse(line, parseContext.get());
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
        var guard = new PreviewGuard(settings.get().stageLimit());
        List<Object> values = Collections.synchronizedList(new ArrayList<>());
        boolean more = false;
        boolean interrupted = false;
        try {
            guard.call(() -> executor.execute(statement.pipeline(), value -> previewValue(value, values, limit), true,
                    Redirections.NONE));
        } catch (PreviewFull _) {
            more = true;
        } catch (InterruptedException | CancellationException e) {
            if (guard.reason() == null) {
                more = true; // cancelled by the caller (time limit): what has arrived is shown
                interrupted = true;
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
        if (Thread.currentThread().isInterrupted()) {
            // The pipeline waits for its stages without letting itself be interrupted, then gives the interruption back
            // instead of throwing: it still is the time limit that stopped the evaluation.
            more = true;
            interrupted = true;
        }
        List<Object> shown;
        synchronized (values) {
            shown = new ArrayList<>(values.subList(0, Math.min(limit, values.size())));
            more |= values.size() > limit;
        }
        if (interrupted && shown.isEmpty()) {
            return new PreviewResult.TimedOut(guard.errors()); // not "no result": the line was cut off, not empty
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
        return new PreviewResult.Values(shown, lines, more, guard.errors(), guard.wasCut());
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
}
