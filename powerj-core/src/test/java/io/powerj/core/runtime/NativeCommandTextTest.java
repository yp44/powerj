package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The one-line form of a native command, shown by {@code jobs} and in the perf summary. */
class NativeCommandTextTest {

    private static String text(String... args) {
        return new NativeRunner.Command(Path.of("bin", "xeyes"), List.of(args), false).text();
    }

    @Test
    void theProgramIsNamedWithoutItsFolder() {
        assertThat(text()).isEqualTo("xeyes");
    }

    @Test
    void plainArgumentsFollowTheName() {
        assertThat(text("-geometry", "100x100")).isEqualTo("xeyes -geometry 100x100");
    }

    @Test
    void anArgumentWithBlanksOrNothingIsQuoted() {
        assertThat(text("a b", "")).isEqualTo("xeyes \"a b\" \"\"");
    }

    @Test
    void lineBreaksAndTabsStayOnOneLine() {
        assertThat(text("one\ntwo\tthree")).isEqualTo("xeyes \"one\\ntwo\\tthree\"");
    }
}
