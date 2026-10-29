package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The latest request wins: the ones it overtakes are neither evaluated nor published, and an evaluation that is overtaken
 * or too long is interrupted. Real threads, but nothing is waited for by sleeping: the tests wait for what they expect.
 */
@Timeout(30)
class LatestWinsRunnerTest {

    private static final long STACK = 1024 * 1024;

    private final List<String> evaluated = new CopyOnWriteArrayList<>();
    private final BlockingQueue<String> published = new LinkedBlockingQueue<>();
    private Function<String, String> evaluation = request -> request.toUpperCase(java.util.Locale.ROOT);

    private LatestWinsRunner<String, String> runner(Duration debounce, Duration deadline) {
        return runner(debounce, () -> deadline);
    }

    private LatestWinsRunner<String, String> runner(Duration debounce, Supplier<Duration> deadline) {
        return new LatestWinsRunner<>(request -> {
            evaluated.add(request);
            return evaluation.apply(request);
        }, failure -> "failed: " + failure.getMessage(),
                (result, delivery) -> delivery.ifLatest(() -> published.add(result)), debounce, deadline, "test-worker",
                STACK);
    }

    private String next() throws InterruptedException {
        String result = published.poll(20, TimeUnit.SECONDS);
        assertThat(result).as("a published result").isNotNull();
        return result;
    }

    @Test
    void aRequestIsEvaluatedAndPublished() throws Exception {
        var runner = runner(Duration.ofMillis(10), Duration.ofSeconds(10));

        runner.submit("abc");

        assertThat(next()).isEqualTo("ABC");
        assertThat(evaluated).containsExactly("abc");
    }

    @Test
    void aBurstOfRequestsIsEvaluatedOnceForTheLastOne() throws Exception {
        var runner = runner(Duration.ofMillis(300), Duration.ofSeconds(10));

        for (String request : List.of("a", "ab", "abc", "abcd", "abcde")) {
            runner.submit(request);
        }

        assertThat(next()).isEqualTo("ABCDE");
        assertThat(evaluated).containsExactly("abcde");
    }

    @Test
    void aRequestThatArrivesWhileTheWorkerWaitsOutThePauseOvertakesTheFirstOne() throws Exception {
        var runner = runner(Duration.ofSeconds(1), Duration.ofSeconds(30));

        runner.submit("x1");
        Thread.sleep(50); // far less than the pause: the worker is waiting it out
        runner.submit("x2");

        assertThat(next()).isEqualTo("X2");
        assertThat(evaluated).containsExactly("x2"); // "x1" was never evaluated: without the pause it was, at once
    }

    @Test
    void aCancelledRequestIsNeverEvaluated() throws Exception {
        var runner = runner(Duration.ofMillis(300), Duration.ofSeconds(10));

        runner.submit("first");
        runner.cancel();
        runner.submit("second");

        assertThat(next()).isEqualTo("SECOND");
        assertThat(evaluated).containsExactly("second");
        assertThat(published).isEmpty();
    }

    @Test
    void aNewerRequestInterruptsTheEvaluationThatIsRunningAndItsResultIsNotPublished() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        evaluation = request -> {
            if (request.equals("slow")) {
                started.countDown();
                try {
                    Thread.sleep(Duration.ofSeconds(60));
                } catch (InterruptedException _) {
                    interrupted.countDown();
                }
                return "slow, overtaken";
            }
            return request;
        };
        var runner = runner(Duration.ofMillis(10), Duration.ofSeconds(60));

        runner.submit("slow");
        assertThat(started.await(20, TimeUnit.SECONDS)).isTrue();
        runner.submit("fast");

        assertThat(interrupted.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(next()).isEqualTo("fast");
        assertThat(published).isEmpty(); // nothing of "slow"
    }

