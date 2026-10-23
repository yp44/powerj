package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.powerj.api.Language;

class CellFormatterTest {

    @AfterEach
    void restoreLanguage() {
        Language.set(Locale.ENGLISH);
    }

    private static String text(Object value) {
        return CellFormatter.text(value, ColumnKind.TEXT);
    }

    private static String size(Object value) {
        return CellFormatter.text(value, ColumnKind.SIZE);
    }

    @Test
    void nullIsEmpty() {
        assertThat(text(null)).isEmpty();
        assertThat(CellFormatter.tooltip(null, ColumnKind.TEXT)).isNull();
    }

    @Test
    void plainValuesAreTheirText() {
        assertThat(text("abc")).isEqualTo("abc");
        assertThat(text(42)).isEqualTo("42");
        assertThat(text(true)).isEqualTo("true");
        assertThat(text(java.nio.file.Path.of("a")).replace('\\', '/')).isEqualTo("a");
        assertThat(CellFormatter.text(7, ColumnKind.NUMBER)).isEqualTo("7");
    }

    @Test
    void sizesAreReadable() {
        assertThat(size(0L)).isEqualTo("0 B");
        assertThat(size(1023L)).isEqualTo("1023 B");
        assertThat(size(1024L)).isEqualTo("1.0 KB");
        assertThat(size(14_520L)).isEqualTo("14.2 KB");
        assertThat(size(5L * 1024 * 1024)).isEqualTo("5.0 MB");
        assertThat(size(3L * 1024 * 1024 * 1024)).isEqualTo("3.0 GB");
        assertThat(size(1L << 40)).isEqualTo("1.0 TB");
        assertThat(size(Long.MAX_VALUE)).endsWith("PB");
        assertThat(size(2048)).isEqualTo("2.0 KB");
    }

    @Test
    void theDecimalSeparatorFollowsTheLanguage() {
        Language.set(Locale.FRENCH);
        assertThat(size(14_520L)).isEqualTo("14,2 KB");
    }

    @Test
    void aSizeColumnThatHoldsTextShowsTheText() {
        assertThat(size("n/a")).isEqualTo("n/a");
        assertThat(size(null)).isEmpty();
    }

    @Test
    void instantsAreLocalDatesAndTimes() {
        Instant instant = Instant.parse("2026-10-05T18:12:30Z");
        String expected = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(instant.atZone(ZoneId.systemDefault()));
        assertThat(CellFormatter.text(instant, ColumnKind.DATE)).isEqualTo(expected);
    }

    @Test
    void durationsAreSecondsThenMinutes() {
        assertThat(text(Duration.ofMillis(10))).isEqualTo("0.010 s");
        assertThat(text(Duration.ofSeconds(59))).isEqualTo("59.000 s");
        assertThat(text(Duration.ofSeconds(65))).isEqualTo("1:05");
        assertThat(text(Duration.ofMinutes(90))).isEqualTo("90:00");
    }

    @Test
    void aTextIsOneLine() {
        assertThat(text("a\nb\r\nc\rd")).isEqualTo("a↵b↵c↵d");
        assertThat(CellFormatter.tooltip("a\nb", ColumnKind.TEXT)).isEqualTo("a\nb");
    }

    @Test
    void aLongTextIsCutAndKeptInTheTooltip() {
        String longText = "x".repeat(CellFormatter.MAX_LENGTH + 50);
        String shown = text(longText);
        assertThat(shown).hasSize(CellFormatter.MAX_LENGTH).endsWith("…");
        assertThat(CellFormatter.tooltip(longText, ColumnKind.TEXT)).isEqualTo(longText);
        assertThat(text("y".repeat(CellFormatter.MAX_LENGTH))).hasSize(CellFormatter.MAX_LENGTH).doesNotEndWith("…");
    }

    @Test
    void aCellThatShowsEverythingHasNoTooltip() {
        assertThat(CellFormatter.tooltip("short", ColumnKind.TEXT)).isNull();
    }

    @Test
    void aSizeHasAFullTextTooltipOnlyWhenItDiffers() {
        assertThat(CellFormatter.tooltip(2048L, ColumnKind.SIZE)).isNull();
    }
}
