package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.powerj.api.Language;

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
        assertThat(context.errors).containsExactly("no such variable: MAVEN_OPTS");
    }

    @Test
    void appendAndPrependUseThePathSeparator() {
        // "env --append PATH /tools": PATH is the value of --append, /tools lands in position 0
        env("/tools", null, null, "PATH", null, null);
        assertThat(context.environment.get("PATH")).isEqualTo("/bin" + File.pathSeparator + "/tools");
        env("/first", null, null, null, "PATH", null);
        assertThat(context.environment.get("PATH")).startsWith("/first" + File.pathSeparator);
    }

    @Test
    void missingVariableIsANonBlockingError() {
        env("NOPE", null, null, null, null, null);
        assertThat(context.emitted).isEmpty();
        assertThat(context.errors).containsExactly("no such variable: NOPE");
    }

    @AfterEach
    void restoreLanguage() {
        Language.set(Locale.ENGLISH);
    }

    @Test
    void missingVariableInFrench() {
        Language.set(Locale.FRENCH);
        env("NOPE", null, null, null, null, null);
        assertThat(context.errors).containsExactly("variable absente : NOPE");
    }

    @Test
    void invalidSetInFrench() {
        Language.set(Locale.FRENCH);
        assertThatThrownBy(() -> env(null, "NOEQUALS", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("--set attend NOM=valeur");
    }

    @Test
    void providerTranslatesCmdletTexts() {
        var provider = new BuiltinCmdlets();
        assertThat(provider.messages(Locale.ENGLISH).getString("env.summary"))
                .isEqualTo("Shows or changes the session environment variables");
        assertThat(provider.messages(Locale.FRENCH).getString("category.System")).isEqualTo("Système");
        assertThat(provider.messages(Locale.FRENCH).getString("env.option.set"))
                .isEqualTo("Crée ou modifie une variable : NOM=valeur");
    }
}
