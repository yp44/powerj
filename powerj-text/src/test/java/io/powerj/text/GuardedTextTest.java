package io.powerj.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The text that lets a regular expression be stopped (#111). {@code java.util.regex} never looks at the interrupt flag
 * and has no budget, so a pattern that backtracks for ever keeps its thread. The matcher reads its input through
 * {@code charAt}: the text asks a guard at each read whether to go on. The terminal stops it on Ctrl+C (the interrupt
 * flag), the grid on a budget of characters read; both go through the same class.
 */
class GuardedTextTest {

    /**
     * Takes time exponential in the length of the line: thirty groups that can each match any number of characters.
     * The classic {@code (a+)+$} no longer does on a recent JDK, which recognizes it.
     */
    private static final String CATASTROPHIC = "(.*a){30}";
    private static final String LINE = "a".repeat(40) + "!";

    @Test
    void itReadsLikeTheTextItWraps() {
        CharSequence text = GuardedText.interruptible("hello");

        assertThat(text.length()).isEqualTo(5);
        assertThat(text.charAt(1)).isEqualTo('e');
        assertThat(text.subSequence(1, 3).toString()).isEqualTo("el");
        assertThat(text.toString()).isEqualTo("hello");
        assertThat(Pattern.compile("l+").matcher(text).find()).isTrue();
    }

    @Test
    void itDoesNotGetInTheWayOfAnOrdinaryMatch() {
        for (CharSequence text : List.of(GuardedText.interruptible("zz abc-123 yy"),
                GuardedText.withBudget("zz abc-123 yy", 10_000))) {
            var matcher = Pattern.compile("(?<word>[a-z]+)-(\\d+)").matcher(text);

            assertThat(matcher.find()).isTrue();
            assertThat(matcher.group("word")).isEqualTo("abc");
            assertThat(matcher.group(2)).isEqualTo("123");
            assertThat(matcher.start()).isEqualTo(3);
        }
    }

    @Test
    void theGuardIsAskedAtEachReadWithTheNumberOfReadsSoFar() {
        var counts = new ArrayList<Long>();
        var text = new GuardedText("abc", reads -> counts.add(reads));

        text.charAt(0);
        text.charAt(2);
        text.charAt(1);

        assertThat(counts).containsExactly(1L, 2L, 3L);
    }

    @Test
    void lengthAndSubSequenceAreNotReads() {
        var counts = new ArrayList<Long>();
        var text = new GuardedText("abcdef", reads -> counts.add(reads));

        text.length();
        text.subSequence(1, 3);
        text.toString();

        assertThat(counts).isEmpty();
    }

    @Test
    void whenTheGuardSaysStopTheReadThrowsAndReadsNothing() {
        var text = new GuardedText("abc", reads -> reads < 3);

        assertThat(text.charAt(0)).isEqualTo('a');
        assertThat(text.charAt(1)).isEqualTo('b');
        assertThatThrownBy(() -> text.charAt(2)).isInstanceOf(GuardedText.Stopped.class);
    }

    @Test
    void stoppedIsACancellationWithoutAStackTrace() {
        var stopped = new GuardedText.Stopped();

        assertThat(stopped).isInstanceOf(CancellationException.class);
        assertThat(stopped.getStackTrace()).isEmpty();
    }

    @Test
    @Timeout(30)
    void aThreadThatIsInterruptedStopsTheMatcher() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> Pattern.compile(CATASTROPHIC).matcher(GuardedText.interruptible(LINE)).find())
                    .isInstanceOf(CancellationException.class);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @Timeout(30)
    void aBudgetStopsTheSameMatcher() {
        assertThatThrownBy(() -> Pattern.compile(CATASTROPHIC).matcher(GuardedText.withBudget(LINE, 100_000)).find())
                .isInstanceOf(GuardedText.Stopped.class);
    }

    @Test
    void aBudgetIsTheNumberOfReadsThatAreAllowed() {
        var text = GuardedText.withBudget("abc", 2);

        text.charAt(0);
        text.charAt(0);
        assertThatThrownBy(() -> text.charAt(0)).isInstanceOf(GuardedText.Stopped.class);
    }

    @Test
    void theInterruptFlagIsOnlyLookedAtEverySoOften() {
        Thread.currentThread().interrupt();
        try {
            var text = GuardedText.interruptible("abc");

            for (int i = 0; i < 1000; i++) {
                text.charAt(0); // cheap: the flag is not read at each character
            }
            assertThatThrownBy(() -> {
                for (int i = 0; i < 100; i++) {
                    text.charAt(0);
                }
            }).isInstanceOf(CancellationException.class);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void itRefusesNullArguments() {
        assertThatThrownBy(() -> new GuardedText(null, reads -> true)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GuardedText("a", null)).isInstanceOf(NullPointerException.class);
    }
}
