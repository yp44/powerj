package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Ctrl+C stops a regular expression that backtracks for ever (review C-18). */
class InterruptibleTextTest {

    /**
     * Takes time exponential in the length of the line: thirty groups that can each match any number of characters.
     * The classic {@code (a+)+$} no longer does on a recent JDK, which recognizes it.
     */
    private static final String CATASTROPHIC = "(.*a){30}";
    private static final String LINE = "a".repeat(40) + "!";

    @Test
    void itReadsLikeTheTextItWraps() {
        CharSequence text = new InterruptibleText("hello");

        assertThat(text.length()).isEqualTo(5);
        assertThat(text.charAt(1)).isEqualTo('e');
        assertThat(text.subSequence(1, 3).toString()).isEqualTo("el");
        assertThat(text.toString()).isEqualTo("hello");
        assertThat(Pattern.compile("l+").matcher(text).find()).isTrue();
    }

    @Test
    void itDoesNotGetInTheWayOfAnOrdinaryMatch() {
        var matcher = Pattern.compile("(?<word>[a-z]+)-(\\d+)").matcher(new InterruptibleText("zz abc-123 yy"));

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group("word")).isEqualTo("abc");
        assertThat(matcher.group(2)).isEqualTo("123");
        assertThat(matcher.start()).isEqualTo(3);
    }

    @Test
    void aThreadThatIsInterruptedStopsTheMatcher() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> Pattern.compile(CATASTROPHIC).matcher(new InterruptibleText(LINE)).find())
                    .isInstanceOf(CancellationException.class);
        } finally {
            Thread.interrupted();
        }
    }

    /** Runs {@code work} on another thread, interrupts it after a moment and tells how it ended. */
    private static Throwable interrupted(Runnable work) throws InterruptedException {
        var outcome = new AtomicReference<Throwable>();
        Thread thread = Thread.ofPlatform().daemon().start(() -> {
            try {
                work.run();
                outcome.set(new AssertionError("the regular expression ended by itself"));
            } catch (Throwable e) {
                outcome.set(e);
            }
        });
        Thread.sleep(200);
        thread.interrupt();
        thread.join(10_000);
        assertThat(thread.isAlive()).as("the thread is still stuck in the regular expression").isFalse();
        return outcome.get();
    }

    @Test
    @Timeout(30)
    void grepFilteringThePipelineStopsOnCtrlC() throws Exception {
        var context = new TestContext<Object>(Path.of("."));
        var grep = new Grep();
        var params = new Grep.Params(CATASTROPHIC, List.of(), false, null, null, true, false, false, null, null, false, false);

        Throwable outcome = interrupted(() -> {
            try {
                grep.begin(params, context);
                grep.process(params, LINE, context);
            } catch (RuntimeException e) {
                throw e; // a cancellation is not wrapped
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(outcome).isInstanceOf(CancellationException.class);
    }

    @Test
    @Timeout(30)
    void rangeMatchingARegexStopsOnCtrlC() throws Exception {
        var context = new TestContext<Object>(Path.of("."));
        context.hasInput = true;
        context.compiler = source -> new io.powerj.api.ScriptBlock() {
            @Override
            public Object invoke(Object current) {
                return current;
            }

            @Override
            public String source() {
                return source;
            }
        };
        var range = new Range();
        var params = new Range.Params(CATASTROPHIC, null, "name", false, false, false, false);

        Throwable outcome = interrupted(() -> {
            try {
                range.begin(params, context);
                range.process(params, LINE, context);
            } catch (RuntimeException e) {
                throw e; // a cancellation is not wrapped
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(outcome).isInstanceOf(CancellationException.class);
    }
}
