package io.powerj.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.powerj.api.internal.LanguageSetting;

/**
 * The first line of {@code help <class>} is written as Java writes the declaration ({@code class … extends …}), in every
 * language: the Java keywords are not translated, as the names of the members below it are not (audit v2, B-09; FR-54).
 */
class HelpJavaClassTest {

    @AfterEach
    void english() {
        LanguageSetting.set(Locale.ENGLISH);
    }

    private static String header(Class<?> type) {
        return Help.javaClass(type).getFirst().toString();
    }

    @Test
    void theKindOfTheTypeIsItsJavaKeyword() {
        assertThat(header(ArrayList.class)).isEqualTo("class java.util.ArrayList extends AbstractList");
        assertThat(header(List.class)).isEqualTo("interface java.util.List");
        assertThat(header(DayOfWeek.class)).isEqualTo("enum java.time.DayOfWeek");
        assertThat(header(Map.Entry.class)).isEqualTo("interface java.util.Map$Entry");
        assertThat(header(Retention.class)).isEqualTo("annotation java.lang.annotation.Retention");
        record Point(int x) {
        }
        assertThat(header(Point.class)).startsWith("record ");
    }

    @Test
    void theHeaderIsTheSameInFrench() {
        LanguageSetting.set(Locale.FRENCH);
        // "classe java.util.ArrayList extends AbstractList" mixed the two languages on one line
        assertThat(header(ArrayList.class)).isEqualTo("class java.util.ArrayList extends AbstractList");
        assertThat(header(List.class)).isEqualTo("interface java.util.List");
    }
}
