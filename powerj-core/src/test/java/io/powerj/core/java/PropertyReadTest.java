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
 * {@link PropertyAccess#property}: the one way to read a property, for {@code $_.name} and for a command that reads the
 * properties of the objects it receives ({@code CmdletContext.readProperty}, #130). The name is a literal one and its case
 * matters, everywhere.
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
        assertThat(PropertyAccess.property(new Process(42, "sleep"), "pid")).isEqualTo(42L);
        assertThat(PropertyAccess.property(new Process(42, "sleep"), "Name")).isEqualTo("sleep");
    }

    @Test
    void theCaseOfTheNameMatters() {
        var process = new Process(42, "sleep");

        assertThatThrownBy(() -> PropertyAccess.property(process, "PID")).isInstanceOf(PjException.class)
                .hasMessageContaining("PID");
        assertThatThrownBy(() -> PropertyAccess.property(process, "name")).isInstanceOf(PjException.class);
    }

    @Test
    void aNameInAnotherCaseIsSuggested() {
        assertThatThrownBy(() -> PropertyAccess.property(new Process(42, "sleep"), "PID"))
                .hasMessageContaining("'pid'");
        assertThatThrownBy(() -> PropertyAccess.property(new Process(42, "sleep"), "name"))
                .hasMessageContaining("'Name'");
        assertThatThrownBy(() -> PropertyAccess.property(new Bean(), "LABEL")).hasMessageContaining("'label'");
    }

    @Test
    void twoComponentsThatDifferOnlyByCaseAreBothReadable() {
        var twins = new Twins(1, 2);

        assertThat(PropertyAccess.property(twins, "a")).isEqualTo(1);
        assertThat(PropertyAccess.property(twins, "A")).isEqualTo(2);
    }

    @Test
    void aKeyOfAMapOrARowIsTakenAsItIsEvenIfItLooksLikeAPathOrHasSpaces() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("first name", "Yves");
        values.put("a.b", 7);
        values.put("Size", 3);

        assertThat(PropertyAccess.property(values, "first name")).isEqualTo("Yves");
        assertThat(PropertyAccess.property(values, "a.b")).isEqualTo(7);
        assertThat(PropertyAccess.property(values, "size")).as("a key is exact").isNull();
        assertThat(PropertyAccess.property(Row.of(values), "first name")).isEqualTo("Yves");
    }

    @Test
    void aGetterIsFoundByTheNameOfItsPropertyAndOnlyByIt() {
        var bean = new Bean();

        assertThat(PropertyAccess.property(bean, "name")).isEqualTo("bean");
        assertThat(PropertyAccess.property(bean, "active")).isEqualTo(true);
        assertThat(PropertyAccess.property(bean, "URL")).as("two capitals at the start: kept, as for a JavaBean")
                .isEqualTo("http://x");
        assertThatThrownBy(() -> PropertyAccess.property(bean, "Name")).as("getName() is name, not Name")
                .isInstanceOf(PjException.class).hasMessageContaining("'name'");
        assertThatThrownBy(() -> PropertyAccess.property(bean, "nAME")).isInstanceOf(PjException.class);
        assertThatThrownBy(() -> PropertyAccess.property(bean, "uRL")).isInstanceOf(PjException.class)
                .hasMessageContaining("'URL'");
        assertThatThrownBy(() -> PropertyAccess.property(bean, "url")).isInstanceOf(PjException.class);
    }

    @Test
    void aPublicFieldIsReadByItsExactName() {
        var bean = new Bean();

        assertThat(PropertyAccess.property(bean, "label")).isEqualTo("L");
        assertThat(PropertyAccess.property(bean, "Label")).isEqualTo("UPPER");
        assertThatThrownBy(() -> PropertyAccess.property(bean, "LABEL")).isInstanceOf(PjException.class);
    }

    @Test
    void aRecordOfAClassThatOtherCodeCannotSeeIsReadByTheCore() {
        Object secret = io.powerj.core.support.HiddenRecords.secret();

        assertThat(PropertyAccess.property(secret, "value")).isEqualTo("s");
    }

    @Test
    void nullHasNoProperty() {
        assertThatThrownBy(() -> PropertyAccess.property(null, "pid")).isInstanceOf(PjException.class);
    }

    @Test
    void aMissingPropertyNamesTheTypeAndTheName() {
        assertThatThrownBy(() -> PropertyAccess.property(new Process(1, "x"), "uid")).isInstanceOf(PjException.class)
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
        assertThatThrownBy(() -> PropertyAccess.property(new Interrupted(), "value")).isInstanceOf(CancellationException.class);
        assertThatThrownBy(() -> PropertyAccess.property(new Interrupted(), "value")).isInstanceOf(CancellationException.class);
    }

    @Test
    void aGetterThatFailsIsAnErrorOfTheObjectThatNamesTheProperty() {
        assertThatThrownBy(() -> PropertyAccess.property(new Interrupted(), "broken")).isInstanceOf(PjException.class)
                .hasMessageContaining("broken");
    }
}
