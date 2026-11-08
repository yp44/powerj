package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.powerj.api.UsageException;
import io.powerj.testing.TestContext;

/**
 * The property, or the path of properties, that a user types as an argument of a cmdlet ({@code sort size},
 * {@code --property owner.name}) is read through the context, one name after the other, and the case of each name matters.
 */
class PropertyPathTest {

    record Owner(String name) {
    }

    record Entry(String path, Owner owner, long size) {
    }

    private final TestContext<Object> context = new TestContext<>();

    @Test
    void aNameIsReadByTheContext() {
        assertThat(PropertyPath.of("size", context).invoke(new Entry("a", new Owner("yves"), 5))).isEqualTo(5L);
    }

    @Test
    void aPathIsReadOneNameAfterTheOther() {
        var asked = new ArrayList<String>();
        context.reader = (target, name) -> {
            asked.add(name);
            return TestContext.readDirectly(target, name);
        };

        Object value = PropertyPath.of("owner.name", context).invoke(new Entry("a", new Owner("yves"), 5));

        assertThat(value).isEqualTo("yves");
        assertThat(asked).containsExactly("owner", "name");
    }

    @Test
    void theCaseOfEachNameMatters() {
        var entry = new Entry("a", new Owner("yves"), 5);

        assertThatThrownBy(() -> PropertyPath.of("Size", context).invoke(entry)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Size");
        assertThatThrownBy(() -> PropertyPath.of("owner.Name", context).invoke(entry))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Name");
    }

    @Test
    void aMissingLinkOfThePathIsAnErrorOfTheObject() {
        record Lonely(Owner owner) {
        }

        assertThatThrownBy(() -> PropertyPath.of("owner.name", context).invoke(new Lonely(null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
    }

    @Test
    void theSourceIsTheExpressionThatItStandsFor() {
        assertThat(PropertyPath.of("owner.name", context).source()).isEqualTo("$_.owner.name");
    }

    @Test
    void whatIsNeitherANameNorAPathOfThemIsRefused() {
        for (String text : List.of("a b", "1x", "size > 1", "a..b", ".a", "a.", "")) {
            assertThatThrownBy(() -> PropertyPath.of(text, context)).as("'" + text + "'")
                    .isInstanceOf(UsageException.class).hasMessageContaining(text);
        }
    }
}
