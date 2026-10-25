package io.powerj.core.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** The guard that stops a preview before anything that changes something runs (review C-30). */
class PreviewGuardTest {

    @Test
    void outsideAPreviewNothingIsDeniedAndNoGuardIsBound() {
        assertThat(PreviewGuard.active()).isFalse();
        assertThat(PreviewGuard.current()).isNull();

        PreviewGuard.deny("would change something"); // no effect
    }

    @Test
    void denyStopsTheEvaluationAndKeepsTheFirstReason() throws Exception {
        var guard = new PreviewGuard();

        guard.call(() -> {
            assertThat(PreviewGuard.active()).isTrue();
            assertThat(PreviewGuard.current()).isSameAs(guard);
            assertThatThrownBy(() -> PreviewGuard.deny("first")).isInstanceOf(PreviewGuard.Denied.class)
                    .hasMessage("first");
            assertThatThrownBy(() -> PreviewGuard.deny("second")).hasMessage("second");
            return null;
        });

        assertThat(guard.reason()).isEqualTo("first");
        assertThat(PreviewGuard.active()).isFalse(); // the binding ends with the call
    }

    @Test
    void denialIsACancellationSoThatNoCmdletOrBlockSwallowsIt() {
        assertThat(new PreviewGuard.Denied("why")).isInstanceOf(CancellationException.class);
    }

    @Test
    void aJavaCallOutsideThePolicyStopsThePreviewAndAnAllowedOneDoesNot() throws Exception {
        var guard = new PreviewGuard();
        var length = String.class.getMethod("length");
        var delete = Files.class.getMethod("delete", Path.class);

        guard.call(() -> {
            PreviewGuard.checkCall(length);
            assertThatThrownBy(() -> PreviewGuard.checkCall(delete)).isInstanceOf(PreviewGuard.Denied.class);
            return null;
        });

        assertThat(guard.reason()).contains("delete");
    }

    @Test
    void checkCallDoesNothingOutsideAPreview() throws Exception {
        PreviewGuard.checkCall(Files.class.getMethod("delete", Path.class));
    }

    @Test
    void theGuardFollowsAStageToAnotherThreadOnlyThroughPropagate() throws Exception {
        var guard = new PreviewGuard();
        var seenByPlain = new AtomicReference<Boolean>();
        var seenByPropagated = new AtomicReference<Boolean>();

        guard.call(() -> {
            Thread plain = Thread.ofVirtual().start(() -> seenByPlain.set(PreviewGuard.active()));
            Thread propagated = Thread.ofVirtual()
                    .start(PreviewGuard.propagate(() -> seenByPropagated.set(PreviewGuard.current() == guard)));
            plain.join();
            propagated.join();
            return null;
        });

        assertThat(seenByPlain.get()).isFalse();
        assertThat(seenByPropagated.get()).isTrue();
    }

    @Test
    void propagateOutsideAPreviewReturnsTheTaskItself() {
        Runnable task = () -> { };

        assertThat(PreviewGuard.propagate(task)).isSameAs(task);
    }

    @Test
    void errorsAreCountedAndNeverShown() {
        var guard = new PreviewGuard();

        guard.error("a");
        guard.error("b");

        assertThat(guard.errors()).isEqualTo(2);
        assertThat(guard.reason()).isNull();
    }

    // --- the fixed policy ---

    private static boolean allowed(Class<?> type, String name, Class<?>... parameters) throws Exception {
        return PreviewPolicy.allows(type.getMethod(name, parameters));
    }

    @Test
    void pureTypesAndReadingMethodsAreAllowed() throws Exception {
        assertThat(allowed(String.class, "toUpperCase")).isTrue();
        assertThat(allowed(Math.class, "max", int.class, int.class)).isTrue();
        assertThat(allowed(List.class, "size")).isTrue();
        assertThat(allowed(Map.class, "get", Object.class)).isTrue();
        assertThat(allowed(Files.class, "exists", Path.class, java.nio.file.LinkOption[].class)).isTrue();
        assertThat(allowed(Path.class, "resolve", String.class)).isTrue();
        assertThat(allowed(Object.class, "toString")).isTrue();
    }

    @Test
    void mutatorsAndChangingFileOperationsAreNot() throws Exception {
        assertThat(allowed(List.class, "add", Object.class)).isFalse();
        assertThat(allowed(Map.class, "put", Object.class, Object.class)).isFalse();
        assertThat(allowed(Files.class, "delete", Path.class)).isFalse();
        assertThat(allowed(Files.class, "writeString", Path.class, CharSequence.class,
                java.nio.file.OpenOption[].class)).isFalse();
        assertThat(allowed(System.class, "getProperty", String.class)).isFalse();
        assertThat(allowed(Class.class, "forName", String.class)).isFalse();
    }

    @Test
    void parallelStreamsAreRefusedBecauseTheirBlocksRunOutsideTheGuard() throws Exception {
        assertThat(allowed(java.util.Collection.class, "parallelStream")).isFalse();
        assertThat(allowed(java.util.stream.Stream.class, "parallel")).isFalse();
        assertThat(allowed(java.util.stream.Stream.class, "sequential")).isTrue();
    }

    @Test
    void onlyAFewConstructorsAndNoThirdPartyCodeAreAllowed() throws Exception {
        assertThat(PreviewPolicy.allows(java.util.ArrayList.class.getConstructor())).isTrue();
        assertThat(PreviewPolicy.allows(StringBuilder.class.getConstructor())).isFalse();
        assertThat(PreviewPolicy.allows(java.io.FileWriter.class.getConstructor(String.class))).isFalse();
        assertThat(PreviewPolicy.allows(PreviewGuardTest.class.getDeclaredMethod("allowed", Class.class, String.class,
                Class[].class))).isFalse(); // not a java.base method
    }
}
