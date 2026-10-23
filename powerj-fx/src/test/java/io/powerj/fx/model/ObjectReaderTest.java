package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.powerj.api.Bytes;
import io.powerj.api.Display;
import io.powerj.api.Row;

class ObjectReaderTest {

    private record Person(String name, int age, Instant born) {
    }

    @Display(columns = {"name", "nothing", "size"})
    private record Entry(String name, @Bytes long size, String hidden) {
    }

    @Display(columns = {"nothing"})
    private record Misnamed(String a, String b) {
    }

    private record Nothing() {
    }

    private record Broken(String ok) {
        public String ok() {
            throw new IllegalStateException();
        }
    }

    private record Fragile(int a, int b) {
        public int b() {
            throw new IllegalStateException("cannot read b");
        }
    }

    /** A bean: getters and one of everything that is not a property. */
    public static class Bean {
        public String getName() {
            return "n";
        }

        public boolean isActive() {
            return true;
        }

        public Boolean isMaybe() {
            return null;
        }

        public String getURL() {
            return "u";
        }

        public int getAge() {
            return 3;
        }

        public String isNotBoolean() {
            return "x";
        }

        public String get() {
            return "no";
        }

        public String is() {
            return "no";
        }

        public String getWith(int argument) {
            return "no";
        }

        public static String getStatic() {
            return "no";
        }

        public void getNothing() {
        }

        public void setName(String name) {
        }

        public String compute() {
            return "no";
        }
    }

    public static class Plain {
        @Override
        public String toString() {
            return "plain";
        }
    }

    private final ObjectReader reader = new ObjectReader(Reflect::read);

    private static List<String> names(Reading reading) {
        return reading.columns().stream().map(ColumnSpec::name).toList();
    }

    @Test
    void aRecordGivesOneColumnPerComponent() {
        var person = new Person("Ada", 36, Instant.EPOCH);
        Reading reading = reader.read(person);
        assertThat(reading.columns()).containsExactly(new ColumnSpec("name", ColumnKind.TEXT),
                new ColumnSpec("age", ColumnKind.NUMBER), new ColumnSpec("born", ColumnKind.DATE));
        assertThat(reading.row().values()).containsExactly(Map.entry("name", "Ada"), Map.entry("age", 36),
                Map.entry("born", Instant.EPOCH));
        assertThat(reading.row().source()).isSameAs(person);
        assertThat(reading.errors()).isEmpty();
    }

    @Test
    void displayChoosesTheColumnsAndTheirOrder() {
        Reading reading = reader.read(new Entry("a.txt", 2048, "secret"));
        assertThat(reading.columns()).containsExactly(new ColumnSpec("name", ColumnKind.TEXT),
                new ColumnSpec("size", ColumnKind.SIZE));
        assertThat(reading.row().values()).containsOnlyKeys("name", "size");
    }

    @Test
    void displayWithNoKnownColumnFallsBackToEveryComponent() {
        assertThat(names(reader.read(new Misnamed("x", "y")))).containsExactly("a", "b");
    }

    @Test
    void aRecordWithoutComponentIsAValue() {
        Reading reading = reader.read(new Nothing());
        assertThat(names(reading)).containsExactly("value");
        assertThat(reading.row().get("value")).isEqualTo(new Nothing());
    }

    @Test
    void aPropertyThatCannotBeReadIsEmptyAndReportedOnce() {
        Reading reading = reader.read(new Fragile(1, 2));
        assertThat(reading.row().get("a")).isEqualTo(1);
        assertThat(reading.row().get("b")).isNull();
        assertThat(reading.errors()).containsExactly(Map.entry("b", "cannot read b"));
        assertThat(reader.read(new Broken("x")).errors()).containsExactly(Map.entry("ok", "IllegalStateException"));
    }

    @Test
    void aRowGivesItsKeysAndItsSizes() {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("file", "a.txt");
        columns.put("bytes", 2048L);
        columns.put("text", 5L);
        columns.put("missing", null);
        Row row = Row.of(columns, Set.of("bytes"));
        Reading reading = reader.read(row);
        assertThat(reading.columns()).containsExactly(new ColumnSpec("file", ColumnKind.TEXT),
                new ColumnSpec("bytes", ColumnKind.SIZE), new ColumnSpec("text", ColumnKind.NUMBER),
                new ColumnSpec("missing", ColumnKind.UNKNOWN));
        assertThat(reading.row().get("bytes")).isEqualTo(2048L);
        assertThat(reading.row().source()).isSameAs(row);
    }

    @Test
    void aSizeColumnThatIsNotANumberStaysText() {
        Reading reading = reader.read(Row.of(Map.of("bytes", "n/a"), Set.of("bytes")));
        assertThat(reading.columns()).containsExactly(new ColumnSpec("bytes", ColumnKind.TEXT));
    }

    @Test
    void aMapGivesItsKeysAsColumns() {
        Map<Object, Object> map = new LinkedHashMap<>();
        map.put("a", 1);
        map.put(2, "two");
        Reading reading = reader.read(map);
        assertThat(names(reading)).containsExactly("a", "2");
        assertThat(reading.row().get("2")).isEqualTo("two");
    }

    @Test
    void anEmptyMapIsAValue() {
        assertThat(names(reader.read(Map.of()))).containsExactly("value");
    }

    @Test
    void scalarsAreOneValueColumn() {
        Object[] scalars = {"text", new StringBuilder("sb"), 42, 1.5, true, 'c', DayOfWeek.FRIDAY, Instant.EPOCH,
                java.time.Duration.ofSeconds(3), Path.of("a/b"), new java.io.File("f"), java.net.URI.create("x:y"),
                new java.util.Date(0), java.util.UUID.randomUUID()};
        for (Object scalar : scalars) {
            Reading reading = reader.read(scalar);
            assertThat(names(reading)).as(scalar.getClass().getName()).containsExactly("value");
            assertThat(reading.row().get("value")).isSameAs(scalar);
        }
        assertThat(reader.read(42).columns().get(0).kind()).isEqualTo(ColumnKind.NUMBER);
        assertThat(reader.read("a").columns().get(0).kind()).isEqualTo(ColumnKind.TEXT);
    }

    @Test
    void nullIsAnEmptyValue() {
        Reading reading = reader.read(null);
        assertThat(reading.columns()).containsExactly(new ColumnSpec("value", ColumnKind.UNKNOWN));
        assertThat(reading.row().get("value")).isNull();
    }

    @Test
    void aBeanGivesItsGettersSortedByName() {
        Reading reading = reader.read(new Bean());
        assertThat(names(reading)).containsExactly("URL", "active", "age", "maybe", "name");
        assertThat(reading.row().get("name")).isEqualTo("n");
        assertThat(reading.row().get("URL")).isEqualTo("u");
        assertThat(reading.row().get("active")).isEqualTo(true);
        assertThat(reading.row().get("age")).isEqualTo(3);
        assertThat(reading.columns().stream().filter(c -> c.name().equals("age")).findFirst().orElseThrow().kind())
                .isEqualTo(ColumnKind.NUMBER);
    }

    @Test
    void anObjectWithoutGettersIsAValue() {
        Plain plain = new Plain();
        Reading reading = reader.read(plain);
        assertThat(names(reading)).containsExactly("value");
        assertThat(reading.row().get("value")).isSameAs(plain);
    }

    @Test
    void aListOfObjectsOfAnotherKindIsNotUnrolledHere() {
        // The pipeline unrolls collections before grid sees them: a list that arrives is one object, with getters.
        Reading reading = reader.read(new java.util.ArrayList<>(List.of(1, 2)));
        assertThat(names(reading)).contains("empty");
    }
}
