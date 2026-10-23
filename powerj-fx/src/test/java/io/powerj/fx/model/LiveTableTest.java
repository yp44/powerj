package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class LiveTableTest {

    private final ObjectReader reader = new ObjectReader(Reflect::read);

    private Reading reading(Object value) {
        return reader.read(value);
    }

    private static List<Object> values(GridTable table) {
        return table.rows().stream().map(r -> r.get("value")).toList();
    }

    @Test
    void theRowsArriveInTheOrderTheyWereOffered() throws Exception {
        var live = new LiveTable(10);
        for (String value : List.of("a", "b", "c")) {
            assertThat(live.offer(reading(value))).isTrue();
        }
        assertThat(live.pending()).isEqualTo(3);
        var table = new GridTable();
        assertThat(live.drain(table, 10)).isEqualTo(3);
        assertThat(values(table)).containsExactly("a", "b", "c");
        assertThat(live.pending()).isZero();
        assertThat(live.drain(table, 10)).isZero();
    }

    @Test
    void theTableTakesABatchAtATime() throws Exception {
        var live = new LiveTable(10);
        for (int i = 0; i < 5; i++) {
            live.offer(reading(i));
        }
        var table = new GridTable();
        assertThat(live.drain(table, 2)).isEqualTo(2);
        assertThat(table.total()).isEqualTo(2);
        assertThat(live.pending()).isEqualTo(3);
        assertThat(live.drain(table, 2)).isEqualTo(2);
        assertThat(live.drain(table, 2)).isEqualTo(1);
        assertThat(values(table)).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void aPipelineFasterThanTheWindowWaits() throws Exception {
        var live = new LiveTable(2);
        live.offer(reading("a"));
        live.offer(reading("b"));
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Boolean> third = CompletableFuture.supplyAsync(() -> {
            try {
                started.countDown();
                return live.offer(reading("c"));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(150);
        assertThat(third).isNotDone();               // the queue is full: it waits
        var table = new GridTable();
        live.drain(table, 1);                         // room for one
        assertThat(third.get(5, TimeUnit.SECONDS)).isTrue();
        live.drain(table, 10);
        assertThat(values(table)).containsExactly("a", "b", "c");
    }

    @Test
    void closingTheWindowFreesAndStopsThePipeline() throws Exception {
        var live = new LiveTable(1);
        live.offer(reading("a"));
        CompletableFuture<Boolean> blocked = CompletableFuture.supplyAsync(() -> {
            try {
                return live.offer(reading("b"));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(150);
        assertThat(blocked).isNotDone();
        live.close();
        assertThat(blocked.get(5, TimeUnit.SECONDS)).isFalse();
        assertThat(live.isClosed()).isTrue();
        assertThat(live.offer(reading("c"))).isFalse();
        assertThat(live.pending()).isZero();
    }

    @Test
    void aCancelledPipelineIsInterruptedWhileItWaits() throws Exception {
        var live = new LiveTable(1);
        live.offer(reading("a"));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                live.offer(reading("b"));
            } catch (InterruptedException e) {
                failure.set(e);
            }
        });
        producer.start();
        Thread.sleep(150);
        producer.interrupt();
        producer.join(5_000);
        assertThat(failure.get()).isInstanceOf(InterruptedException.class);
    }

    @Test
    void theStateFollowsTheInput() throws Exception {
        var live = new LiveTable(10);
        assertThat(live.state()).isEqualTo(LiveTable.State.LOADING);
        live.offer(reading("a"));
        assertThat(live.state()).isEqualTo(LiveTable.State.LOADING); // this thread is still alive
        live.finish(false);
        assertThat(live.state()).isEqualTo(LiveTable.State.COMPLETE);
        var cut = new LiveTable(10);
        cut.finish(true);
        assertThat(cut.state()).isEqualTo(LiveTable.State.TRUNCATED);
    }

    @Test
    void aPipelineThatDiedWithoutFinishingIsInterrupted() throws Exception {
        var live = new LiveTable(10);
        Thread producer = Thread.ofVirtual().start(() -> {
            try {
                live.offer(reading("a"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        producer.join(5_000);
        assertThat(live.state()).isEqualTo(LiveTable.State.INTERRUPTED);
        assertThat(live.pending()).isEqualTo(1); // what arrived is still given to the window
    }

    @Test
    void aPipelineThatFinishedIsNeverInterrupted() throws Exception {
        var live = new LiveTable(10);
        Thread producer = new Thread(() -> {
            try {
                live.offer(reading("a"));
                live.finish(false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        producer.start();
        producer.join(5_000);
        assertThat(live.state()).isEqualTo(LiveTable.State.COMPLETE);
    }

    @Test
    void whilePausedNothingGoesToTheTable() throws Exception {
        var live = new LiveTable(10);
        assertThat(live.isPaused()).isFalse();
        live.offer(reading("a"));
        live.setPaused(true);
        assertThat(live.isPaused()).isTrue();
        var table = new GridTable();
        assertThat(live.drain(table, 10)).isZero();
        assertThat(table.total()).isZero();
        assertThat(live.pending()).isEqualTo(1);
        live.offer(reading("b"));                          // the pipeline goes on until the queue is full
        assertThat(live.state()).isEqualTo(LiveTable.State.LOADING);
        live.setPaused(false);
        assertThat(live.drain(table, 10)).isEqualTo(2);
        assertThat(values(table)).containsExactly("a", "b");
    }

    @Test
    void aPausedWindowMakesThePipelineWaitAndResumingFreesIt() throws Exception {
        var live = new LiveTable(2);
        live.setPaused(true);
        live.offer(reading("a"));
        live.offer(reading("b"));
        CompletableFuture<Boolean> third = CompletableFuture.supplyAsync(() -> {
            try {
                return live.offer(reading("c"));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        var table = new GridTable();
        for (int i = 0; i < 3; i++) {
            Thread.sleep(60);
            assertThat(live.drain(table, 10)).isZero();      // the window takes nothing
            assertThat(third).isNotDone();                    // so the pipeline waits
        }
        live.setPaused(false);
        live.drain(table, 1);
        assertThat(third.get(5, TimeUnit.SECONDS)).isTrue();
        live.drain(table, 10);
        assertThat(values(table)).containsExactly("a", "b", "c");
    }

    @Test
    void closingAPausedWindowStillFreesThePipeline() throws Exception {
        var live = new LiveTable(1);
        live.setPaused(true);
        live.offer(reading("a"));
        CompletableFuture<Boolean> blocked = CompletableFuture.supplyAsync(() -> {
            try {
                return live.offer(reading("b"));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(150);
        assertThat(blocked).isNotDone();
        live.close();
        assertThat(blocked.get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void theCapacityIsMandatory() {
        assertThatThrownBy(() -> new LiveTable(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
