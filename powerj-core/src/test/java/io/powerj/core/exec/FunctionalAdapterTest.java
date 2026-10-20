package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.powerj.api.ScriptBlock;

/** A block passed where Java expects a functional interface (FR-51). */
class FunctionalAdapterTest {

    private static ScriptBlock block(Function<Object, Object> function) {
        return new ScriptBlock() {
            @Override
            public Object invoke(Object current) {
                return function.apply(current);
            }

            @Override
            public String source() {
                return "test";
            }

            @Override
            public String toString() {
                return "{ test }";
            }
        };
    }

    @Test
    void onlyInterfacesWithOneAbstractMethodAreFunctional() {
        assertThat(FunctionalAdapter.isFunctional(Runnable.class)).isTrue();
        assertThat(FunctionalAdapter.isFunctional(Comparator.class)).isTrue(); // equals is a method of Object
        assertThat(FunctionalAdapter.isFunctional(List.class)).isFalse();
        assertThat(FunctionalAdapter.isFunctional(String.class)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void anyBlockReceivesItsArgumentInCurrent() {
        var upper = (Function<Object, Object>) FunctionalAdapter.adapt(block(s -> ((String) s).toUpperCase()),
                Function.class);
        assertThat(upper.apply("a")).isEqualTo("A");
        assertThat(upper.andThen(s -> s + "!").apply("b")).isEqualTo("B!"); // default method of the interface
        var supplier = (Supplier<Object>) FunctionalAdapter.adapt(block(current -> current == null ? "none" : current),
                Supplier.class);
        assertThat(supplier.get()).isEqualTo("none");
        var run = (Runnable) FunctionalAdapter.adapt(block(current -> "ignored"), Runnable.class);
        run.run();
    }

    @Test
    void objectMethodsOfTheImplementation() {
        Object adapted = FunctionalAdapter.adapt(block(current -> true), Predicate.class);
        assertThat(adapted.toString()).isEqualTo("{ test }");
        assertThat(adapted.hashCode()).isEqualTo(System.identityHashCode(adapted));
        assertThat(adapted.equals(adapted)).isTrue();
        assertThat(adapted.equals("other")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theResultMustSuitTheReturnType() {
        var predicate = (Predicate<Object>) FunctionalAdapter.adapt(block(current -> "yes"), Predicate.class);
        assertThatThrownBy(() -> predicate.test(1))
                .isInstanceOf(PjException.class)
                .hasMessage("the block must return a boolean (Predicate.test), got String \"yes\"");
        var number = (IntSupplier) FunctionalAdapter.adapt(block(current -> "x"), IntSupplier.class);
        assertThatThrownBy(number::getAsInt)
                .hasMessage("the block returns String \"x\", int expected (IntSupplier.getAsInt)");
        var widened = (IntSupplier) FunctionalAdapter.adapt(block(current -> 'a'), IntSupplier.class);
        assertThat(widened.getAsInt()).isEqualTo(97);
    }
}
