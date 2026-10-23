package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ColumnChoiceTest {

    @Test
    void noChoiceShowsEverythingInOrderOfArrival() {
        ColumnChoice all = ColumnChoice.ALL;
        assertThat(all.isAll()).isTrue();
        assertThat(all.names()).isEmpty();
        assertThat(all.visible("anything")).isTrue();
        assertThat(all.insertIndex(List.of("a", "b"), "c")).isEqualTo(2);
        assertThat(all.missing(Set.of())).isEmpty();
    }

    @Test
    void aListOfNamesIsReadWithoutItsBlanksAndRepeats() {
        assertThat(ColumnChoice.parse("name, size ,,modified").names()).containsExactly("name", "size", "modified");
        assertThat(ColumnChoice.parse("name,NAME,Name,size").names()).containsExactly("name", "size");
        assertThat(ColumnChoice.parse("one").isAll()).isFalse();
    }

    @Test
    void aListWithoutNameIsRefused() {
        for (String bad : new String[] {"", "  ", ",", ", ,"}) {
            assertThatThrownBy(() -> ColumnChoice.parse(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void onlyTheColumnsAskedForAreShownWithoutRegardToCase() {
        ColumnChoice choice = ColumnChoice.parse("Name,size");
        assertThat(choice.visible("name")).isTrue();
        assertThat(choice.visible("SIZE")).isTrue();
        assertThat(choice.visible("modified")).isFalse();
    }

    @Test
    void askedColumnsGoFirstInTheOrderAskedAndTheOthersFollowAsTheyCome() {
        ColumnChoice choice = ColumnChoice.parse("size,name");
        // The columns arrive as name, dir, size, modified.
        List<String> columns = new java.util.ArrayList<>();
        for (String arrived : List.of("name", "dir", "size", "modified")) {
            columns.add(choice.insertIndex(columns, arrived), arrived);
        }
        assertThat(columns).containsExactly("size", "name", "dir", "modified");
    }

    @Test
    void anAskedColumnThatComesLateGoesBeforeTheOthers() {
        ColumnChoice choice = ColumnChoice.parse("b");
        List<String> columns = new java.util.ArrayList<>(List.of("a", "c"));
        columns.add(choice.insertIndex(columns, "b"), "b");
        assertThat(columns).containsExactly("b", "a", "c");
    }

    @Test
    void namesAskedForThatNeverCameAreListed() {
        ColumnChoice choice = ColumnChoice.parse("name,Nothing,SIZE,other");
        assertThat(choice.missing(Set.of("name", "size", "dir"))).containsExactly("Nothing", "other");
        assertThat(choice.missing(Set.of("name", "nothing", "size", "other"))).isEmpty();
    }
}
