package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** {@link CsvWriter} and {@link CsvReader} (specification FR-73). */
class CsvTest {

    record Item(String name, long size) {
    }

    @Test
    void fieldsAreQuotedWhenNeeded() {
        CsvWriter writer = new CsvWriter(',');
        assertThat(writer.line(List.of("a", "b,c", "say \"hi\"", "two\nlines", " edge", "x;y")))
                .isEqualTo("a,\"b,c\",\"say \"\"hi\"\"\",\"two\nlines\",\" edge\",x;y");
        assertThat(new CsvWriter(';').line(List.of("x;y", "a,b"))).isEqualTo("\"x;y\";a,b");
    }

    @Test
    void valuesAreWrittenWithoutLocaleNorExponent() {
        assertThat(CsvWriter.text(null)).isEmpty();
        assertThat(CsvWriter.text(1.5)).isEqualTo("1.5");
        assertThat(CsvWriter.text(1e20)).isEqualTo("100000000000000000000");
        assertThat(CsvWriter.text(2.0)).isEqualTo("2");
        assertThat(CsvWriter.text(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(CsvWriter.text(Instant.parse("2026-10-18T19:23:00Z"))).isEqualTo("2026-10-18T19:23:00Z");
        assertThat(CsvWriter.text(List.of(1, "a"))).isEqualTo("[1,\"a\"]");
        assertThat(CsvWriter.text(new Item("a", 1))).isEqualTo("{\"name\":\"a\",\"size\":1}");
    }

    @Test
    void delimiterOption() {
        assertThat(CsvWriter.delimiter(null)).isEqualTo(',');
        assertThat(CsvWriter.delimiter(";")).isEqualTo(';');
        assertThat(CsvWriter.delimiter("tab")).isEqualTo('\t');
        assertThatThrownBy(() -> CsvWriter.delimiter(";;")).hasMessage("--delimiter expects one character or tab, got ;;");
        assertThatThrownBy(() -> CsvWriter.delimiter("\"")).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<List<String>> read(char delimiter, String... pieces) {
        CsvReader reader = new CsvReader(delimiter);
        List<List<String>> records = new ArrayList<>();
        for (String piece : pieces) {
            reader.feed(piece).forEach(r -> records.add(r.fields()));
        }
        return records;
    }

    @Test
    void rfc4180() {
        assertThat(read(',', "a,\"b,c\",\"say \"\"hi\"\"\"", "", "x,,\"\"")).containsExactly(
                List.of("a", "b,c", "say \"hi\""), List.of("x", "", ""));
        assertThat(read(',', "a,\"two", "lines\",b")).containsExactly(List.of("a", "two\nlines", "b"));
        assertThat(read(',', "h1,h2\r\nv1,v2\r\n")).containsExactly(List.of("h1", "h2"), List.of("v1", "v2"));
        assertThat(read(';', "﻿a;b", "1;2")).containsExactly(List.of("a", "b"), List.of("1", "2"));
        assertThat(read('\t', "a\tb")).containsExactly(List.of("a", "b"));
    }

    @Test
    void recordLinesAndUnclosedQuotes() {
        CsvReader reader = new CsvReader(',');
        reader.feed("a");
        reader.feed("");
        List<CsvReader.Record> records = reader.feed("\"x");
        assertThat(records).isEmpty();
        records = reader.feed("y\",z");
        assertThat(records).singleElement().satisfies(r -> {
            assertThat(r.fields()).containsExactly("x\ny", "z");
            assertThat(r.line()).isEqualTo(3);
        });
        reader.feed("\"open");
        assertThat(reader.unclosed()).isTrue();
        assertThat(reader.recordLine()).isEqualTo(5);
    }

    @Test
    void roundTrip() {
        List<String> values = List.of("a,b", "\"q\"", "multi\nline", "", " s ");
        String line = new CsvWriter(',').line(values);
        assertThat(read(',', line.split("\n"))).containsExactly(values);
    }
}
