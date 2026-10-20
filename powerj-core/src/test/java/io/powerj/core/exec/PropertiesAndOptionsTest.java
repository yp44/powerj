package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.powerj.api.Option;
import io.powerj.api.Row;
import io.powerj.core.lang.Accessor;

/** Properties and indexes of values (FR-27 to FR-29), and option values of every type (FR-18 to FR-20). */
class PropertiesAndOptionsTest {

    @Test
    void accessorsInSequence() {
        Object value = List.of(Map.of("name", "a"));
        assertThat(PropertyAccess.apply(value, List.of(new Accessor.Index(0), new Accessor.Property("name"))))
                .isEqualTo("a");
        assertThat(PropertyAccess.apply("x", List.of())).isEqualTo("x");
    }

    @Test
    void propertyErrors() {
        assertThatThrownBy(() -> PropertyAccess.property(null, "name"))
                .isInstanceOf(PjException.class).hasMessage("property 'name' on a null value");
        assertThatThrownBy(() -> PropertyAccess.property(Set.of(1), "nope"))
                .hasMessageStartingWith("Set has no property 'nope'");
        assertThatThrownBy(() -> PropertyAccess.property(new ArrayDeque<>(List.of(1)), "nope"))
                .hasMessageStartingWith("Collection has no property 'nope'");
        assertThatThrownBy(() -> PropertyAccess.property(new InvokerFixture(), "broken"))
                .hasMessage("reading property 'broken': java.lang.IllegalStateException: broken");
    }

    @Test
    void publicFieldsAreProperties() {
        assertThat(PropertyAccess.property(new InvokerFixture(), "count")).isEqualTo(3);
        assertThat(Members.field(InvokerFixture.class, "COUNT")).isPresent();
        assertThat(Members.ofType(InvokerFixture.class)).extracting(Members.Member::name).contains("count", "broken");
    }

    @Test
    void indexes() {
        assertThat(PropertyAccess.index(new int[] {1, 2}, -1)).isEqualTo(2);
        assertThatThrownBy(() -> PropertyAccess.index(null, 0)).hasMessage("index [0] on a null value");
        assertThatThrownBy(() -> PropertyAccess.index(42, 0)).hasMessage("Integer cannot be indexed");
        assertThatThrownBy(() -> PropertyAccess.index(List.of(1, 2), 5)).hasMessage("index [5] out of bounds (size 2)");
    }

    @Test
    void membersOfARowStartWithItsColumns() {
        var columns = new LinkedHashMap<String, Object>();
        columns.put("name", "a");
        columns.put("size", null);
        var members = Members.of(Row.of(columns));
        assertThat(members.subList(0, 2)).extracting(Members.Member::name, Members.Member::type)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("name", "String"),
                        org.assertj.core.groups.Tuple.tuple("size", "Object"));
        assertThat(members).extracting(Members.Member::name).noneMatch(n -> n.startsWith("put("));
    }

    // --- options ---

    record Typed(@Option(shortName = 's') long size, double ratio, Boolean flag, Duration timeout,
                 @Option(shortName = 'l') Long limit, @SuppressWarnings("rawtypes") List raw, UUID id) {
    }

    record Checked(@Option(position = 0) String name) {
        Checked {
            if ("bad".equals(name)) {
                throw new IllegalArgumentException("bad name");
            }
        }
    }

    record Positional(@Option(position = 0) String first, @Option(position = 1) String second) {
    }

    private static Typed typed(Object... args) {
        return OptionBinder.bind("t", Typed.class, List.of(args));
    }

    @Test
    void numbersBooleansAndDurations() {
        assertThat(typed()).isEqualTo(new Typed(0L, 0.0, null, null, null, List.of(), null));
        assertThat(typed("--size", "10kb").size()).isEqualTo(10_240L);
        assertThat(typed("-s", "5").size()).isEqualTo(5L);
        assertThat(typed("--ratio", "1.5").ratio()).isEqualTo(1.5);
        assertThat(typed("--flag=yes").flag()).isTrue();
        assertThat(typed("--flag=0").flag()).isFalse();
        assertThat(typed("--timeout", "30s").timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(typed("--raw", "a", "--raw", "b").raw()).containsExactly("a", "b");
    }

    @Test
    void invalidOptionValues() {
        assertThatThrownBy(() -> typed("--flag=maybe")).hasMessage("t: --flag expects true or false, not 'maybe'");
        assertThatThrownBy(() -> typed("--timeout", "x"))
                .hasMessage("t: duration expected for --timeout (e.g. 30s, 5m, 2h, 7d), not 'x'");
        assertThatThrownBy(() -> typed("--ratio", "x")).hasMessage("t: invalid value for --ratio: 'x'");
        assertThatThrownBy(() -> typed("--id", "x")).hasMessage("t: unsupported option type: UUID");
        assertThatThrownBy(() -> typed("-l")).hasMessage("t: value expected after -l");
    }

    @Test
    void aPositionalGivenByNameLeavesItsPlaceToTheNext() {
        assertThat(OptionBinder.bind("t", Positional.class, List.of("--first", "a", "b")))
                .isEqualTo(new Positional("a", "b"));
    }

    @Test
    void aRefusalOfTheParametersIsACommandError() {
        assertThatThrownBy(() -> OptionBinder.bind("t", Checked.class, List.of("bad")))
                .isInstanceOf(PjException.class).hasMessage("t: bad name");
    }
}
