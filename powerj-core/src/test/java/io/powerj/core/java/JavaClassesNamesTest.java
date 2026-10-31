package io.powerj.core.java;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What {@link JavaClasses#find} answers for text that is not the name of a class: nothing, and no exception (#82). */
class JavaClassesNamesTest {

    private final JavaClasses classes = new JavaClasses();

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "..", "a."})
    void aTextThatIsNotAClassNameIsNotAStaticReference(String text) {
        assertThat(classes.isStaticReference(text)).isFalse();
    }
}
