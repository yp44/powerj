package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class GridModelTest {

    private record A(String name, int n) {
    }

    private record B(String name, String extra) {
    }

    private final ObjectReader reader = new ObjectReader(Reflect::read);

    @Test
    void columnsAreTheUnionInOrderOfFirstAppearance() {
        var builder = new GridModel.Builder();
        builder.add(reader.read(new A("a", 1))).add(reader.read(new B("b", "x"))).add(reader.read("text"));
        GridModel model = builder.build();
        assertThat(model.columns().stream().map(ColumnSpec::name)).containsExactly("name", "n", "extra", "value");
        assertThat(model.rows()).hasSize(3);
        assertThat(builder.size()).isEqualTo(3);
    }

    @Test
    void aRowLacksTheColumnsOfOtherTypes() {
        var builder = new GridModel.Builder();
        builder.add(reader.read(new A("a", 1))).add(reader.read(new B("b", "x")));
        GridModel model = builder.build();
        assertThat(model.rows().get(0).get("extra")).isNull();
        assertThat(model.rows().get(0).values()).doesNotContainKey("extra");
        assertThat(model.rows().get(1).get("n")).isNull();
        assertThat(model.rows().get(1).get("extra")).isEqualTo("x");
    }

    @Test
    void aSharedColumnMergesItsKind() {
        var builder = new GridModel.Builder();
        builder.add(reader.read(Map.of("v", 1)));
        assertThat(builder.build().columns()).containsExactly(new ColumnSpec("v", ColumnKind.NUMBER));
        builder.add(reader.read(Map.of("v", "text")));
        assertThat(builder.build().columns()).containsExactly(new ColumnSpec("v", ColumnKind.TEXT));
    }

    @Test
    void aNullDoesNotHideTheKindOfTheOthers() {
        var builder = new GridModel.Builder();
        java.util.Map<String, Object> withNull = new java.util.HashMap<>();
        withNull.put("v", null);
        builder.add(reader.read(withNull)).add(reader.read(Map.of("v", 5)));
        assertThat(builder.build().columns()).containsExactly(new ColumnSpec("v", ColumnKind.NUMBER));
    }

    @Test
    void anEmptyInputIsAnEmptyModel() {
        GridModel model = new GridModel.Builder().build();
        assertThat(model.columns()).isEmpty();
        assertThat(model.rows()).isEmpty();
    }

    @Test
    void theModelDoesNotChangeWhenTheBuilderGoesOn() {
        var builder = new GridModel.Builder();
        builder.add(reader.read(new A("a", 1)));
        GridModel first = builder.build();
        builder.add(reader.read(new A("b", 2)));
        assertThat(first.rows()).hasSize(1);
        assertThat(builder.build().rows()).hasSize(2);
        assertThatThrownBy(() -> first.rows().add(first.rows().get(0))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.columns().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rowsKnowTheirRankOfArrival() {
        var builder = new GridModel.Builder();
        builder.add(reader.read(new A("a", 1))).add(reader.read(new A("b", 2))).add(reader.read("c"));
        assertThat(builder.build().rows().stream().map(GridRow::position)).containsExactly(0, 1, 2);
        assertThat(new GridRow("x", Map.of()).position()).isZero();
    }

    @Test
    void aRowKeepsItsObjectAndItsValuesAreReadOnly() {
        var a = new A("a", 1);
        GridRow row = reader.read(a).row();
        assertThat(row.source()).isSameAs(a);
        assertThatThrownBy(() -> row.values().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(row.get("unknown")).isNull();
    }

    @Test
    void aReadingIsImmutable() {
        Reading reading = reader.read(new A("a", 1));
        assertThat(reading.columns()).isInstanceOf(List.class);
        assertThatThrownBy(() -> reading.columns().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ColumnSpec(null, ColumnKind.TEXT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ColumnSpec("a", null)).isInstanceOf(NullPointerException.class);
    }
}
