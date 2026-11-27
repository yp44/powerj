package io.powerj.shell;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import io.powerj.core.error.PjException;
import io.powerj.core.java.Applicable;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.Supervisor;

/**
 * The prompt (FR-01, FR-79). By default {@code PJ <dir>> }, {@code PJ? <dir>> } in preview mode (FR-66),
 * {@code PJ% <dir>> } in perf mode (FR-76). When the user has set {@code $prompt} to a block, the prompt is what the block
 * returns, evaluated before each line outside of any line typed ({@code $?}, {@code $exit}, {@code $last},
 * {@code $errors} are left alone). Before each evaluation {@code $POWERJ_MODE_CURRENT} is set to the mode:
 * {@code normal}, {@code preview} or {@code perf}.
 * <p>
 * The prompt never blocks the shell nor breaks it: a block that fails, returns {@code null}, is not a block, or takes
 * longer than {@code POWERJ_PROMPT_TIMEOUT_MS} gives the default prompt for that line, and a message says why; Ctrl+C
 * during the evaluation gives the default prompt at once.
 */
final class PromptRenderer {

    private static final Logger LOG = Logger.getLogger(PromptRenderer.class.getName());

    /** The variable that holds the user's block. */
    static final String PROMPT_VARIABLE = "prompt";
    /** The variable that holds the mode, read by the block. */
    static final String MODE_VARIABLE = "POWERJ_MODE_CURRENT";
    /** Variable of the session environment: the longest evaluation of {@code $prompt}, in milliseconds. */
    static final String TIMEOUT_VARIABLE = "POWERJ_PROMPT_TIMEOUT_MS";
    static final int DEFAULT_TIMEOUT_MILLIS = 500;
    /** A longer wait freezes the shell before each line: a larger value counts as this one. */
    static final int MAX_TIMEOUT_MILLIS = 10_000;
    private static final long STACK_SIZE = 16L * 1024 * 1024;

    private final Session session;
    private final ModeController modes;
    private final Terminal terminal;
    private final boolean colors;
    /** Calls the block outside of any line; {@code null}: {@code $prompt} is not looked at. */
    private final Function<Applicable, Object> caller;
    /** Told why the default prompt is shown in place of the user's, and {@code null} before each prompt: nothing to say yet. */
    private final Consumer<String> problems;

    /** The thread of the last evaluation, and what it will deliver while it runs. */
    private volatile Thread worker;
    private volatile CompletableFuture<Object> pending;

    /** The default prompt only: {@code $prompt} is not looked at. */
    PromptRenderer(Session session, ModeController modes, Terminal terminal, boolean colors) {
        this(session, modes, terminal, colors, null, _ -> { });
    }

    /**
     * @param caller   calls a block outside of any line ({@code Interpreter::callDetached})
     * @param problems told the message to show when the default prompt replaces the user's
     */
    PromptRenderer(Session session, ModeController modes, Terminal terminal, boolean colors,
                   Function<Applicable, Object> caller, Consumer<String> problems) {
        this.session = session;
        this.modes = modes;
        this.terminal = terminal;
        this.colors = colors;
        this.caller = caller;
        this.problems = problems;
        modeVariable(modes.current());
    }

    String render() {
        ShellMode mode = modes.current();
        modeVariable(mode);
        problems.accept(null);
        String custom = custom();
        return custom != null ? custom : defaultPrompt(mode);
    }

    /**
     * The prompt as JLine must receive it: every {@code %} doubled, because JLine reads {@code %N}, {@code %M},
     * {@code %P}, {@code %{}… in a prompt as patterns, and a current directory is any name (U-13); the text of
     * {@code $prompt} is shown as the block wrote it too (FR-79). The one place that knows the rule: the first prompt, and
     * the prompt reset when the mode changes, all come from here.
     */
    String forJLine() {
        return render().replace("%", "%%");
    }

    /** Ctrl+C: the evaluation of {@code $prompt} under way, if there is one, gives way to the default prompt. */
    void cancel() {
        CompletableFuture<Object> current = pending;
        if (current != null && current.completeExceptionally(new CancellationException())) {
            Thread thread = worker;
            if (thread != null) {
                thread.interrupt();
            }
        }
    }

