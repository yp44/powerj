package io.powerj.core.java;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.GregorianCalendar;

import org.junit.jupiter.api.Test;

/** The accessors of a record, and the static methods of a class, are looked up once and give the same answers as before. */
class RecordShapeTest {

    record Point(int x, int y, String Label) {
    }

    /** Components that differ only by case: the first declared is the one a property name finds. */
    record Twins(int a, int A) {
    }

    @Test
    void aComponentIsFoundWhateverTheCaseOfItsName() throws ReflectiveOperationException {
        var shape = RecordShape.of(Point.class);
        var point = new Point(3, 4, "p");

        assertThat(shape.accessor("x").invoke(point)).isEqualTo(3);
        assertThat(shape.accessor("Y").invoke(point)).isEqualTo(4);
        assertThat(shape.accessor("label").invoke(point)).isEqualTo("p");
        assertThat(shape.accessor("LABEL").invoke(point)).isEqualTo("p");
    }

    @Test
    void aNameThatIsNoComponentHasNoAccessor() {
        assertThat(RecordShape.of(Point.class).accessor("z")).isNull();
        assertThat(RecordShape.of(Point.class).accessor("")).isNull();
        assertThat(RecordShape.of(Point.class).accessor("toString")).isNull();
    }

    @Test
    void twoComponentsThatDifferOnlyByCaseAnswerWithTheFirstDeclared() throws ReflectiveOperationException {
        var twins = new Twins(1, 2);

        assertThat(RecordShape.of(Twins.class).accessor("a").invoke(twins)).isEqualTo(1);
        assertThat(RecordShape.of(Twins.class).accessor("A").invoke(twins)).isEqualTo(1);
    }

    @Test
    void theShapeOfAClassIsComputedOnce() {
        assertThat(RecordShape.of(Point.class)).isSameAs(RecordShape.of(Point.class));
        assertThat(RecordShape.of(Point.class)).isNotSameAs(RecordShape.of(Twins.class));
    }

    @Test
    void theAccessorsOfARecordThatOtherCodeCannotSeeCanBeCalled() throws ReflectiveOperationException {
        Object secret = io.powerj.core.support.HiddenRecords.secret();
        Method accessor = RecordShape.of(secret.getClass()).accessor("value");

        assertThat(accessor.invoke(secret)).isEqualTo("s");
        assertThat(PropertyAccess.property(secret, "SIZE")).isEqualTo(5);
    }

    // --- static methods ---

    @Test
    void theStaticMethodsOfAClassAreFoundByNameAndKeptApartFromTheOthers() {
        assertThat(JavaInvoker.staticMethods(Math.class, "max")).hasSize(4)
                .allSatisfy(method -> assertThat(method.getName()).isEqualTo("max"));
        assertThat(JavaInvoker.staticMethods(Math.class, "noSuchThing")).isEmpty();
        assertThat(JavaInvoker.staticMethods(Math.class, "toString")).isEmpty(); // an instance method of Object
    }

    @Test
    void theListOfStaticMethodsIsTheSameEveryTime() {
        assertThat(JavaInvoker.staticMethods(Math.class, "max")).isSameAs(JavaInvoker.staticMethods(Math.class, "max"));
    }

    @Test
    void aStaticMethodThatTheClassInheritsIsFoundWhenItDeclaresNoneOfThatName() {
        // getInstance() is declared by Calendar, GregorianCalendar inherits it
        assertThat(JavaInvoker.staticMethods(GregorianCalendar.class, "getInstance")).isNotEmpty()
                .allSatisfy(method -> assertThat(method.getDeclaringClass()).isNotEqualTo(GregorianCalendar.class));
        assertThat(JavaInvoker.staticMethods(Math.class, "abs")).allSatisfy(
                method -> assertThat(method.getDeclaringClass()).isEqualTo(Math.class));
    }

    @Test
    void aStaticMethodThatTheClassDeclaresHidesTheInheritedOnesOfTheSameName() {
        // every enum declares valueOf(String), and inherits the static valueOf(Class, String) of Enum
        assertThat(JavaInvoker.staticMethods(java.time.DayOfWeek.class, "valueOf")).singleElement()
                .satisfies(method -> assertThat(method.getDeclaringClass()).isEqualTo(java.time.DayOfWeek.class));
    }

    /** A class of the application, not of the JDK: its methods are not offered to a line (FR-52). */
    public static final class Outsider {
        public static int answer() {
            return 42;
        }
    }

    @Test
    void theStaticMethodsOfAClassOutsideTheJdkAreNotOffered() {
        assertThat(JavaInvoker.staticMethods(Outsider.class, "answer")).isEmpty();
    }

    @Test
    void theListOfStaticMethodsCannotBeChanged() {
        var methods = JavaInvoker.staticMethods(Math.class, "max");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> methods.add(methods.getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
