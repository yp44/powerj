package io.powerj.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import io.powerj.api.CmdletInfo;
import io.powerj.cmdlets.BuiltinCmdlets;

/** Review C-28: {@link BuiltinCmdlets} lists the cmdlets by hand; nothing must be forgotten. */
class CmdletRegistrationTest {

    @Test
    void everyDeclaredBuiltInCmdletIsRegistered() {
        Set<String> declared = new ClassFileImporter().importPackages("io.powerj.cmdlets").stream()
                .filter(c -> c.isAnnotatedWith(CmdletInfo.class))
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        Set<String> registered = new BuiltinCmdlets().cmdlets().stream()
                .map(c -> c.getClass().getName())
                .collect(Collectors.toCollection(java.util.TreeSet::new));

        assertThat(declared).isNotEmpty().isEqualTo(registered);
    }
}
