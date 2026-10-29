package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

import io.powerj.core.runtime.PreviewSettings;

/** The control of the preview that timed out (FR-66): what each Alt+arrow does, and how the settings are drawn. */
class PreviewControlTest {

    private final PreviewControl control = new PreviewControl();

    @Test
    void theDeadlineIsChosenFirst() {
        assertThat(control.selected()).isEqualTo(PreviewControl.Field.DEADLINE);
    }

    @Test
    void upAndDownChangeTheChosenSettingByOneStep() {
        var settings = PreviewSettings.defaults();

        assertThat(control.press(PreviewControl.Key.UP, settings)).isEqualTo(new PreviewSettings(800, 50));
        assertThat(control.press(PreviewControl.Key.DOWN, settings)).isEqualTo(new PreviewSettings(200, 50));
    }

    @Test
    void rightChoosesTheLimitAndLeftTheDeadline() {
        var settings = PreviewSettings.defaults();

        assertThat(control.press(PreviewControl.Key.RIGHT, settings)).isEqualTo(settings);
        assertThat(control.selected()).isEqualTo(PreviewControl.Field.LIMIT);
        assertThat(control.press(PreviewControl.Key.UP, settings)).isEqualTo(new PreviewSettings(400, 100));
        assertThat(control.press(PreviewControl.Key.DOWN, settings)).isEqualTo(new PreviewSettings(400, 20));

        assertThat(control.press(PreviewControl.Key.LEFT, settings)).isEqualTo(settings);
        assertThat(control.selected()).isEqualTo(PreviewControl.Field.DEADLINE);
        assertThat(control.press(PreviewControl.Key.UP, settings)).isEqualTo(new PreviewSettings(800, 50));
    }

    @Test
    void choosingTwiceTheSameSettingChangesNothing() {
        control.press(PreviewControl.Key.RIGHT, PreviewSettings.defaults());
        control.press(PreviewControl.Key.RIGHT, PreviewSettings.defaults());
        assertThat(control.selected()).isEqualTo(PreviewControl.Field.LIMIT);
        control.press(PreviewControl.Key.LEFT, PreviewSettings.defaults());
        control.press(PreviewControl.Key.LEFT, PreviewSettings.defaults());
        assertThat(control.selected()).isEqualTo(PreviewControl.Field.DEADLINE);
    }

    @Test
    void atTheEndOfTheStepsTheSettingsStayAsTheyAre() {
        var longest = new PreviewSettings(3200, 500);

        assertThat(control.press(PreviewControl.Key.UP, longest)).isEqualTo(longest);
        control.press(PreviewControl.Key.RIGHT, longest);
        assertThat(control.press(PreviewControl.Key.UP, longest)).isEqualTo(longest);
        assertThat(control.press(PreviewControl.Key.DOWN, new PreviewSettings(200, 10)))
                .isEqualTo(new PreviewSettings(200, 10));
    }

    @Test
    void theKeysAreWhatATerminalSendsForAltAndAnArrow() {
        assertThat(PreviewControl.Key.UP.sequence()).isEqualTo("\033[1;3A");
        assertThat(PreviewControl.Key.DOWN.sequence()).isEqualTo("\033[1;3B");
        assertThat(PreviewControl.Key.RIGHT.sequence()).isEqualTo("\033[1;3C");
        assertThat(PreviewControl.Key.LEFT.sequence()).isEqualTo("\033[1;3D");
    }

    @Test
    void theChosenSettingIsBetweenBracketsAndInReverseVideo() {
        var settings = PreviewSettings.defaults();

        AttributedString deadlineChosen = control.fields(settings, false);
        assertThat(deadlineChosen.toString()).isEqualTo("[duration 400 ms]  max/stage 50");
        assertThat(deadlineChosen.styleAt(0)).isEqualTo(AttributedStyle.DEFAULT.inverse());
        assertThat(deadlineChosen.styleAt(deadlineChosen.length() - 1)).isEqualTo(AttributedStyle.DEFAULT);

        control.press(PreviewControl.Key.RIGHT, settings);
        AttributedString limitChosen = control.fields(settings, false);
        assertThat(limitChosen.toString()).isEqualTo("duration 400 ms  [max/stage 50]");
        assertThat(limitChosen.styleAt(0)).isEqualTo(AttributedStyle.DEFAULT);
        assertThat(limitChosen.styleAt(limitChosen.length() - 1)).isEqualTo(AttributedStyle.DEFAULT.inverse());
    }

    @Test
    void theBriefFormKeepsTheValuesOnly() {
        assertThat(control.fields(PreviewSettings.defaults(), true).toString()).isEqualTo("[400 ms]  50/stage");
    }

    @Test
    void aSettingThatCannotGoUpAnyMoreSaysSo() {
        assertThat(control.fields(new PreviewSettings(3200, 500), false).toString())
                .isEqualTo("[duration 3200 ms (max)]  max/stage 500 (max)");
        assertThat(control.fields(new PreviewSettings(1600, 100), false).toString())
                .isEqualTo("[duration 1600 ms]  max/stage 100");
    }

    @Test
    void theHelpNamesTheKeys() {
        assertThat(PreviewControl.help(false)).isEqualTo("Alt+◄► choose, Alt+▲▼ change");
        assertThat(PreviewControl.help(true)).isEqualTo("Alt+◄►▲▼");
    }
}
