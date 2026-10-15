package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.powerj.api.Bytes;
import io.powerj.api.Display;
import io.powerj.api.Highlight;
import io.powerj.api.Language;
import io.powerj.api.Span;

class OutputFormatterTest {

    record Small(String name, @Bytes long size) { }

    @Display(columns = {"name", "count"})
    record Chosen(String name, int count, String hidden) { }

    record Wide(int a, int b, int c, int d, int e, int f) { }

    @Display(columns = {"line", "text"})
    record Found(int line, @Highlight("spans") String text, List<Span> spans) { }

    private static String renderColors(int width, Object... values) {
        var out = new StringWriter();
        try (var formatter = new OutputFormatter(new PrintWriter(out), width, true)) {
            for (Object v : values) {
                formatter.accept(v);
            }
        }
        return out.toString().replace(System.lineSeparator(), "\n");
    }

    private static String render(int width, Object... values) {
        var out = new StringWriter();
        try (var formatter = new OutputFormatter(new PrintWriter(out), width)) {
            for (Object v : values) {
                formatter.accept(v);
            }
        }
        return out.toString().replace(System.lineSeparator(), "\n");
    }

    @Test
    void recordsOfTheSameTypeFormOneTable() {
        assertThat(render(80, new Small("a.txt", 12), new Small("long-name.txt", 2048)))
                .isEqualTo("""
                        name             size
                        ----             ----
                        a.txt            12 B
                        long-name.txt  2.0 KB
                        """);
    }

    @Test
    void highlightedPartsAreInBoldRedWhenColorsAreAccepted() {
        var found = new Found(7, "a TODO and a todo", List.of(new Span(2, 6), new Span(13, 17)));
        assertThat(renderColors(80, found)).isEqualTo("""
                line  text
                ----  ----
                   7  a \u001b[1;31mTODO\u001b[0m and a \u001b[1;31mtodo\u001b[0m
                """);
        assertThat(render(80, found)).doesNotContain("\u001b").contains("   7  a TODO and a todo");
    }

    @Test
    void highlightingStopsAtTheTruncation() {
        String text = "x".repeat(30) + "MATCH";
        var found = new Found(1, text, List.of(new Span(10, 35))); // text column shrunk to 14: 13 characters + "…"
        String rendered = renderColors(20, found);
        assertThat(rendered).contains("xxxxxxxxxx\u001b[1;31mxxx\u001b[0m…").doesNotContain("MATCH");
    }

    @Test
    void highlightIgnoresSpansOutsideTheText() {
        assertThat(OutputFormatter.highlight("abc", List.of(new Span(1, 2), new Span(5, 9)), 3))
                .isEqualTo("a\u001b[1;31mb\u001b[0mc");
        assertThat(OutputFormatter.highlight("abc", null, 3)).isEqualTo("abc");
    }

    @Test
    void displayAnnotationChoosesColumns() {
        assertThat(render(80, new Chosen("x", 3, "secret"))).contains("name  count").doesNotContain("secret");
    }

    @Test
    void recordsWithManyComponentsAreListed() {
        assertThat(render(80, new Wide(1, 2, 3, 4, 5, 6))).startsWith("a : 1\nb : 2\n");
    }

    @Test
    void lineBreaksAreKept() {
        assertThat(render(80, "one\ntwo\nthree")).isEqualTo("one\ntwo\nthree\n");
        assertThat(OutputFormatter.cell("a\r\nb\nc", false)).isEqualTo("a\r\nb\nc");
    }

    @Test
    void scalarsCollectionsAndMaps() {
        assertThat(render(80, "texte", 42, List.of("a", "b"), null)).isEqualTo("texte\n42\na\nb\n");
        assertThat(render(80, Map.of("k", "v"))).contains("key  value", "k    v");
    }

    @Test
    void wideTablesAreTruncatedToTheTerminal() {
        String rendered = render(20, new Small("un-nom-vraiment-tres-long.txt", 1));
        assertThat(rendered.lines()).allMatch(line -> line.length() <= 20);
        assertThat(rendered).contains("…");
    }

    @Test
    void readableValues() {
        assertThat(OutputFormatter.humanBytes(512)).isEqualTo("512 B");
        assertThat(OutputFormatter.humanBytes(5L * 1024 * 1024 * 1024))
                .isEqualTo("5.0 GB");
        assertThat(OutputFormatter.cell(Instant.parse("2026-10-05T18:12:00Z"), false)).matches("2026-10-0[56] \\d\\d:12");
    }

    @Test
    void frenchUsesItsDecimalSeparatorAndHeaders() {
        Language.set(Locale.FRENCH);
        try {
            assertThat(OutputFormatter.humanBytes(5L * 1024 * 1024 * 1024)).isEqualTo("5,0 GB");
            assertThat(render(80, Map.of("k", "v"))).contains("clé  valeur", "---  ------", "k    v");
        } finally {
            Language.set(Locale.ENGLISH);
        }
    }

    @Test
    void manyRowsStreamAfterTheWidthSample() {
        Object[] rows = new Object[120];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = new Small("f" + i, i);
        }
        assertThat(render(80, rows).lines()).hasSize(122);
    }
}
