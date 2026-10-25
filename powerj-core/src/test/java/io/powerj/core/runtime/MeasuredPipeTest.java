package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The pipe of the perf mode (FR-76): the same queue, and counters. */
@Timeout(30)
class MeasuredPipeTest {

    private static void awaitBlocked(Thread thread) {
        while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING) {
            Thread.onSpinWait();
        }
    }

    @Test
    void aMeasuredPipeCountsWhatCrossesAndHowLongTheWriterWaited() throws Exception {
        MeasuredPipe pipe = new MeasuredPipe();
        for (int i = 0; i < 256; i++) {
            pipe.put(i);
        }
        Thread writer = Thread.ofVirtual().start(() -> pipe.put("late"));
        awaitBlocked(writer); // the queue is full: the writer waits for the reader

        assertThat(pipe.next()).isEqualTo(0);
        writer.join();
        List<Object> rest = new ArrayList<>();
        for (int i = 0; i < 256; i++) { // the queue is full again: it must be emptied before it can take the end marker
            rest.add(pipe.next());
        }
        pipe.close();

        assertThat(pipe.next()).isSameAs(Source.END);
        assertThat(rest).endsWith("late");
        assertThat(pipe.putCount()).isEqualTo(257); // the end marker is not counted
        assertThat(pipe.takeCount()).isEqualTo(257);
        assertThat(pipe.putBlockedNanos()).isPositive();
    }

    @Test
    void aMeasuredPipeCountsTheTimeTheReaderWaited() throws Exception {
        MeasuredPipe pipe = new MeasuredPipe();
        CompletableFuture<Object> value = new CompletableFuture<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                value.complete(pipe.next());
            } catch (InterruptedException e) {
                value.completeExceptionally(e);
            }
        });
        awaitBlocked(reader); // the queue is empty: the reader waits for the writer

        pipe.put("hello");

        assertThat(value.get(5, TimeUnit.SECONDS)).isEqualTo("hello");
        reader.join();
        assertThat(pipe.takeBlockedNanos()).isPositive();
        assertThat(pipe.takeCount()).isEqualTo(1);
        assertThat(pipe.putBlockedNanos()).isZero();
    }

    @Test
    void aMeasuredPipeIsStillAPipe() throws Exception {
        var pipe = new MeasuredPipe();

        pipe.put(null);
        pipe.put("a");
        pipe.close();

        assertThat(pipe.next()).isNull();
        assertThat(pipe.next()).isEqualTo("a");
        assertThat(pipe.next()).isSameAs(Source.END);
        assertThat(pipe.putCount()).isEqualTo(2);
        assertThat(pipe.takeCount()).isEqualTo(2);
    }
}
