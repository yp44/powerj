package io.powerj.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RowTest {

    @Test
    void keepsTheOrderOfTheColumnsAndAllowsNullValues() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("name", "a.txt");
        source.put("size", 12L);
        source.put("note", null);

        Row row = Row.of(source);

        assertThat(row.keySet()).containsExactly("name", "size", "note");
        assertThat(row.get("name")).isEqualTo("a.txt");
        assertThat(row.containsKey("note")).isTrue();
        assertThat(row.get("note")).isNull();
        assertThat(row.containsKey("absent")).isFalse();
    }

    @Test
    void isImmutableAndDetachedFromItsSource() {
        Map<String, Object> source = new LinkedHashMap<>(Map.of("a", 1));
        Row row = Row.of(source);
        source.put("b", 2);

        assertThat(row).containsOnlyKeys("a");
        assertThatThrownBy(() -> row.put("c", 3)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> row.remove("a")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> row.entrySet().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sizeColumnsAreDeclaredWithTheRow() {
        assertThat(Row.of(Map.of("kb", 1)).sizeColumns()).isEmpty();
        assertThat(Row.of(Map.of("size", 1, "n", 2), Set.of("size")).sizeColumns()).containsExactly("size");
    }

    @Test
    void behavesAsAMapForEqualityAndText() {
        Row row = Row.of(Map.of("a", 1));
        assertThat(row).isEqualTo(Map.of("a", 1)).hasToString("{a=1}");
        assertThat(row.hashCode()).isEqualTo(Map.of("a", 1).hashCode());
    }
}
