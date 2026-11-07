package io.powerj.core.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;

import io.powerj.api.Row;
import io.powerj.core.error.PjException;

/**
 * {@link PropertyAccess#read}: the one way for a command to read a property of the objects it receives
 * ({@code CmdletContext.readProperty}, #130). Same lookup as {@code $_.name}, but the name is a literal one and its case
 * matters.
 */
class PropertyReadTest {

    record Process(long pid, String Name) {
    }

    /** Components that differ only by case. */
    record Twins(int a, int A) {
    }

    public static final class Bean {
        public String label = "L";
        public final String Label = "UPPER";

        public String getName() {
            return "bean";
        }

        public boolean isActive() {
            return true;
        }

        public String getURL() {
            return "http://x";
        }
    }

    @Test
    void aRecordComponentIsReadByItsExactName() {
        assertThat(PropertyAccess.read(new Process(42, "sleep"), "pid")).isEqualTo(42L);
        assertThat(PropertyAccess.read(new Process(42, "sleep"), "Name")).isEqualTo("sleep");
    }

    @Test
    void theCaseOfTheNameMatters() {
        var process = new Process(42, "sleep");

        assertThat(PropertyAccess.property(process, "PID")).as("$_.PID finds pid").isEqualTo(42L);
        assertThatThrownBy(() -> PropertyAccess.read(process, "PID")).isInstanceOf(PjException.class).hasMessageContaining("PID");
        assertThatThrownBy(() -> PropertyAccess.read(process, "name")).isInstanceOf(PjException.class);
    }

    @Test
    void twoComponentsThatDifferOnlyByCaseAreBothReadable() {
        var twins = new Twins(1, 2);

        assertThat(PropertyAccess.read(twins, "a")).isEqualTo(1);
        assertThat(PropertyAccess.read(twins, "A")).isEqualTo(2);
        assertThat(PropertyAccess.property(twins, "A")).as("$_.A finds the first declared").isEqualTo(1);
    }

    @Test
    void aKeyOfAMapOrARowIsTakenAsItIsEvenIfItLooksLikeAPathOrHasSpaces() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("first name", "Yves");
        values.put("a.b", 7);
        values.put("Size", 3);

        assertThat(PropertyAccess.read(values, "first name")).isEqualTo("Yves");
        assertThat(PropertyAccess.read(values, "a.b")).isEqualTo(7);
        assertThat(PropertyAccess.read(values, "size")).as("a key is exact").isNull();
        assertThat(PropertyAccess.read(Row.of(values), "first name")).isEqualTo("Yves");
    }

    @Test
    void aGetterIsFoundByItsPropertyNameAndTheCaseOfTheRestMatters() {
        var bean = new Bean();

        assertThat(PropertyAccess.read(bean, "name")).isEqualTo("bean");
        assertThat(PropertyAccess.read(bean, "Name")).as("the first letter is free, as for a JavaBean").isEqualTo("bean");
        assertThat(PropertyAccess.read(bean, "active")).isEqualTo(true);
        assertThat(PropertyAccess.read(bean, "URL")).isEqualTo("http://x");
        assertThatThrownBy(() -> PropertyAccess.read(bean, "nAME")).isInstanceOf(PjException.class);
        assertThatThrownBy(() -> PropertyAccess.read(bean, "uRl")).isInstanceOf(PjException.class);
    }

    @Test
    void aPublicFieldIsReadByItsExactName() {
        var bean = new Bean();

        assertThat(PropertyAccess.read(bean, "label")).isEqualTo("L");
        assertThat(PropertyAccess.read(bean, "Label")).isEqualTo("UPPER");
        assertThatThrownBy(() -> PropertyAccess.read(bean, "LABEL")).isInstanceOf(PjException.class);
    }

    @Test
    void aRecordOfAClassThatOtherCodeCannotSeeIsReadByTheCore() {
        Object secret = io.powerj.core.support.HiddenRecords.secret();

        assertThat(PropertyAccess.read(secret, "value")).isEqualTo("s");
    }

    @Test
    void nullHasNoProperty() {
        assertThatThrownBy(() -> PropertyAccess.read(null, "pid")).isInstanceOf(PjException.class);
    }

    @Test
    void aMissingPropertyNamesTheTypeAndTheName() {
        assertThatThrownBy(() -> PropertyAccess.read(new Process(1, "x"), "uid")).isInstanceOf(PjException.class)
                .hasMessageContaining("Process").hasMessageContaining("uid");
    }

    public static final class Interrupted {
        public String getValue() {
            throw new CancellationException("stopped");
        }

        public String getBroken() {
            throw new IllegalStateException("broken");
        }
    }

    @Test
    void aGetterThatIsInterruptedEndsTheCommandInsteadOfBeingAnErrorOfTheObject() {
        assertThatThrownBy(() -> PropertyAccess.read(new Interrupted(), "value")).isInstanceOf(CancellationException.class);
        assertThatThrownBy(() -> PropertyAccess.property(new Interrupted(), "value")).isInstanceOf(CancellationException.class);
    }

    @Test
    void aGetterThatFailsIsAnErrorOfTheObjectThatNamesTheProperty() {
        assertThatThrownBy(() -> PropertyAccess.read(new Interrupted(), "broken")).isInstanceOf(PjException.class)
                .hasMessageContaining("broken");
    }
}
