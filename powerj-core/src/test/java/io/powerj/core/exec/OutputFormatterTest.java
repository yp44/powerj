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

class OutputFormatterTest {

    record Small(String name, @Bytes long size) { }

    @Display(columns = {"name", "count"})
    record Chosen(String name, int count, String hidden) { }

    record Wide(int a, int b, int c, int d, int e, int f) { }

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
                        long-name.txt  %s
                        """.formatted(String.format(Locale.getDefault(Locale.Category.FORMAT), "%.1f KB", 2.0)));
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
    void scalarsCollectionsAndMaps() {
        assertThat(render(80, "texte", 42, List.of("a", "b"), null)).isEqualTo("texte\n42\na\nb\n");
        assertThat(render(80, Map.of("k", "v"))).contains("clé  valeur", "k    v");
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
                .isEqualTo(String.format(Locale.getDefault(Locale.Category.FORMAT), "%.1f GB", 5.0));
        assertThat(OutputFormatter.cell(Instant.parse("2026-10-05T18:12:00Z"), false)).matches("2026-10-0[56] \\d\\d:12");
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
