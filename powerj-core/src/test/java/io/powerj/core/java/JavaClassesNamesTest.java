package io.powerj.core.java;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What {@link JavaClasses#find} answers for text that is not the name of a class: nothing, and no exception (#82). */
class JavaClassesNamesTest {

    private final JavaClasses classes = new JavaClasses();

    /**
     * Completion asks about the text before a dot, and for a file name that starts with a dot ({@code ls .gi},
     * {@code ls ~/.config}) that text is empty or a dot. Each of these used to throw (an index out of bounds), and
     * completion, which swallows the exception, then offered nothing.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", ".", "..", "...", ".a", "a.", "a..b", "java.", "java..List", "java.util."})
    void aTextThatIsNotAClassNameIsNotFound(String text) {
        assertThat(classes.find(text)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "..", "a."})
    void aTextThatIsNotAClassNameIsNotAStaticReference(String text) {
        assertThat(classes.isStaticReference(text)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"List", "java.util.List", "Map.Entry", "Math"})
    void aClassNameIsStillFound(String text) {
        assertThat(classes.find(text)).isPresent();
    }
}
