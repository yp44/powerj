package io.powerj.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.RandomAccess;

import org.junit.jupiter.api.Test;

class CollectedTest {

    @Test
    void copiesItsElementsAndKeepsNulls() {
        List<String> source = new ArrayList<>(Arrays.asList("a", null, "c"));
        Collected<String> collected = new Collected<>(source);
        source.add("d");

        assertThat(collected).containsExactly("a", null, "c");
        assertThat(collected.size()).isEqualTo(3);
        assertThat(collected.get(2)).isEqualTo("c");
    }

    @Test
    void isUnmodifiableAndRandomAccess() {
        Collected<Integer> collected = new Collected<>(List.of(1, 2));

        assertThat(collected).isInstanceOf(RandomAccess.class);
        assertThatThrownBy(() -> collected.add(3)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> collected.set(0, 9)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> collected.remove(0)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void isAListForEqualityAndStreams() {
        assertThat(new Collected<>(List.of("x", "y"))).isEqualTo(List.of("x", "y")).hasToString("[x, y]");
        assertThat(new Collected<>(List.of(1, 2, 3)).stream().mapToInt(Integer::intValue).sum()).isEqualTo(6);
        assertThat(new Collected<>(List.of())).isEmpty();
    }
}
