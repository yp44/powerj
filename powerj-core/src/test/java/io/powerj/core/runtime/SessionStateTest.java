package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.error.PjException;
import io.powerj.core.nativeexec.Platform;

/** Where the user is: directories, home, environment. */
class SessionStateTest {

    @TempDir
    Path tmp;

    private SessionState state(Map<String, String> environment) {
        return new SessionState(tmp.resolve("startup-home"), tmp, new HashMap<>(environment));
    }

    @Test
    void homeIsHomeThenUserProfileThenTheStartupHome() {
        Path a = tmp.resolve("a").toAbsolutePath();
        Path b = tmp.resolve("b").toAbsolutePath();

        assertThat(state(Map.of("HOME", a.toString(), "USERPROFILE", b.toString())).home()).isEqualTo(a);
        assertThat(state(Map.of("USERPROFILE", b.toString())).home()).isEqualTo(b);
        assertThat(state(Map.of()).home()).isEqualTo(tmp.resolve("startup-home").toAbsolutePath());
    }

    @Test
    void aBlankOrInvalidHomeFallsThroughToTheNextSource() {
        Path b = tmp.resolve("b").toAbsolutePath();

        assertThat(state(Map.of("HOME", "  ", "USERPROFILE", b.toString())).home()).isEqualTo(b);
        assumeFalse(Platform.isWindows(), "a NUL in a path is accepted differently there");
        assertThat(state(Map.of("HOME", "bad\0path", "USERPROFILE", b.toString())).home()).isEqualTo(b);
    }

    @Test
    void homeFollowsTheEnvironmentAtOnceButTheStartupHomeDoesNot() {
        var state = state(Map.of());
        Path elsewhere = tmp.resolve("elsewhere").toAbsolutePath();

        state.environment().put("HOME", elsewhere.toString());

        assertThat(state.home()).isEqualTo(elsewhere);
        assertThat(state.startupHome()).isEqualTo(tmp.resolve("startup-home").toAbsolutePath());
    }

    @Test
    void theTildeIsExpandedOnlyWhenItStartsTheWordAlone() {
        var state = state(Map.of("HOME", tmp.toString()));

        assertThat(state.expandTilde("~")).isEqualTo(tmp.toString());
        assertThat(state.expandTilde("~/docs")).isEqualTo(tmp.resolve("docs").toString());
        assertThat(state.expandTilde("~\\docs")).isEqualTo(tmp.resolve("docs").toString());
        assertThat(state.expandTilde("~someone")).isEqualTo("~someone");
        assertThat(state.expandTilde("a~b")).isEqualTo("a~b");
    }

    @Test
    void changingDirectoryRemembersTheOneWeLeft() throws IOException {
        Path sub = Files.createDirectory(tmp.resolve("sub"));
        var state = state(Map.of());
        assertThat(state.previousDirectory()).isEmpty();

        state.changeDirectory(Path.of("sub"));

        assertThat(state.currentDirectory()).isEqualTo(sub.toAbsolutePath().normalize());
        assertThat(state.previousDirectory()).contains(tmp.toAbsolutePath().normalize());
    }

    @Test
    void aMissingDirectoryOrAFileIsRefusedAndNothingChanges() throws IOException {
        Files.writeString(tmp.resolve("file.txt"), "x");
        var state = state(Map.of());

        assertThatThrownBy(() -> state.changeDirectory(Path.of("missing"))).isInstanceOf(PjException.class)
                .hasMessageContaining("missing");
        assertThatThrownBy(() -> state.changeDirectory(Path.of("file.txt"))).isInstanceOf(PjException.class)
                .hasMessageContaining("file.txt");
        assertThat(state.currentDirectory()).isEqualTo(tmp.toAbsolutePath().normalize());
        assertThat(state.previousDirectory()).isEmpty();
    }

    @Test
    void theConfigurationDirectoryIsAbsoluteAndMayBeCleared() {
        var state = state(Map.of());
        assertThat(state.configDirectory()).isEmpty();

        state.configDirectory(Path.of("conf"));
        assertThat(state.configDirectory()).contains(Path.of("conf").toAbsolutePath().normalize());

        state.configDirectory(null);
        assertThat(state.configDirectory()).isEmpty();
    }

    @Test
    void theInitialEnvironmentIsCopied() {
        var initial = new HashMap<>(Map.of("A", "1"));
        var state = new SessionState(tmp, tmp, initial);

        initial.put("B", "2");
        state.environment().put("C", "3");

        assertThat(state.environment()).containsOnlyKeys("A", "C");
        assertThat(initial).containsOnlyKeys("A", "B");
    }

    @Test
    void environmentNamesFollowTheCaseRuleOfThePlatform() {
        var state = state(Map.of("Path", "x"));

        if (Platform.isWindows()) {
            assertThat(state.environment().get("PATH")).isEqualTo("x");
            assertThat(state.environmentSnapshot().get("pAtH")).isEqualTo("x");
        } else {
            assertThat(state.environment().get("PATH")).isNull();
            assertThat(state.environmentSnapshot().get("PATH")).isNull();
        }
    }

    @Test
    void theSnapshotIsReadOnlyAndDoesNotFollowLaterChanges() {
        var state = state(Map.of("A", "1"));
        var snapshot = state.environmentSnapshot();

        state.environment().put("A", "2");

        assertThat(snapshot.get("A")).isEqualTo("1");
        assertThatThrownBy(() -> snapshot.put("B", "3")).isInstanceOf(UnsupportedOperationException.class);
    }
}
