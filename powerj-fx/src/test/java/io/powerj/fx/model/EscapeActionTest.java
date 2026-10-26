package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EscapeActionTest {

    @Test
    void anOpenListKeepsEscForItselfWhateverElse() {
        assertThat(EscapeAction.of(true, false)).isEqualTo(EscapeAction.LEAVE_TO_LIST);
        assertThat(EscapeAction.of(true, true)).isEqualTo(EscapeAction.LEAVE_TO_LIST);
    }

    @Test
    void aFieldWithTextIsEmptiedFirst() {
        assertThat(EscapeAction.of(false, true)).isEqualTo(EscapeAction.CLEAR_FIELD);
    }

    @Test
    void otherwiseTheWindowCloses() {
        assertThat(EscapeAction.of(false, false)).isEqualTo(EscapeAction.CLOSE_WINDOW);
    }
}
