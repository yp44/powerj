package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** One mode at a time: entering one leaves the other, and no mode knows the others. */
class ModeControllerTest {

    private final List<String> events = new ArrayList<>();
    private final ModeController modes = new ModeController();

    {
        modes.register(ShellMode.PREVIEW, () -> events.add("enter preview"), () -> events.add("leave preview"));
        modes.register(ShellMode.PERF, () -> events.add("enter perf"), () -> events.add("leave perf"));
    }

    @Test
    void theShellStartsInTheNormalMode() {
        assertThat(modes.current()).isEqualTo(ShellMode.NORMAL);
        assertThat(modes.is(ShellMode.NORMAL)).isTrue();
        assertThat(events).isEmpty();
    }

    @Test
    void toggleEntersAModeAndAnotherToggleLeavesIt() {
        modes.toggle(ShellMode.PREVIEW);
        assertThat(modes.current()).isEqualTo(ShellMode.PREVIEW);

        modes.toggle(ShellMode.PREVIEW);
        assertThat(modes.current()).isEqualTo(ShellMode.NORMAL);
        assertThat(events).containsExactly("enter preview", "leave preview");
    }

    @Test
    void enteringAModeLeavesTheOneTheShellWasIn() {
        modes.toggle(ShellMode.PREVIEW);
        modes.toggle(ShellMode.PERF);

        assertThat(modes.current()).isEqualTo(ShellMode.PERF);
        assertThat(modes.is(ShellMode.PREVIEW)).isFalse();
        assertThat(events).containsExactly("enter preview", "leave preview", "enter perf"); // left before entered
    }

    @Test
    void theNormalModeHasNoHookAndIsEnteredByLeavingTheOther() {
        modes.toggle(ShellMode.PERF);
        modes.switchTo(ShellMode.NORMAL);

        assertThat(modes.current()).isEqualTo(ShellMode.NORMAL);
        assertThat(events).containsExactly("enter perf", "leave perf");
    }

    @Test
    void switchingToTheModeTheShellIsInDoesNothing() {
        modes.switchTo(ShellMode.PERF);
        events.clear();

        modes.switchTo(ShellMode.PERF);

        assertThat(events).isEmpty();
    }

    @Test
    void aModeWithoutHooksCanBeEntered() {
        var bare = new ModeController();

        bare.toggle(ShellMode.PREVIEW);

        assertThat(bare.current()).isEqualTo(ShellMode.PREVIEW);
    }

    @Test
    void theModeIsAlreadyTheNewOneWhenItsHookRuns() {
        var seen = new ArrayList<ShellMode>();
        modes.register(ShellMode.PERF, () -> seen.add(modes.current()), () -> seen.add(modes.current()));

        modes.toggle(ShellMode.PERF);
        modes.toggle(ShellMode.PERF);

        assertThat(seen).containsExactly(ShellMode.PERF, ShellMode.NORMAL);
    }
}
