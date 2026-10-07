package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EnvTest {

    private TestContext<EnvVar> context;

    @BeforeEach
    void setUp() {
        context = new TestContext<>(Path.of("."));
        context.environment.put("JAVA_HOME", "/jdk");
        context.environment.put("PATH", "/bin");
    }

    private void env(String name, String set, String unset, String append, String prepend, String value) {
        new Env().begin(new Env.Params(name, set, unset, append, prepend, value), context);
    }

    @Test
    void listsAllOrOne() {
        env(null, null, null, null, null, null);
        assertThat(context.emitted).contains(new EnvVar("JAVA_HOME", "/jdk"), new EnvVar("PATH", "/bin"));
        context.emitted.clear();
        env("PATH", null, null, null, null, null);
        assertThat(context.emitted).containsExactly(new EnvVar("PATH", "/bin"));
    }

    @Test
    void setAndUnset() {
        env(null, "MAVEN_OPTS=-Xmx2g", null, null, null, null);
        assertThat(context.environment).containsEntry("MAVEN_OPTS", "-Xmx2g");
        env(null, null, "MAVEN_OPTS", null, null, null);
        assertThat(context.environment).doesNotContainKey("MAVEN_OPTS");
        env(null, null, "MAVEN_OPTS", null, null, null);
        assertThat(context.errors).containsExactly("variable absente : MAVEN_OPTS");
    }

    @Test
    void appendAndPrependUseThePathSeparator() {
        // « env --append PATH /tools » : PATH est la valeur de --append, /tools arrive en position 0
        env("/tools", null, null, "PATH", null, null);
        assertThat(context.environment.get("PATH")).isEqualTo("/bin" + File.pathSeparator + "/tools");
        env("/first", null, null, null, "PATH", null);
        assertThat(context.environment.get("PATH")).startsWith("/first" + File.pathSeparator);
    }

    @Test
    void missingVariableIsANonBlockingError() {
        env("NOPE", null, null, null, null, null);
        assertThat(context.emitted).isEmpty();
        assertThat(context.errors).containsExactly("variable absente : NOPE");
    }
}
