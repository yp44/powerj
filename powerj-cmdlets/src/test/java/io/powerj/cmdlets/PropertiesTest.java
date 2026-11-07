package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;

import io.powerj.api.Bytes;
import io.powerj.testing.TestContext;

/**
 * The columns and the values of an object, for {@code to-json}, {@code to-csv} and {@code join}: the names come from the
 * record, the values from the context ({@code CmdletContext.readProperty}), never from reflection in this module (#130).
 */
class PropertiesTest {

    record Entry(String name, @Bytes long size, @Bytes long used, int depth) {
    }

    /** A record whose accessors fail. */
    record Broken(String ok, String boom, String cancelled) {
        @Override
        public String boom() {
            throw new IllegalStateException("no luck");
        }

        @Override
        public String cancelled() {
            throw new CancellationException();
        }
    }

    private final TestContext<Object> context = new TestContext<>();
    private final Properties properties = new Properties(context);

    @Test
    void theColumnsAndTheValuesOfARecordAreThoseOfItsComponents() {
        var entry = new Entry("a.txt", 10, 20, 3);

        assertThat(Properties.columns(entry)).containsExactly("name", "size", "used", "depth");
        assertThat(properties.of(entry)).containsExactly(Map.entry("name", "a.txt"), Map.entry("size", 10L),
                Map.entry("used", 20L), Map.entry("depth", 3));
        assertThat(properties.get(entry, "size")).isEqualTo(10L);
        assertThat(properties.get(entry, "nothing")).isNull();
    }

    @Test
    void aColumnIsFoundByItsNameAndTheCaseMatters() {
        var entry = new Entry("a.txt", 10, 20, 3);

        assertThat(properties.get(entry, "Name")).isNull();
        assertThat(properties.get(entry, "name")).isEqualTo("a.txt");
    }

    @Test
    void theColumnsOfAMapAreItsKeysAndThoseOfAnythingElseIsOneColumnCalledValue() {
        assertThat(Properties.columns(Map.of("first name", "Yves"))).containsExactly("first name");
        assertThat(properties.get(Map.of("first name", "Yves"), "first name")).isEqualTo("Yves");
        assertThat(Properties.columns("text")).containsExactly(Properties.VALUE);
        assertThat(properties.get("text", Properties.VALUE)).isEqualTo("text");
        assertThat(properties.get("text", "other")).isNull();
        assertThat(Properties.columns(null)).containsExactly(Properties.VALUE);
    }

    @Test
    void twoObjectsOfTheSameClassHaveTheSameColumns() {
        List<String> first = List.copyOf(Properties.columns(new Entry("a", 1, 2, 3)));

        assertThat(Properties.columns(new Entry("b", 4, 5, 6))).isEqualTo(first);
    }

    @Test
    void theSizesAreTheComponentsMarkedAsBytes() {
        assertThat(Sizes.isSizeProperty(new Entry("a", 1, 2, 3), "size")).isTrue();
        assertThat(Sizes.isSizeProperty(new Entry("a", 1, 2, 3), "used")).isTrue();
        assertThat(Sizes.isSizeProperty(new Entry("a", 1, 2, 3), "name")).isFalse();
        assertThat(Sizes.isSizeProperty(new Entry("a", 1, 2, 3), "nothing")).isFalse();
        assertThat(Sizes.isSizeProperty("text", "size")).isFalse();
        assertThat(Sizes.isSizeProperty(null, "size")).isFalse();
    }

    @Test
    void aRecordThatOtherCodeCannotSeeIsReadAndItsSizesAreKnown() {
        Object secret = io.powerj.cmdlets.support.HiddenRecords.secret();

        assertThat(properties.get(secret, "value")).isEqualTo("s");
        assertThat(properties.of((Record) secret)).containsEntry("size", 5L);
        assertThat(Sizes.isSizeProperty(secret, "size")).isTrue();
    }

    @Test
    void anAccessorThatFailsIsUnreadableAndOneThatIsInterruptedStopsTheCommand() {
        var broken = new Broken("fine", "x", "y");

        assertThat(properties.get(broken, "ok")).isEqualTo("fine");
        assertThatThrownBy(() -> properties.get(broken, "boom")).isInstanceOf(Properties.Unreadable.class)
                .hasMessageContaining("boom").hasMessageContaining("no luck");
        assertThatThrownBy(() -> properties.get(broken, "cancelled")).isInstanceOf(CancellationException.class);
        assertThatThrownBy(() -> properties.of(broken)).isInstanceOf(Properties.Unreadable.class);
    }

    @Test
    void theValuesAreReadByTheContextAndNotByThisModule() {
        // A context that answers with its own value: nothing in Properties reads the record behind its back.
        var reading = new TestContext<Object>();
        reading.reader = (target, name) -> "read by the context: " + name;

        assertThat(new Properties(reading).get(new Entry("a", 1, 2, 3), "size")).isEqualTo("read by the context: size");
        assertThat(new Properties(reading).of(new Entry("a", 1, 2, 3))).containsEntry("depth", "read by the context: depth");
    }
}
