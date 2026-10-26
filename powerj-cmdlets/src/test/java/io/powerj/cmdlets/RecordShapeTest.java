package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;

import io.powerj.api.Bytes;

/** The components of a record, looked up once per class: the answers are those of the reflection they replace. */
class RecordShapeTest {

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

    @Test
    void theNamesAreThoseOfTheComponentsInDeclarationOrder() {
        assertThat(RecordShape.of(Entry.class).names()).containsExactly("name", "size", "used", "depth");
    }

    @Test
    void aColumnIsFoundByItsNameAndTheCaseMatters() {
        var shape = RecordShape.of(Entry.class);

        assertThat(shape.indexOf("name")).isZero();
        assertThat(shape.indexOf("depth")).isEqualTo(3);
        assertThat(shape.indexOf("Name")).isEqualTo(-1);
        assertThat(shape.indexOf("nothing")).isEqualTo(-1);
    }

    @Test
    void theNamesCannotBeChanged() {
        var names = RecordShape.of(Entry.class).names();

        assertThatThrownBy(() -> names.set(0, "x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> names.add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theSizesAreTheComponentsMarkedAsBytes() {
        var shape = RecordShape.of(Entry.class);

        assertThat(shape.isBytes("size")).isTrue();
        assertThat(shape.isBytes("used")).isTrue();
        assertThat(shape.isBytes("depth")).isFalse();
        assertThat(shape.isBytes("nothing")).isFalse();
        assertThat(Sizes.isSizeProperty(new Entry("a", 1, 2, 3), "used")).isTrue();
        assertThat(Sizes.isSizeProperty(new Entry("a", 1, 2, 3), "name")).isFalse();
        assertThat(Sizes.isSizeProperty("text", "size")).isFalse();
        assertThat(Sizes.isSizeProperty(null, "size")).isFalse();
    }

    @Test
    void theShapeOfAClassIsComputedOnce() {
        assertThat(RecordShape.of(Entry.class)).isSameAs(RecordShape.of(Entry.class));
    }

    @Test
    void theColumnsAndTheValuesOfARecordAreThoseOfItsComponents() {
        var entry = new Entry("a.txt", 10, 20, 3);

        assertThat(Properties.columns(entry)).containsExactly("name", "size", "used", "depth");
        assertThat(Properties.of(entry)).containsExactly(java.util.Map.entry("name", "a.txt"),
                java.util.Map.entry("size", 10L), java.util.Map.entry("used", 20L), java.util.Map.entry("depth", 3));
        assertThat(Properties.get(entry, "size")).isEqualTo(10L);
        assertThat(Properties.get(entry, "nothing")).isNull();
    }

    @Test
    void twoObjectsOfTheSameClassHaveTheSameColumns() {
        List<String> first = List.copyOf(Properties.columns(new Entry("a", 1, 2, 3)));

        assertThat(Properties.columns(new Entry("b", 4, 5, 6))).isEqualTo(first);
    }

    @Test
    void aRecordThatOtherCodeCannotSeeIsReadAndItsSizesAreKnown() {
        Object secret = io.powerj.cmdlets.support.HiddenRecords.secret();

        assertThat(Properties.get(secret, "value")).isEqualTo("s");
        assertThat(Properties.of((Record) secret)).containsEntry("size", 5L);
        assertThat(Sizes.isSizeProperty(secret, "size")).isTrue();
    }

    @Test
    void anAccessorThatFailsIsUnreadableAndOneThatIsInterruptedStopsTheCommand() {
        var broken = new Broken("fine", "x", "y");

        assertThat(Properties.get(broken, "ok")).isEqualTo("fine");
        assertThatThrownBy(() -> Properties.get(broken, "boom")).isInstanceOf(Properties.Unreadable.class)
                .hasMessageContaining("boom").hasMessageContaining("no luck");
        assertThatThrownBy(() -> Properties.get(broken, "cancelled")).isInstanceOf(CancellationException.class);
        assertThatThrownBy(() -> Properties.of(broken)).isInstanceOf(Properties.Unreadable.class);
    }
}
