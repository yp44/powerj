package io.powerj.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.powerj.api.Bytes;
import io.powerj.api.Display;
import io.powerj.api.Highlight;
import io.powerj.api.Span;

/**
 * How the output contract of a record is read, once (#108): the columns of {@code @Display}, the sizes of {@code @Bytes}
 * and the parts of {@code @Highlight}, as the terminal, the grid and the exports all need them.
 */
class RecordViewTest {

    record Plain(String a, int b, boolean c) { }

    @Display(columns = {"b", "missing", "a"})
    record Chosen(String a, int b, String c) { }

    @Display(columns = {"x", "y"})
    record NothingThere(String a, int b) { }

    @Display(columns = {"a", "a"})
    record Repeated(String a, int b) { }

    record Sizes(String name, @Bytes long size, @Bytes long used, int depth) { }

    record Found(@Highlight("spans") String text, List<Span> spans, String other) { }

    record Unfounded(@Highlight("nothing") String text, @Highlight("number") String other, int number) { }

    record Empty() { }

    /** Components that differ only by case. */
    record Twins(int a, int A) { }

    private static List<String> names(List<RecordView.Component> components) {
        return components.stream().map(RecordView.Component::name).toList();
    }

    @Test
    void theComponentsAreInDeclarationOrderWithTheirNames() {
        RecordView view = RecordView.of(Plain.class);

        assertThat(view.names()).containsExactly("a", "b", "c");
        assertThat(names(view.components())).containsExactly("a", "b", "c");
        assertThat(view.components()).extracting(RecordView.Component::recordComponent)
                .extracting(RecordComponent::getName).containsExactly("a", "b", "c");
    }

    @Test
    void aColumnIsFoundByItsNameAndTheCaseMatters() {
        RecordView view = RecordView.of(Plain.class);

        assertThat(view.indexOf("a")).isZero();
        assertThat(view.indexOf("c")).isEqualTo(2);
        assertThat(view.indexOf("A")).isEqualTo(-1);
        assertThat(view.indexOf("nothing")).isEqualTo(-1);
        assertThat(view.component("b").name()).isEqualTo("b");
        assertThat(view.component("B")).isNull();
        assertThat(RecordView.of(Twins.class).indexOf("A")).isEqualTo(1);
    }

    @Test
    void withoutDisplayNothingIsDeclaredAndNothingIsDisplayed() {
        RecordView view = RecordView.of(Plain.class);

        assertThat(view.declaresDisplay()).isFalse();
        assertThat(view.displayed()).isEmpty();
    }

    @Test
    void displayNamesTheColumnsInItsOrderAndSkipsTheOnesThatDoNotExist() {
        RecordView view = RecordView.of(Chosen.class);

        assertThat(view.declaresDisplay()).isTrue();
        assertThat(names(view.displayed())).containsExactly("b", "a");
    }

    @Test
    void displayThatNamesNothingThatExistsIsDeclaredButEmpty() {
        RecordView view = RecordView.of(NothingThere.class);

        assertThat(view.declaresDisplay()).isTrue();
        assertThat(view.displayed()).isEmpty();
    }

    @Test
    void aColumnNamedTwiceByDisplayIsShownTwice() {
        assertThat(names(RecordView.of(Repeated.class).displayed())).containsExactly("a", "a");
    }

    @Test
    void theSizesAreTheComponentsMarkedAsBytes() {
        RecordView view = RecordView.of(Sizes.class);

        assertThat(view.isBytes("size")).isTrue();
        assertThat(view.isBytes("used")).isTrue();
        assertThat(view.isBytes("depth")).isFalse();
        assertThat(view.isBytes("nothing")).isFalse();
        assertThat(view.components()).extracting(RecordView.Component::bytes).containsExactly(false, true, true, false);
    }

    @Test
    void highlightNamesTheListOfSpansOfTheSameRecord() {
        RecordView view = RecordView.of(Found.class);

        assertThat(view.component("text").highlight()).isNotNull();
        assertThat(view.component("text").highlight().getName()).isEqualTo("spans");
        assertThat(view.component("spans").highlight()).isNull();
        assertThat(view.component("other").highlight()).isNull();
    }

    @Test
    void highlightThatNamesNoListIsIgnored() {
        RecordView view = RecordView.of(Unfounded.class);

        assertThat(view.component("text").highlight()).as("no such component").isNull();
        assertThat(view.component("other").highlight()).as("a component that is not a List").isNull();
    }

    @Test
    void aRecordWithoutComponentsHasNoColumns() {
        RecordView view = RecordView.of(Empty.class);

        assertThat(view.names()).isEmpty();
        assertThat(view.components()).isEmpty();
        assertThat(view.displayed()).isEmpty();
    }

    @Test
    void theViewOfAClassIsComputedOnceAndCannotBeChanged() {
        assertThat(RecordView.of(Plain.class)).isSameAs(RecordView.of(Plain.class));
        assertThat(RecordView.of(Plain.class)).isNotSameAs(RecordView.of(Sizes.class));
        RecordView view = RecordView.of(Plain.class);

        assertThatThrownBy(() -> view.names().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.components().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> RecordView.of(Chosen.class).displayed().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aTypeThatIsNotARecordIsRefused() {
        assertThatThrownBy(() -> RecordView.of(String.class)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("java.lang.String");
    }
}
