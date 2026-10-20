package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.powerj.api.ScriptBlock;

class RangeTest {

    private final TestContext<Object> context = new TestContext<>(Path.of("."));

    record Person(String name, Integer age) {
    }

    {
        context.hasInput = true;
        // Simulated compilation of "$_.name" / "$_.age".
        context.compiler = source -> switch (source) {
            case "$_.name" -> block(source, o -> ((Person) o).name());
            case "$_.age" -> block(source, o -> ((Person) o).age());
            default -> throw new IllegalArgumentException(source);
        };
    }

    private static ScriptBlock block(String source, Function<Object, Object> function) {
        return new ScriptBlock() {
            @Override
            public Object invoke(Object current) {
                return function.apply(current);
            }

            @Override
            public String source() {
                return source;
            }
        };
    }

    private static Range.Params params(Object from, Object to) {
        return new Range.Params(from, to, null, false, false, false, false);
    }

    private static Range.Params byName(Object from, Object to) {
        return new Range.Params(from, to, "name", false, false, false, false);
    }

    /** Runs as the shell does (no more objects after stopInput); returns the number of objects read. */
    private int run(Range.Params params, Object... inputs) {
        var range = new Range();
        range.begin(params, context);
        int read = 0;
        for (Object input : inputs) {
            if (context.stopInputCalls > 0) {
                break;
            }
            read++;
            range.process(params, input, context);
        }
        range.end(params, context);
        return read;
    }

    private static Object[] letters() {
        return new Object[] {"a", "b", "c", "d", "e", "f"};
    }

    @Test
    void positions() {
        run(params("3", null), letters());
        assertThat(context.emitted).containsExactly("c", "d", "e", "f");
        assertThat(context.stopInputCalls).isZero();
        context.emitted.clear();

        assertThat(run(params(null, 2), letters())).isEqualTo(2);
        assertThat(context.emitted).containsExactly("a", "b");
        assertThat(context.stopInputCalls).isEqualTo(1);
    }

    @Test
    void fromAndToPositions() {
        assertThat(run(params(2, "4"), letters())).isEqualTo(4);
        assertThat(context.emitted).containsExactly("b", "c", "d");
        context.emitted.clear();
        context.stopInputCalls = 0;
        run(params(3, 3), letters());
        assertThat(context.emitted).containsExactly("c");
    }

    @Test
    void toBeforeFromEmitsNothing() {
        assertThat(run(params(4, 2), letters())).isZero();
        assertThat(context.emitted).isEmpty();
        assertThat(context.stopInputCalls).isEqualTo(1);
    }

    @Test
    void regularExpressionsOnAProperty() {
        var people = new Object[] {new Person("Anna", 1), new Person("Paula", 2), new Person("Bob", 3),
                new Person("Peter", 4), new Person("Carl", 5)};
        assertThat(run(byName("Paul", "Peter"), people)).isEqualTo(4);
        assertThat(context.emitted).extracting(o -> ((Person) o).name()).containsExactly("Paula", "Bob", "Peter");
        context.emitted.clear();
        context.stopInputCalls = 0;
        run(byName("^Paul$", null), people); // Paula is not exactly Paul
        assertThat(context.emitted).isEmpty();
    }

    @Test
    void ignoreCase() {
        var people = new Object[] {new Person("anna", 1), new Person("paul", 2), new Person("bob", 3)};
        run(new Range.Params("PAUL", null, "name", true, false, false, false), people);
        assertThat(context.emitted).extracting(o -> ((Person) o).name()).containsExactly("paul", "bob");
    }

    @Test
    void aNumberWithPropertyIsARegularExpression() {
        var people = new Object[] {new Person("a", 10), new Person("b", 25), new Person("c", 30)};
        run(new Range.Params(25, null, "age", false, false, false, false), people);
        assertThat(context.emitted).extracting(o -> ((Person) o).name()).containsExactly("b", "c");
    }

    @Test
    void nullPropertyNeverMatches() {
        var people = new Object[] {new Person(null, 1), new Person("Paul", 2)};
        run(byName("^", null), people);
        assertThat(context.emitted).extracting(o -> ((Person) o).name()).containsExactly("Paul");
    }

