package io.powerj.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * What the author of a cmdlet can do with the language: read it, never change it (#79). Only the shell chooses the
 * language, once, at startup; a cmdlet that could call {@code Language.set(Locale.FRENCH)} would change the language of
 * the whole shell.
 */
class LanguageSurfaceTest {

    /** The modules of PowerJ itself, the only ones that may write the language (tests included). */
    private static final Set<String> BUILT_IN = Set.of("io.powerj.core", "io.powerj.cmdlets", "io.powerj.fx",
            "io.powerj.shell");

    /** Read from the compiled descriptor: these tests run on the class path, where the module is not named. */
    private static final Path DESCRIPTOR = Path.of("target/classes/module-info.class");

    @Test
    void aCmdletAuthorCanReadTheLanguageButNotChangeIt() {
        Set<String> methods = Arrays.stream(Language.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(methods).contains("current", "text").doesNotContain("set", "resolve");
    }

    @Test
    void thePackageWhereTheLanguageIsWrittenIsExportedToTheBuiltInModulesOnly() throws IOException {
        Set<ModuleDescriptor.Exports> exports = descriptor().exports();

        Set<String> open = exports.stream().filter(e -> !e.isQualified()).map(ModuleDescriptor.Exports::source)
                .collect(Collectors.toSet());
        assertThat(open).as("packages a third-party module can use").containsExactlyInAnyOrder("io.powerj.api",
                "io.powerj.api.perf");
        ModuleDescriptor.Exports writer = exports.stream()
                .filter(e -> e.source().equals("io.powerj.api.internal")).findFirst().orElse(null);
        assertThat(writer).as("the package where the language is written").isNotNull();
        assertThat(writer.isQualified()).isTrue();
        assertThat(writer.targets()).isEqualTo(BUILT_IN);
    }

    private static ModuleDescriptor descriptor() throws IOException {
        try (InputStream in = Files.newInputStream(DESCRIPTOR)) {
            return ModuleDescriptor.read(in);
        }
    }
}
