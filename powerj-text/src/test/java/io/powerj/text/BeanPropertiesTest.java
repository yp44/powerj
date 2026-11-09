package io.powerj.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

/** The name of the property of a getter: one rule for the engine that reads it and for the grid that shows it (#130). */
class BeanPropertiesTest {

    @SuppressWarnings("unused")
    public static class Fixture {
        public String getName() {
            return "n";
        }

        public boolean isDirectory() {
            return true;
        }

        public Boolean isBoxed() {
            return true;
        }

        public String isText() {
            return "not a boolean";
        }

        public String getURL() {
            return "u";
        }

        public String getX() {
            return "x";
        }

        public String getx() {
            return "lower case after the prefix";
        }

        public String get_total() {
            return "underscore";
        }

        public String get() {
            return "no name";
        }

        public String getWithArgument(int i) {
            return "argument";
        }

        public void getNothing() {
        }

        public String size() {
            return "not a getter";
        }
    }

    private static String nameOf(String method) throws NoSuchMethodException {
        for (Method m : Fixture.class.getMethods()) {
            if (m.getName().equals(method)) {
                return BeanProperties.nameOf(m);
            }
        }
        throw new NoSuchMethodException(method);
    }

    @Test
    void aGetterGivesItsPropertyWithTheFirstLetterInLowerCase() throws Exception {
        assertThat(nameOf("getName")).isEqualTo("name");
        assertThat(nameOf("getX")).isEqualTo("x");
        assertThat(nameOf("isDirectory")).isEqualTo("directory");
        assertThat(nameOf("isBoxed")).isEqualTo("boxed");
    }

    @Test
    void aNameThatStartsWithTwoCapitalsIsKept() throws Exception {
        assertThat(nameOf("getURL")).isEqualTo("URL");
    }

    @Test
    void whatIsNoGetterGivesNothing() throws Exception {
        assertThat(nameOf("isText")).as("is is for a boolean").isNull();
        assertThat(nameOf("getx")).as("a capital letter after the prefix").isNull();
        assertThat(nameOf("get_total")).isNull();
        assertThat(nameOf("get")).isNull();
        assertThat(nameOf("getWithArgument")).isNull();
        assertThat(nameOf("getNothing")).isNull();
        assertThat(nameOf("size")).isNull();
    }
}