    @Test
    void blockConditions() {
        var begin = block("$_.equals(\"BEGIN\")", s -> s.equals("BEGIN"));
        var end = block("$_.equals(\"END\")", s -> s.equals("END"));
        run(params(begin, end), "x", "BEGIN", "1", "END", "y");
        assertThat(context.emitted).containsExactly("BEGIN", "1", "END");
    }

    @Test
    void toIsTestedFromTheObjectAfterTheOpening() {
        var p = block("$_.startsWith(\"P\")", s -> ((String) s).startsWith("P"));
        run(params(p, p), "a", "Paul", "b", "Peter", "c");
        assertThat(context.emitted).containsExactly("Paul", "b", "Peter");
    }

    @Test
    void conditionThenPosition() {
        var b = block("$_.equals(\"b\")", s -> s.equals("b"));
        run(params(b, 4), letters());
        assertThat(context.emitted).containsExactly("b", "c", "d");
        context.emitted.clear();
        context.stopInputCalls = 0;
        var e = block("$_.equals(\"e\")", s -> s.equals("e"));
        run(params(e, 2), letters()); // --to already passed: the opening object alone, as in sed
        assertThat(context.emitted).containsExactly("e");
    }

    @Test
    void exclude() {
        var p = block("$_.startsWith(\"P\")", s -> ((String) s).startsWith("P"));
        run(new Range.Params(p, p, null, false, false, true, false), "a", "Paul", "b", "Peter", "c");
        assertThat(context.emitted).containsExactly("b");
        assertThatThrownBy(() -> run(new Range.Params(p, p, null, false, true, true, false)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--include");
    }

    @Test
    void repeatEmitsEveryRangeAndReadsEverything() {
        var begin = block("$_.equals(\"BEGIN\")", s -> s.equals("BEGIN"));
        var end = block("$_.equals(\"END\")", s -> s.equals("END"));
        int read = run(new Range.Params(begin, end, null, false, false, false, true),
                "BEGIN", "1", "END", "x", "BEGIN", "2", "END", "y");
        assertThat(read).isEqualTo(8);
        assertThat(context.emitted).containsExactly("BEGIN", "1", "END", "BEGIN", "2", "END");
        assertThat(context.stopInputCalls).isZero();
    }

    @Test
    void repeatNeedsAValueOrConditionFrom() {
        assertThatThrownBy(() -> run(new Range.Params(2, null, null, false, false, false, true)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--repeat");
        assertThatThrownBy(() -> run(new Range.Params(null, 2, null, false, false, false, true)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--repeat");
    }

    @Test
    void textBoundWithoutPropertyIsRefused() {
        assertThatThrownBy(() -> run(params("Paul", null))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--from Paul").hasMessageContaining("--property");
        assertThatThrownBy(() -> run(params(null, 0))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--to");
    }

    @Test
    void invalidRegularExpression() {
        assertThatThrownBy(() -> run(byName("(", null))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("(");
    }

    @Test
    void aFailingConditionSkipsTheObject() {
        var boom = block("$_.boom", s -> {
            if (s.equals("b")) {
                throw new IllegalStateException("boom");
            }
            return s.equals("c");
        });
        run(params(boom, null), letters());
        assertThat(context.emitted).containsExactly("c", "d", "e", "f");
        assertThat(context.errors).hasSize(1).first().asString().contains("boom");
    }

    @Test
    void positionsCountSkippedObjects() {
        var boom = block("$_.boom", s -> {
            if (s.equals("b")) {
                throw new IllegalStateException("boom");
            }
            return false;
        });
        run(params(null, boom), letters()); // b fails: it is skipped, but still counted
        assertThat(context.emitted).containsExactly("a", "c", "d", "e", "f");
    }

    @Test
    void contradictoryOptions() {
        assertThatThrownBy(() -> run(new Range.Params(1, 2, null, false, true, true, false), letters()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--include and --exclude cannot be used together");
        assertThatThrownBy(() -> run(new Range.Params(1, 2, null, false, false, false, true), letters()))
                .hasMessageStartingWith("--repeat needs a --from that is a regular expression");
    }

    @Test
    void positionsAndExcludedBounds() {
        run(new Range.Params(2, 4, null, false, false, true, false), letters());
        assertThat(context.emitted).containsExactly("c");
    }
}
