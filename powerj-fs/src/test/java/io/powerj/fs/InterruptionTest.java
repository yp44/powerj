package io.powerj.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;

class InterruptionTest {

    @Test
    void anInterruptedReadIsACancellationAndTheFlagIsSetAgain() {
        try {
            assertThatThrownBy(() -> Interruption.rethrow(new ClosedByInterruptException()))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        try {
            assertThatThrownBy(() -> Interruption.rethrow(new InterruptedIOException("read")))
                    .isInstanceOf(CancellationException.class);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anyOtherFailureIsLeftAlone() {
        assertThatCode(() -> Interruption.rethrow(new IOException("disk"))).doesNotThrowAnyException();
        assertThatCode(() -> Interruption.rethrow(new NoSuchFileException("x"))).doesNotThrowAnyException();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(Interruption.is(new ClosedByInterruptException())).isTrue();
        assertThat(Interruption.is(new IOException())).isFalse();
    }
}
