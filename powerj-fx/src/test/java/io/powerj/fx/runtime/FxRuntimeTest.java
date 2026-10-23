package io.powerj.fx.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class FxRuntimeTest {

    /** Toolkit that runs everything on the calling thread, or fails on demand. */
    private static final class FakePlatform implements FxPlatform {
        final AtomicInteger startups = new AtomicInteger();
        final AtomicInteger runs = new AtomicInteger();
        RuntimeException startupFailure;
        Error startupError;
        boolean swallowRuns;

        @Override
        public void startup() {
            startups.incrementAndGet();
            if (startupFailure != null) {
                throw startupFailure;
            }
            if (startupError != null) {
                throw startupError;
            }
        }

        @Override
        public void runLater(Runnable action) {
            runs.incrementAndGet();
            if (!swallowRuns) {
                action.run();
            }
        }
    }

    @Test
    void startsTheToolkitOnlyOnce() {
        FakePlatform platform = new FakePlatform();
        FxRuntime runtime = new FxRuntime(platform);
        runtime.runAndWait(() -> { });
        runtime.runAndWait(() -> { });
        runtime.ensureStarted();
        assertThat(platform.startups).hasValue(1);
        assertThat(platform.runs).hasValue(2);
    }

    @Test
    void runsTheActionOnTheToolkit() {
        boolean[] ran = new boolean[1];
        new FxRuntime(new FakePlatform()).runAndWait(() -> ran[0] = true);
        assertThat(ran[0]).isTrue();
    }

    @Test
    void aMissingDisplayIsReportedWithTheReason() {
        FakePlatform platform = new FakePlatform();
        platform.startupFailure = new UnsupportedOperationException("Unable to open DISPLAY");
        assertThatThrownBy(() -> new FxRuntime(platform).ensureStarted())
                .isInstanceOf(FxUnavailableException.class)
                .hasMessage("the graphical toolkit cannot start (no graphical display?): Unable to open DISPLAY")
                .hasCause(platform.startupFailure);
    }

    @Test
    void aMissingNativeLibraryIsReportedToo() {
        FakePlatform platform = new FakePlatform();
        platform.startupError = new UnsatisfiedLinkError("libglass.so");
        assertThatThrownBy(() -> new FxRuntime(platform).ensureStarted())
                .isInstanceOf(FxUnavailableException.class)
                .hasMessageContaining("libglass.so");
    }

    @Test
    void anExceptionWithoutMessageIsNamed() {
        FakePlatform platform = new FakePlatform();
        platform.startupFailure = new IllegalStateException();
        assertThatThrownBy(() -> new FxRuntime(platform).ensureStarted())
                .hasMessageContaining("IllegalStateException");
    }

    @Test
    void aBlankMessageIsReplacedByTheNameOfTheException() {
        FakePlatform platform = new FakePlatform();
        platform.startupFailure = new IllegalStateException("  ");
        assertThatThrownBy(() -> new FxRuntime(platform).ensureStarted())
                .hasMessageContaining("IllegalStateException");
    }

    @Test
    void aCheckedExceptionOfTheActionIsWrapped() {
        FxRuntime runtime = new FxRuntime(new FakePlatform());
        java.io.IOException checked = new java.io.IOException("disk");
        assertThatThrownBy(() -> runtime.runAndWait(() -> FxRuntimeTest.<RuntimeException>sneakyThrow(checked)))
                .isInstanceOf(IllegalStateException.class)
                .hasCause(checked);
    }

    /** Throws a checked exception from a {@link Runnable}, as reflection or a proxy could. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    @Test
    void aFailedStartIsRememberedAndNotTriedAgain() {
        FakePlatform platform = new FakePlatform();
        platform.startupFailure = new UnsupportedOperationException("no display");
        FxRuntime runtime = new FxRuntime(platform);
        assertThatThrownBy(runtime::ensureStarted).isInstanceOf(FxUnavailableException.class);
        platform.startupFailure = null;
        assertThatThrownBy(() -> runtime.runAndWait(() -> { }))
                .isInstanceOf(FxUnavailableException.class)
                .hasMessage("the graphical toolkit failed to start earlier in this session and cannot be restarted: "
                        + "restart PowerJ (the graphical toolkit cannot start (no graphical display?): no display)");
        assertThat(platform.startups).hasValue(1);
    }

    @Test
    void anExceptionOfTheActionIsRethrownToTheCaller() {
        FxRuntime runtime = new FxRuntime(new FakePlatform());
        assertThatThrownBy(() -> runtime.runAndWait(() -> {
            throw new IllegalArgumentException("boom");
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("boom");
    }

    @Test
    void anErrorOfTheActionIsRethrownToTheCaller() {
        FxRuntime runtime = new FxRuntime(new FakePlatform());
        assertThatThrownBy(() -> runtime.runAndWait(() -> {
            throw new AssertionError("boom");
        })).isInstanceOf(AssertionError.class);
    }

    @Test
    void aToolkitThatDoesNotAnswerTimesOut() {
        FakePlatform platform = new FakePlatform();
        platform.swallowRuns = true;
        FxRuntime runtime = new FxRuntime(platform, Duration.ofMillis(50));
        assertThatThrownBy(() -> runtime.runAndWait(() -> { }))
                .isInstanceOf(FxUnavailableException.class)
                .hasMessage("the graphical toolkit did not answer in 0 s");
    }

    @Test
    void interruptionIsACancellationAndKeepsTheInterruptFlag() {
        FakePlatform platform = new FakePlatform();
        platform.swallowRuns = true;
        FxRuntime runtime = new FxRuntime(platform);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> runtime.runAndWait(() -> { })).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
