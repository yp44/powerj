package io.powerj.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Requires;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * What the module says about itself (ADR 0010, #107): it needs the API and the JDK, and nothing of the engine or of the
 * renderers that use it; its package is exported to every module, since {@code powerj-fx} reads it from another layer.
 */
class ModuleTest {

    /** Read from the compiled descriptor: these tests run on the class path, where the module is not named. */
    private static final Path DESCRIPTOR = Path.of("target/classes/module-info.class");

    private static ModuleDescriptor descriptor() throws IOException {
        try (InputStream in = Files.newInputStream(DESCRIPTOR)) {
            return ModuleDescriptor.read(in);
        }
    }

    @Test
    void theModuleIsCalledIoPowerjTextAndRequiresOnlyTheApi() throws IOException {
        ModuleDescriptor module = descriptor();

        assertThat(module.name()).isEqualTo("io.powerj.text");
        Set<String> required = module.requires().stream()
                .filter(r -> !r.modifiers().contains(Requires.Modifier.MANDATED))
                .map(Requires::name).collect(Collectors.toSet());
        assertThat(required).containsExactly("io.powerj.api");
    }

    @Test
    void itsPackageIsExportedToEveryModuleNotOpenedAndNoServiceIsProvided() throws IOException {
        ModuleDescriptor module = descriptor();

        // powerj-fx is loaded from ~/.powerj/modules in a layer of its own: a qualified export would not reach it
        assertThat(module.exports()).hasSize(1).allSatisfy(e -> {
            assertThat(e.source()).isEqualTo("io.powerj.text");
            assertThat(e.isQualified()).isFalse();
        });
        assertThat(module.opens()).isEmpty();
        assertThat(module.isOpen()).isFalse();
        assertThat(module.provides()).isEmpty();
    }
}
