package io.powerj.core.nativeexec;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Reading the descriptors of a child that has just been started (#128). Right after {@code exec}, the dynamic loader holds
 * the C library on descriptor 3 for an instant and then closes it: a list read at that moment is {@code 0 1 2 3} although
 * nothing leaked (2 of 300 reads, measured on Linux). A leak never goes away; a loader's descriptor does.
 */
class ChildFdsTest {

    private static final List<String> STANDARD = List.of("0", "1", "2");
    private static final List<String> LOADER = List.of("0", "1", "2", "3");

    @Test
    void aDescriptorOfTheLoaderThatClosesIsWaitedFor() throws Exception {
        Queue<List<String>> reads = new ArrayDeque<>(List.of(LOADER, LOADER, STANDARD));

        List<String> read = ChildFds.until(reads::remove, STANDARD::equals, Duration.ofSeconds(5));

        assertThat(read).isEqualTo(STANDARD);
        assertThat(reads).isEmpty();
    }

    @Test
    void aLeakThatStaysIsReturnedWhenTheTimeIsUp() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        List<String> read = ChildFds.until(() -> {
            calls.incrementAndGet();
            return LOADER;
        }, STANDARD::equals, Duration.ofMillis(100));

        assertThat(read).as("the last list read, for the message of the assertion").isEqualTo(LOADER);
        assertThat(calls.get()).as("it kept reading until the time was up").isGreaterThan(2);
    }

    @Test
    void aListThatIsAlreadyRightIsReadOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        List<String> read = ChildFds.until(() -> {
            calls.incrementAndGet();
            return STANDARD;
        }, STANDARD::equals, Duration.ofSeconds(5));

        assertThat(read).isEqualTo(STANDARD);
        assertThat(calls.get()).isEqualTo(1);
    }
}
