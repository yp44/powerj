package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Stage previewed according to the cursor (FR-66). */
class PreviewCutTest {

    private static final String LINE = "ls -r | where { f -> f.size > 1kb || f.dir } | map { f -> \"a|b\" + f.name }";

    @Test
    void cursorAtTheEndPreviewsTheWholeLine() {
        assertThat(PreviewCut.upToStage(LINE, LINE.length())).isEqualTo(LINE);
    }

    @Test
    void cursorInAStagePreviewsUpToTheEndOfThatStage() {
        assertThat(PreviewCut.upToStage(LINE, 0)).isEqualTo("ls -r ");
        assertThat(PreviewCut.upToStage(LINE, LINE.indexOf("where"))).isEqualTo(LINE.substring(0, LINE.indexOf("| map")));
        assertThat(PreviewCut.upToStage(LINE, LINE.indexOf("map"))).isEqualTo(LINE);
    }

    @Test
    void pipesInBlocksStringsAndCharactersDoNotSeparateStages() {
        assertThat(PreviewCut.upToStage("\"a | b\".length()", 0)).isEqualTo("\"a | b\".length()");
        assertThat(PreviewCut.upToStage("'|' == '|' | x", 0)).isEqualTo("'|' == '|' ");
        assertThat(PreviewCut.upToStage("\"\"\"\na | b\n\"\"\" | x", 0)).isEqualTo("\"\"\"\na | b\n\"\"\" ");
        assertThat(PreviewCut.upToStage("(ls | where { true }).size() | x", 0)).isEqualTo("(ls | where { true }).size() ");
    }
}
