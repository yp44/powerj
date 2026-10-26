package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

/** Background jobs: numbering, end of a job, reporting (review C-30). The processes are doubles: no native command. */
class JobsTest {

    /** A process that ends when the test says so. */
    private static final class FakeProcess extends Process {

        private final long pid;
        private final CompletableFuture<Process> exit = new CompletableFuture<>();
        private int code;

        FakeProcess(long pid) {
            this.pid = pid;
        }

        void end(int code) {
            this.code = code;
            exit.complete(this);
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public CompletableFuture<Process> onExit() {
            return exit;
        }

        @Override
        public int exitValue() {
            return code;
        }

        @Override
        public java.io.OutputStream getOutputStream() {
            return java.io.OutputStream.nullOutputStream();
        }

        @Override
        public java.io.InputStream getInputStream() {
            return java.io.InputStream.nullInputStream();
        }

        @Override
        public java.io.InputStream getErrorStream() {
            return java.io.InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return exit.join().exitValue();
        }

        @Override
        public void destroy() {
            end(143);
        }
    }

    @Test
    void jobsAreNumberedFromOneAndTrackTheLastProcessOfThePipeline() {
        Jobs jobs = new Jobs();
        FakeProcess first = new FakeProcess(100);
        FakeProcess last = new FakeProcess(101);

        Job job = jobs.add("producer | consumer", List.of(first, last));

        assertThat(job.id()).isEqualTo(1);
        assertThat(job.pid()).isEqualTo(101);
        assertThat(job.command()).isEqualTo("producer | consumer");
        assertThat(job.running()).isTrue();
        assertThat(job.exitCode()).isNull();
        assertThat(job.start()).isBeforeOrEqualTo(Instant.now());
        assertThat(jobs.anyRunning()).isTrue();
    }

    @Test
    void theSmallestFreeNumberIsReused() {
        Jobs jobs = new Jobs();
        FakeProcess a = new FakeProcess(1);
        FakeProcess b = new FakeProcess(2);
        jobs.add("a", List.of(a));
        jobs.add("b", List.of(b));
        a.end(0);
        jobs.drainFinished();

        Job next = jobs.add("c", List.of(new FakeProcess(3)));

        assertThat(next.id()).isEqualTo(1);
    }

    @Test
    void anEndedJobIsReportedOnceAndThenForgotten() {
        Jobs jobs = new Jobs();
        FakeProcess process = new FakeProcess(7);
        jobs.add("sleep 30", List.of(process));

        assertThat(jobs.drainFinished()).isEmpty();
        process.end(3);

        assertThat(jobs.anyRunning()).isFalse();
        assertThat(jobs.drainFinished()).singleElement().asString().startsWith("[1] done sleep 30 (exit 3");
        assertThat(jobs.drainFinished()).isEmpty();
        assertThat(jobs.list()).isEmpty();
    }

    @Test
    void listShowsEveryJobThenForgetsTheEndedOnes() {
        Jobs jobs = new Jobs();
        FakeProcess running = new FakeProcess(1);
        FakeProcess ended = new FakeProcess(2);
        jobs.add("running", List.of(running));
        jobs.add("ended", List.of(ended));
        ended.end(0);

        List<Job> listed = jobs.list();

        assertThat(listed).extracting(Job::command).containsExactly("running", "ended");
        assertThat(listed).extracting(Job::running).containsExactly(true, false);
        assertThat(jobs.list()).extracting(Job::command).containsExactly("running"); // the ended one was reported
    }

    @Test
    void aJobsExitCodeIsKept() {
        Jobs jobs = new Jobs();
        FakeProcess process = new FakeProcess(5);
        jobs.add("x", List.of(process));
        process.end(42);

        assertThat(jobs.list()).singleElement().satisfies(job -> {
            assertThat(job.running()).isFalse();
            assertThat(job.exitCode()).isEqualTo(42);
        });
    }
}
