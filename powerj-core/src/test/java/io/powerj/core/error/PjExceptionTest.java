package io.powerj.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.junit.jupiter.api.Test;

/** {@link PjException}: the error it carries, and that it can be serialized like any exception (#84). */
class PjExceptionTest {

    @Test
    void anExceptionMadeFromAMessageCarriesTheErrorOfThatMessage() {
        var exception = new PjException("no such folder");

        assertThat(exception).hasMessage("no such folder").hasNoCause();
        assertThat(exception.error()).isEqualTo(PjError.of("no such folder"));
    }

    @Test
    void anExceptionMadeFromAnErrorGivesItBackWithItsCause() {
        var cause = new IllegalStateException("original");
        var error = PjError.of("could not read", cause);

        var exception = new PjException(error);

        assertThat(exception).hasMessage("could not read").hasCause(cause);
        assertThat(exception.error()).isEqualTo(error);
        assertThat(exception.error().cause()).containsSame(cause);
    }

    /** It used to hold a {@link PjError}, a record with an {@code Optional}, which cannot be serialized. */
    @Test
    void anExceptionSurvivesSerialization() throws IOException, ClassNotFoundException {
        var original = new PjException(PjError.of("could not read", new IllegalStateException("original")));

        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        PjException copy;
        try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            copy = (PjException) in.readObject();
        }

        assertThat(copy).hasMessage("could not read");
        assertThat(copy.error().message()).isEqualTo("could not read");
        assertThat(copy.error().cause()).hasValueSatisfying(cause -> assertThat(cause)
                .isInstanceOf(IllegalStateException.class).hasMessage("original"));
    }
}
