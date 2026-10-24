package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WindowStateTest {

    @Test
    void aStateThatCanBeAWindowIsUsable() {
        assertThat(new WindowState(10, 20, 800, 600).isUsable()).isTrue();
        assertThat(new WindowState(-30, -10, 200, 200).isUsable()).isTrue();
    }

    @Test
    void numbersThatCannotBeAWindowAreNot() {
        assertThat(new WindowState(0, 0, 199, 600).isUsable()).isFalse();
        assertThat(new WindowState(0, 0, 800, 10).isUsable()).isFalse();
        assertThat(new WindowState(Double.NaN, 0, 800, 600).isUsable()).isFalse();
        assertThat(new WindowState(0, Double.POSITIVE_INFINITY, 800, 600).isUsable()).isFalse();
        assertThat(new WindowState(0, 0, Double.NaN, 600).isUsable()).isFalse();
        assertThat(new WindowState(0, 0, 800, Double.NaN).isUsable()).isFalse();
    }

    @Test
    void aWindowThatFitsIsLeftAlone() {
        var state = new WindowState(100, 50, 800, 600);
        assertThat(state.fit(0, 0, 1920, 1080)).isEqualTo(state);
    }

    @Test
    void aWindowBiggerThanTheScreenIsReduced() {
        var fitted = new WindowState(0, 0, 3000, 2000).fit(0, 0, 1280, 800);
        assertThat(fitted.width()).isEqualTo(1280);
        assertThat(fitted.height()).isEqualTo(800);
    }

    @Test
    void aWindowOutsideTheScreenIsBroughtBackWhereItCanBeReached() {
        // A second screen that is no longer there: x = 3000 on a screen 1280 wide.
        var right = new WindowState(3000, 100, 800, 600).fit(0, 0, 1280, 800);
        assertThat(right.x()).isEqualTo(1180);
        var left = new WindowState(-2000, 100, 800, 600).fit(0, 0, 1280, 800);
        assertThat(left.x()).isEqualTo(-700);          // 100 pixels of it stay on the screen
        var below = new WindowState(100, 5000, 800, 600).fit(0, 0, 1280, 800);
        assertThat(below.y()).isEqualTo(700);
        var above = new WindowState(100, -400, 800, 600).fit(0, 0, 1280, 800);
        assertThat(above.y()).isZero();                // the title bar must be reachable
    }

    @Test
    void theScreenDoesNotHaveToStartAtZero() {
        var fitted = new WindowState(-5000, -5000, 800, 600).fit(1920, 0, 1920, 1080);
        assertThat(fitted.x()).isEqualTo(1920 - 800 + 100);
        assertThat(fitted.y()).isZero();
    }
}
