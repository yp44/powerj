package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.powerj.api.Collected;

/** The link between two pipeline stages (review C-30: it had no test of its own). */
class PipeTest {

    private static List<Object> drain(Pipe pipe) throws InterruptedException {
        List<Object> values = new ArrayList<>();
        for (Object value = pipe.next(); value != Source.END; value = pipe.next()) {
            values.add(value);
        }
        return values;
    }

    @Test
    void valuesComeOutInTheOrderTheyWentIn() throws Exception {
        Pipe pipe = new Pipe();
        pipe.put("a");
        pipe.put(2);
        pipe.put("c");
        pipe.close();

        assertThat(drain(pipe)).containsExactly("a", 2, "c");
    }

    @Test
    void nullTravelsThroughTheQueue() throws Exception {
        Pipe pipe = new Pipe();
        pipe.put(null);
        pipe.put("x");
        pipe.close();

        assertThat(drain(pipe)).containsExactly(null, "x");
    }

    @Test
    void closeSendsTheEndMarker() throws Exception {
        Pipe pipe = new Pipe();
        pipe.close();

        assertThat(pipe.next()).isSameAs(Source.END);
    }

    @Test
    void aFastWriterWaitsForASlowReader() throws Exception {
        Pipe pipe = new Pipe();
        for (int i = 0; i < 256; i++) {
            pipe.put(i); // the queue holds 256 objects: none of these blocks
        }
        CountDownLatch writing = new CountDownLatch(1);
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
            writing.countDown();
            pipe.put("one too many");
        });

        assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> writer.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(pipe.next()).isEqualTo(0); // room for one: the writer goes on
        writer.get(5, TimeUnit.SECONDS);
    }

    @Test
    void abortingStopsTheWriterAndRunsTheCallbacksOnce() {
        Pipe pipe = new Pipe();
        AtomicInteger calls = new AtomicInteger();
        pipe.onAbort(calls::incrementAndGet);
        pipe.put("queued");

        pipe.abort();
        pipe.abort();

        assertThat(calls).hasValue(1);
        assertThat(pipe.aborted()).isTrue();
        assertThatThrownBy(() -> pipe.put("late")).isInstanceOf(CancellationException.class);
    }

    @Test
    void aCallbackRegisteredAfterTheAbortRunsAtOnce() {
        Pipe pipe = new Pipe();
        pipe.abort();
        AtomicInteger calls = new AtomicInteger();

        pipe.onAbort(calls::incrementAndGet);

        assertThat(calls).hasValue(1);
    }

    @Test
    void closingAnAbortedPipeIsHarmless() {
        Pipe pipe = new Pipe();
        pipe.abort();

        pipe.close();

        assertThat(pipe.aborted()).isTrue();
    }

    @Test
    void aWriterWaitingForRoomIsReleasedByTheInterruption() throws Exception {
        Pipe pipe = new Pipe();
        for (int i = 0; i < 256; i++) {
            pipe.put(i);
        }
        CompletableFuture<Throwable> outcome = new CompletableFuture<>();
        Thread writer = Thread.ofVirtual().start(() -> {
            try {
                pipe.put("blocked");
                outcome.complete(null);
            } catch (Throwable t) {
                outcome.complete(t);
            }
        });
        while (writer.getState() != Thread.State.WAITING && writer.getState() != Thread.State.TIMED_WAITING) {
            Thread.onSpinWait();
        }

        writer.interrupt();

        assertThat(outcome.get(5, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
    }

    @Test
    void collectionsArraysStreamsAndOptionalsAreUnrolled() throws Exception {
        Pipe pipe = new Pipe();
        pipe.putUnrolled(List.of(1, 2));
        pipe.putUnrolled(new String[] {"a", "b"});
        pipe.putUnrolled(new int[] {7, 8});
        pipe.putUnrolled(Stream.of("s1", "s2"));
        pipe.putUnrolled(IntStream.of(5, 6));
        pipe.putUnrolled(Arrays.asList(9, 10).iterator());
        pipe.putUnrolled(Optional.of("present"));
        pipe.putUnrolled(Optional.empty());
        pipe.putUnrolled(OptionalInt.of(3));
        pipe.close();

        assertThat(drain(pipe)).containsExactly(1, 2, "a", "b", 7, 8, "s1", "s2", 5, 6, 9, 10, "present", 3);
    }

    @Test
    void stringsMapsAndPathsAreNeverUnrolled() throws Exception {
        Pipe pipe = new Pipe();
        Map<String, Integer> map = Map.of("k", 1);
        java.nio.file.Path path = java.nio.file.Path.of("a", "b");
        pipe.putUnrolled("text");
        pipe.putUnrolled(map);
        pipe.putUnrolled(path);
        pipe.close();

        assertThat(drain(pipe)).containsExactly("text", map, path);
    }

    @Test
    void aCollectedListIsPassedWholeAsCollectPromises() throws Exception {
        Pipe pipe = new Pipe();
        Collected<String> list = new Collected<>(List.of("x", "y"));
        pipe.putUnrolled(list);
        pipe.putUnrolled(null);
        pipe.close();

        assertThat(drain(pipe)).containsExactly(list, null);
    }

    @Test
    void aStreamIsClosedOnceUnrolled() {
        Pipe pipe = new Pipe();
        AtomicInteger closed = new AtomicInteger();
        Stream<String> stream = Stream.of("a").onClose(closed::incrementAndGet);

        pipe.putUnrolled(stream);

        assertThat(closed).hasValue(1);
    }

    private static void awaitBlocked(Thread thread) {
        while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING) {
            Thread.onSpinWait();
        }
    }

    @Test
    void aMeasuredPipeCountsWhatCrossesAndHowLongTheWriterWaited() throws Exception {
        Pipe pipe = new Pipe(true);
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
        Pipe pipe = new Pipe(true);
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
}
