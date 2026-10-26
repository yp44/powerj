package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class GridRowTest {

    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], pairs[i + 1]);
        }
        return values;
    }

    @Test
    void aValueIsFoundByTheNameOfItsColumn() {
        GridRow row = new GridRow("src", values("name", "Ada", "age", 36), 4);

        assertThat(row.get("name")).isEqualTo("Ada");
        assertThat(row.get("age")).isEqualTo(36);
        assertThat(row.source()).isEqualTo("src");
        assertThat(row.position()).isEqualTo(4);
    }

    @Test
    void aColumnTheRowLacksAndAColumnThatIsNullAreBothNullButOnlyOneIsListed() {
        GridRow row = new GridRow("src", values("name", "Ada", "nick", null));

        assertThat(row.get("nick")).isNull();
        assertThat(row.get("other")).isNull();
        assertThat(row.values()).containsOnlyKeys("name", "nick");
    }

    @Test
    void theValuesComeInTheOrderOfTheColumnsAndCannotBeChanged() {
        GridRow row = new GridRow("src", values("z", 1, "a", 2, "m", 3));

        assertThat(row.values().keySet()).containsExactly("z", "a", "m");
        assertThatThrownBy(() -> row.values().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void anotherPositionKeepsTheValuesAndTheShapeAndDoesNotCopyThem() {
        GridRow row = new GridRow("src", values("a", 1, "b", 2));

        GridRow moved = row.withPosition(9);

        assertThat(moved.position()).isEqualTo(9);
        assertThat(moved.shape()).isSameAs(row.shape());
        assertThat(moved.get("b")).isEqualTo(2);
        assertThat(moved.source()).isSameAs(row.source());
        assertThat(row.position()).isZero();
    }

    @Test
    void rowsAreEqualWhenTheirSourceValuesAndPositionAre() {
        GridRow first = new GridRow("same", values("a", 1), 2);

        assertThat(first).isEqualTo(new GridRow("same", values("a", 1), 2)).hasSameHashCodeAs(
                new GridRow("same", values("a", 1), 2));
        assertThat(first).isNotEqualTo(new GridRow("same", values("a", 1), 3))
                .isNotEqualTo(new GridRow("other", values("a", 1), 2))
                .isNotEqualTo(new GridRow("same", values("a", 2), 2))
                .isNotEqualTo(new GridRow("same", values("b", 1), 2))
                .isNotEqualTo("same");
        assertThat(first.toString()).contains("same", "a=1", "position=2");
    }
}
