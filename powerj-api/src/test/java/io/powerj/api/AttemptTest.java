package io.powerj.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;

/** {@link ScriptBlock#tryInvoke} and {@link ScriptBlock#tryTest}: an error is a result, a cancellation is not. */
class AttemptTest {

    private static ScriptBlock block(java.util.function.Function<Object, Object> body) {
        return new ScriptBlock() {
            @Override
            public Object invoke(Object current) {
                return body.apply(current);
            }

            @Override
            public String source() {
                return "block";
            }
        };
    }

    @Test
    void aValueIsASuccess() {
        var attempt = block(x -> x + "!").tryInvoke("a");

        assertThat(attempt.failed()).isFalse();
        assertThat(attempt.value()).isEqualTo("a!");
        assertThat(attempt.error()).isNull();
    }

    @Test
    void aNullValueIsASuccessToo() {
        var attempt = block(x -> null).tryInvoke("a");

        assertThat(attempt.failed()).isFalse();
        assertThat(attempt.value()).isNull();
    }

    @Test
    void anErrorOfTheEvaluationIsTheResult() {
        var failure = new IllegalArgumentException("bad object");

        var attempt = block(x -> {
            throw failure;
        }).tryInvoke("a");

        assertThat(attempt.failed()).isTrue();
        assertThat(attempt.error()).isSameAs(failure);
        assertThat(attempt.value()).isNull();
    }

    @Test
    void aCancellationIsThrownAndNotTakenForAnError() {
        var cancelled = new CancellationException("Ctrl+C");

        assertThatThrownBy(() -> block(x -> {
            throw cancelled;
        }).tryInvoke("a")).isSameAs(cancelled);
        assertThatThrownBy(() -> block(x -> {
            throw cancelled;
        }).tryTest("a")).isSameAs(cancelled);
    }

    @Test
    void tryTestReadsABoolean() {
        assertThat(block(x -> true).tryTest("a").value()).isTrue();
        assertThat(block(x -> false).tryTest("a").value()).isFalse();
    }

    @Test
    void tryTestMakesAResultThatIsNotABooleanAFailure() {
        var attempt = block(x -> "yes").tryTest("a");

        assertThat(attempt.failed()).isTrue();
        assertThat(attempt.error()).isInstanceOf(IllegalStateException.class).hasMessageContaining("String yes");
    }

    @Test
    void mapChangesAValueAndLeavesAFailureAlone() {
        assertThat(Attempt.succeeded(2).map(n -> n * 3).value()).isEqualTo(6);
        var failure = new IllegalStateException("x");

        var mapped = Attempt.<Integer>failed(failure).map(n -> n * 3);

        assertThat(mapped.failed()).isTrue();
        assertThat(mapped.error()).isSameAs(failure);
    }

    @Test
    void anAttemptIsNotBothAValueAndAnError() {
        assertThatThrownBy(() -> new Attempt<>("v", new RuntimeException()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Attempt.failed(null)).isInstanceOf(NullPointerException.class);
    }
}