    private void modeVariable(ShellMode mode) {
        session.setVariable(MODE_VARIABLE, mode.name().toLowerCase(Locale.ROOT));
    }

    private String defaultPrompt(ShellMode mode) {
        String marker = switch (mode) {
            case NORMAL -> "PJ ";
            case PREVIEW -> marker("PJ?", AttributedStyle.CYAN);
            case PERF -> marker("PJ%", AttributedStyle.YELLOW);
        };
        return marker + session.currentDirectory() + "> ";
    }

    private String marker(String text, int color) {
        return colors ? new AttributedString(text, AttributedStyle.DEFAULT.foreground(color)).toAnsi(terminal) + " "
                : text + " ";
    }

    /** The text of the user's prompt, or {@code null} for the default one (none set, or it did not give one). */
    private String custom() {
        if (caller == null || !session.variableNames().contains(PROMPT_VARIABLE)) {
            return null;
        }
        Object value = session.variable(PROMPT_VARIABLE);
        if (value == null) {
            return null; // $prompt = null: back to the default prompt
        }
        if (!(value instanceof Applicable block)) {
            problems.accept(Messages.get("prompt.notABlock", value.getClass().getSimpleName()));
            return null;
        }
        Object result = evaluate(block);
        return result == null ? null : String.valueOf(result);
    }

    /** What the block returns, or {@code null} once the problem has been told. */
    private Object evaluate(Applicable block) {
        int timeout = timeoutMillis();
        Thread previous = worker;
        if (previous != null && previous.isAlive() && !joined(previous, timeout)) {
            problems.accept(Messages.get("prompt.stillRunning"));
            return null; // one evaluation at a time: a block that never ends does not start one more thread per line
        }
        var result = new CompletableFuture<Object>();
        Thread thread = Thread.ofPlatform().name("powerj-prompt").daemon().stackSize(STACK_SIZE).unstarted(() -> {
            try {
                result.complete(caller.apply(block));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        worker = thread;
        pending = result;
        try {
            thread.start();
            Object value = result.get(timeout, TimeUnit.MILLISECONDS);
            if (value == null) {
                problems.accept(Messages.get("prompt.null"));
            }
            return value;
        } catch (TimeoutException _) {
            thread.interrupt();
            problems.accept(Messages.get("prompt.timeout", timeout, TIMEOUT_VARIABLE));
        } catch (CancellationException _) {
            problems.accept(Messages.get("prompt.cancelled"));
        } catch (InterruptedException _) {
            thread.interrupt();
            problems.accept(Messages.get("prompt.cancelled"));
        } catch (ExecutionException e) {
            problems.accept(failure(e.getCause()));
        } finally {
            pending = null;
        }
        return null;
    }

    private static boolean joined(Thread thread, int timeoutMillis) {
        try {
            return thread.join(Duration.ofMillis(timeoutMillis));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The message for a block that threw: the one a line would show, with the Java stack trace if {@code $debug}. */
    private String failure(Throwable t) {
        if (t instanceof CancellationException || t instanceof InterruptedException) {
            return Messages.get("prompt.cancelled");
        }
        String message;
        Throwable cause;
        if (t instanceof PjException e) {
            message = e.error().message();
            cause = e.error().cause().orElse(null);
        } else {
            LOG.log(Level.WARNING, "The block of $prompt failed", t);
            message = Supervisor.messageOf(t);
            cause = t;
        }
        String text = Messages.get("prompt.failed", message);
        if (cause == null || !session.debug()) {
            return text;
        }
        var trace = new StringWriter();
        cause.printStackTrace(new PrintWriter(trace));
        return text + System.lineSeparator() + trace.toString().stripTrailing();
    }

    /** {@code POWERJ_PROMPT_TIMEOUT_MS} of the session environment, read at each prompt; the default if not valid. */
    private int timeoutMillis() {
        String text = session.environment().get(TIMEOUT_VARIABLE);
        if (text == null) {
            return DEFAULT_TIMEOUT_MILLIS;
        }
        try {
            int value = Integer.parseInt(text.strip());
            return value > 0 ? Math.min(value, MAX_TIMEOUT_MILLIS) : DEFAULT_TIMEOUT_MILLIS;
        } catch (NumberFormatException _) {
            return DEFAULT_TIMEOUT_MILLIS;
        }
    }
}
