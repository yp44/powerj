package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GridStatusTest {

    private static GridStatus of(int shown, int total, LiveTable.State state, boolean paused, int pending) {
        return GridStatus.of(shown, total, state, paused, pending, -1, 0, null);
    }

    @Test
    void aCompleteTableSaysHowManyRows() {
        GridStatus status = of(50, 50, LiveTable.State.COMPLETE, false, 0);

        assertThat(status.text()).isEqualTo("50 rows");
        assertThat(of(1, 1, LiveTable.State.COMPLETE, false, 0).text()).isEqualTo("1 row");
        assertThat(of(0, 0, LiveTable.State.COMPLETE, false, 0).text()).isEqualTo("0 rows");
    }

    @Test
    void aFilterSaysHowManyRowsPassOfHowMany() {
        assertThat(of(2, 50, LiveTable.State.COMPLETE, false, 0).text()).isEqualTo("2 / 50 rows");
    }

    @Test
    void theStateOfTheInputIsNamed() {
        assertThat(of(3, 3, LiveTable.State.LOADING, false, 0).text()).isEqualTo("3 rows  ·  loading…");
        assertThat(of(3, 3, LiveTable.State.TRUNCATED, false, 0).text())
                .isEqualTo("3 rows  ·  truncated at 3 rows (--max-rows)");
        assertThat(of(3, 3, LiveTable.State.INTERRUPTED, false, 0).text()).isEqualTo("3 rows  ·  input interrupted");
    }

    @Test
    void pausingHidesTheStateWhileRowsWaitAndOtherwiseThereIsNothingToPause() {
        GridStatus waiting = of(3, 3, LiveTable.State.LOADING, true, 5);
        assertThat(waiting.text()).isEqualTo("3 rows  ·  paused");
        assertThat(waiting.pauseDisabled()).isFalse();
        assertThat(waiting.pauseLabel()).isEqualTo("Resume");

        // the input is over but rows wait in the queue: the table is not complete
        assertThat(of(3, 3, LiveTable.State.COMPLETE, true, 2).text()).isEqualTo("3 rows  ·  paused");

        GridStatus over = of(3, 3, LiveTable.State.COMPLETE, true, 0);
        assertThat(over.text()).isEqualTo("3 rows");
        assertThat(over.pauseDisabled()).isTrue();
    }

    @Test
    void thePauseButtonIsUsableWhileTheInputIsLoadingOrRowsWait() {
        assertThat(of(3, 3, LiveTable.State.LOADING, false, 0).pauseDisabled()).isFalse();
        assertThat(of(3, 3, LiveTable.State.LOADING, false, 0).pauseLabel()).isEqualTo("Pause");
        assertThat(of(3, 3, LiveTable.State.COMPLETE, false, 4).pauseDisabled()).isFalse();
        assertThat(of(3, 3, LiveTable.State.TRUNCATED, false, 0).pauseDisabled()).isTrue();
        assertThat(of(3, 3, LiveTable.State.INTERRUPTED, false, 0).pauseDisabled()).isTrue();
    }

    @Test
    void anEmptyTableSaysItIsLoadingUntilTheInputIsOver() {
        assertThat(of(0, 0, LiveTable.State.LOADING, false, 0).placeholder()).isEqualTo("Loading…");
        assertThat(of(0, 0, LiveTable.State.COMPLETE, false, 0).placeholder()).isEqualTo("No result");
        assertThat(of(0, 5, LiveTable.State.TRUNCATED, false, 0).placeholder()).isEqualTo("No result");
    }

    @Test
    void theTicksAreCountedOnlyWhenThereAreTicks() {
        assertThat(GridStatus.of(5, 5, LiveTable.State.COMPLETE, false, 0, 0, 0, null).text()).isEqualTo("5 rows  ·  0 selected");
        assertThat(GridStatus.of(5, 5, LiveTable.State.LOADING, false, 0, 3, 0, null).text())
                .isEqualTo("5 rows  ·  3 selected  ·  loading…");
    }

    @Test
    void theCellsARegexGaveUpOnAreReportedBeforeTheState() {
        assertThat(GridStatus.of(0, 5, LiveTable.State.COMPLETE, false, 0, -1, 1, null).text())
                .isEqualTo("0 / 5 rows  ·  1 cell not searched: the regular expression took too long");
        assertThat(GridStatus.of(0, 5, LiveTable.State.LOADING, false, 0, 2, 5, null).text())
                .isEqualTo("0 / 5 rows  ·  2 selected  ·  5 cells not searched: the regular expression took too long"
                        + "  ·  loading…");
    }

    @Test
    void anInvalidFilterIsSaidInWordsRightAfterTheRowsNotOnlyByAColour() {
        GridStatus status = GridStatus.of(3, 50, LiveTable.State.COMPLETE, false, 0, 2, 0, "filter of size: not a number: abc");

        assertThat(status.text()).isEqualTo("3 / 50 rows  ·  filter of size: not a number: abc  ·  2 selected");
        assertThat(GridStatus.of(3, 3, LiveTable.State.COMPLETE, false, 0, -1, 0, "").text()).isEqualTo("3 rows");
    }
}
