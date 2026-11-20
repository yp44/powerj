package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.RecordComponent;
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
class ObjectPropertiesTest {

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
    private final ObjectProperties properties = new ObjectProperties(context);

    @Test
    void theColumnsAndTheValuesOfARecordAreThoseOfItsComponents() {
        var entry = new Entry("a.txt", 10, 20, 3);

        assertThat(ObjectProperties.columns(entry)).containsExactly("name", "size", "used", "depth");
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
        assertThat(ObjectProperties.columns(Map.of("first name", "Yves"))).containsExactly("first name");
        assertThat(properties.get(Map.of("first name", "Yves"), "first name")).isEqualTo("Yves");
        assertThat(ObjectProperties.columns("text")).containsExactly(ObjectProperties.VALUE);
        assertThat(properties.get("text", ObjectProperties.VALUE)).isEqualTo("text");
        assertThat(properties.get("text", "other")).isNull();
        assertThat(ObjectProperties.columns(null)).containsExactly(ObjectProperties.VALUE);
    }

    @Test
    void twoObjectsOfTheSameClassHaveTheSameColumns() {
        List<String> first = List.copyOf(ObjectProperties.columns(new Entry("a", 1, 2, 3)));

        assertThat(ObjectProperties.columns(new Entry("b", 4, 5, 6))).isEqualTo(first);
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

    /**
     * Reads a component of a record that this module owns and others cannot see. In the shell the engine makes the accessors
     * accessible; the context of the tests does it with {@code setAccessible}, which a reader outside this module is only
     * allowed when the package is open to it, and whether it is depends on how the test runtime lays out the test classes
     * (Surefire 3.5.3: class path, 3.6.1: module path, #143). The owner of the class can always read it.
     */
    private static Object readAsOwner(Object target, String name) {
        try {
            for (RecordComponent component : target.getClass().getRecordComponents()) {
                if (component.getName().equals(name)) {
                    return MethodHandles.privateLookupIn(target.getClass(), MethodHandles.lookup())
                            .unreflect(component.getAccessor()).invoke(target);
                }
            }
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalArgumentException("no property " + name);
    }

    @Test
    void aRecordThatOtherCodeCannotSeeIsReadAndItsSizesAreKnown() {
        Object secret = io.powerj.cmdlets.support.HiddenRecords.secret();
        context.reader = ObjectPropertiesTest::readAsOwner;

        assertThat(properties.get(secret, "value")).isEqualTo("s");
        assertThat(properties.of((Record) secret)).containsEntry("size", 5L);
        assertThat(Sizes.isSizeProperty(secret, "size")).isTrue();
    }

    @Test
    void anAccessorThatFailsIsUnreadableAndOneThatIsInterruptedStopsTheCommand() {
        var broken = new Broken("fine", "x", "y");

        assertThat(properties.get(broken, "ok")).isEqualTo("fine");
        assertThatThrownBy(() -> properties.get(broken, "boom")).isInstanceOf(ObjectProperties.Unreadable.class)
                .hasMessageContaining("boom").hasMessageContaining("no luck");
        assertThatThrownBy(() -> properties.get(broken, "cancelled")).isInstanceOf(CancellationException.class);
        assertThatThrownBy(() -> properties.of(broken)).isInstanceOf(ObjectProperties.Unreadable.class);
    }

    @Test
    void theValuesAreReadByTheContextAndNotByThisModule() {
        // A context that answers with its own value: nothing in ObjectProperties reads the record behind its back.
        var reading = new TestContext<Object>();
        reading.reader = (target, name) -> "read by the context: " + name;

        assertThat(new ObjectProperties(reading).get(new Entry("a", 1, 2, 3), "size")).isEqualTo("read by the context: size");
        assertThat(new ObjectProperties(reading).of(new Entry("a", 1, 2, 3))).containsEntry("depth", "read by the context: depth");
    }
}