    @Test
    void cancelInterruptsTheEvaluationThatIsRunning() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        evaluation = request -> {
            started.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(60));
            } catch (InterruptedException _) {
                interrupted.countDown();
            }
            return "overtaken";
        };
        var runner = runner(Duration.ofMillis(10), Duration.ofSeconds(60));

        runner.submit("slow");
        assertThat(started.await(20, TimeUnit.SECONDS)).isTrue();
        runner.cancel();

        assertThat(interrupted.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(published).isEmpty();
    }

    @Test
    void anEvaluationPastTheDeadlineIsInterruptedAndWhatItReturnsIsPublished() throws Exception {
        evaluation = request -> {
            try {
                Thread.sleep(Duration.ofSeconds(60));
            } catch (InterruptedException _) {
                return "partial " + request; // what it had until then
            }
            return "complete";
        };
        var runner = runner(Duration.ofMillis(10), Duration.ofMillis(150));

        runner.submit("q");

        assertThat(next()).isEqualTo("partial q");
    }

    @Test
    void theDeadlineIsAskedForAtTheStartOfEachEvaluation() throws Exception {
        evaluation = request -> {
            try {
                Thread.sleep(Duration.ofMillis(600)); // longer than the first deadline, shorter than the second
            } catch (InterruptedException _) {
                return "cut " + request;
            }
            return "done " + request;
        };
        var deadline = new AtomicReference<>(Duration.ofMillis(100));
        var runner = runner(Duration.ofMillis(10), deadline::get);

        runner.submit("first");
        assertThat(next()).isEqualTo("cut first");

        deadline.set(Duration.ofSeconds(30)); // the user chose a longer one
        runner.submit("second");
        assertThat(next()).isEqualTo("done second");

        deadline.set(Duration.ofMillis(100)); // and a shorter one again
        runner.submit("third");
        assertThat(next()).isEqualTo("cut third");
    }

    @Test
    void theInterruptionDoesNotReachThePublisher() throws Exception {
        var flags = new LinkedBlockingQueue<Boolean>();
        evaluation = request -> {
            try {
                Thread.sleep(Duration.ofSeconds(60));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt(); // an evaluation that keeps the flag, as a cancelled command does
            }
            return request;
        };
        var runner = new LatestWinsRunner<String, String>(request -> evaluation.apply(request), Throwable::getMessage,
                (result, delivery) -> flags.add(Thread.currentThread().isInterrupted()), Duration.ofMillis(10),
                () -> Duration.ofMillis(100), "test-worker", STACK);

        runner.submit("q");

        assertThat(flags.poll(20, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void anEvaluationThatThrowsGivesTheResultOfTheFailure() throws Exception {
        evaluation = request -> {
            throw new IllegalStateException("boom");
        };
        var runner = runner(Duration.ofMillis(10), Duration.ofSeconds(10));

        runner.submit("q");

        assertThat(next()).isEqualTo("failed: boom");
    }

    @Test
    void aPublisherThatThrowsDoesNotKillTheWorker() throws Exception {
        var calls = new LinkedBlockingQueue<String>();
        var runner = new LatestWinsRunner<String, String>(request -> request, Throwable::getMessage,
                (result, delivery) -> {
                    calls.add(result);
                    if (result.equals("first")) {
                        throw new IllegalStateException("terminal closed");
                    }
                }, Duration.ofMillis(10), () -> Duration.ofSeconds(10), "test-worker", STACK);

        runner.submit("first");
        assertThat(calls.poll(20, TimeUnit.SECONDS)).isEqualTo("first");
        runner.submit("second");

        assertThat(calls.poll(20, TimeUnit.SECONDS)).isEqualTo("second");
    }

    @Test
    void aRequestThatArrivesWhileTheResultIsBeingHandedOverMakesItStale() throws Exception {
        var inPublisher = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var delivered = new LinkedBlockingQueue<Boolean>();
        var runner = new LatestWinsRunner<String, String>(request -> request, Throwable::getMessage,
                (result, delivery) -> {
                    if (result.equals("old")) {
                        inPublisher.countDown();
                        try {
                            release.await(20, TimeUnit.SECONDS);
                        } catch (InterruptedException _) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    delivered.add(delivery.ifLatest(() -> { }));
                }, Duration.ofMillis(10), () -> Duration.ofSeconds(10), "test-worker", STACK);

        runner.submit("old");
        assertThat(inPublisher.await(20, TimeUnit.SECONDS)).isTrue();
        runner.submit("new");
        release.countDown();

        assertThat(delivered.poll(20, TimeUnit.SECONDS)).isFalse(); // the result of "old" arrived too late
        assertThat(delivered.poll(20, TimeUnit.SECONDS)).isTrue();  // the one of "new" is the latest
    }

    @Test
    void theWorkerIsADaemonThreadWithTheNameItWasGiven() throws Exception {
        var seen = new LinkedBlockingQueue<String>();
        evaluation = request -> {
            seen.add(Thread.currentThread().getName() + " " + Thread.currentThread().isDaemon());
            return request;
        };
        var runner = runner(Duration.ofMillis(10), Duration.ofSeconds(10));

        runner.submit("q");

        assertThat(seen.poll(20, TimeUnit.SECONDS)).isEqualTo("test-worker true");
    }
}
