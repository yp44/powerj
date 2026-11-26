package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.powerj.api.UsageException;
import io.powerj.api.internal.LanguageSetting;
import io.powerj.testing.TestContext;

class EnvTest {

    private TestContext<EnvVar> context;

    @BeforeEach
    void setUp() {
        context = new TestContext<>(Path.of("."));
        context.environment.put("JAVA_HOME", "/jdk");
        context.environment.put("PATH", "/bin");
    }

    /** {@code names}: the positional words, separated by blanks ({@code null}: none). */
    private void env(String names, String set, String unset, String append, String prepend) {
        new Env().begin(params(names, set, unset, append, prepend), context);
    }

    private static Env.Params params(String names, String set, String unset, String append, String prepend) {
        return new Env.Params(names == null ? List.of() : List.of(names.split(" ")), set, unset, append, prepend);
    }

    @Test
    void listsAllOrOne() {
        env(null, null, null, null, null);
        assertThat(context.emitted).contains(new EnvVar("JAVA_HOME", "/jdk"), new EnvVar("PATH", "/bin"));
        context.emitted.clear();
        env("PATH", null, null, null, null);
        assertThat(context.emitted).containsExactly(new EnvVar("PATH", "/bin"));
    }

    @Test
    void setAndUnset() {
        env("-Xmx2g", "MAVEN_OPTS", null, null, null); // "env --set MAVEN_OPTS -- -Xmx2g"
        assertThat(context.environment).containsEntry("MAVEN_OPTS", "-Xmx2g");
        env(null, null, "MAVEN_OPTS", null, null);
        assertThat(context.environment).doesNotContainKey("MAVEN_OPTS");
        env(null, null, "MAVEN_OPTS", null, null);
        assertThat(context.errors).containsExactly("no such variable: MAVEN_OPTS");
    }

    @Test
    void appendAndPrependUseThePathSeparator() {
        // "env --append PATH /tools": PATH is the value of --append, /tools lands in position 0
        env("/tools", null, null, "PATH", null);
        assertThat(context.environment.get("PATH")).isEqualTo("/bin" + File.pathSeparator + "/tools");
        env("/first", null, null, null, "PATH");
        assertThat(context.environment.get("PATH")).startsWith("/first" + File.pathSeparator);
    }

    @Test
    void showsSeveralVariablesInTheOrderGiven() {
        env("PATH JAVA_HOME", null, null, null, null); // "env PATH JAVA_HOME"
        assertThat(context.emitted).containsExactly(new EnvVar("PATH", "/bin"), new EnvVar("JAVA_HOME", "/jdk"));
    }

    @Test
    void aMissingVariableAmongSeveralDoesNotStopTheOthers() {
        env("PATH NOPE JAVA_HOME", null, null, null, null);
        assertThat(context.emitted).containsExactly(new EnvVar("PATH", "/bin"), new EnvVar("JAVA_HOME", "/jdk"));
        assertThat(context.errors).containsExactly("no such variable: NOPE");
    }

    @Test
    void setTakesTheNameThenTheWordsOfTheValueWithoutEquals() {
        env("the value of the var", "MY_VAR", null, null, null); // "env --set MY_VAR the value of the var"
        assertThat(context.environment).containsEntry("MY_VAR", "the value of the var");
        env("-Dfoo=bar", "OPTS", null, null, null); // "env --set OPTS -- -Dfoo=bar": an = in the value is kept
        assertThat(context.environment).containsEntry("OPTS", "-Dfoo=bar");
        env("/jdk25", "JAVA_HOME", null, null, null);
        assertThat(context.environment).containsEntry("JAVA_HOME", "/jdk25");
    }

    @Test
    void anEmptyQuotedValueSetsAnEmptyVariable() {
        env("", "EMPTY", null, null, null); // "env --set EMPTY \"\""
        assertThat(context.environment).containsEntry("EMPTY", "");
    }

    @Test
    void setWithoutAValueOrWithANameThatIsNoNameIsAUsageError() {
        assertThatThrownBy(() -> env(null, "MY_VAR", null, null, null)).isInstanceOf(UsageException.class)
                .hasMessage("value expected: env --set NAME value");
        // NAME=value is not a form of --set: the name and the value are separated by a blank
        assertThatThrownBy(() -> env(null, "A=b", null, null, null)).isInstanceOf(UsageException.class)
                .hasMessage("--set expects the name of a variable, got A=b: write env --set NAME value");
        assertThatThrownBy(() -> env("c", "A=b", null, null, null)).isInstanceOf(UsageException.class)
                .hasMessage("--set expects the name of a variable, got A=b: write env --set NAME value");
        assertThatThrownBy(() -> env("x", "/x", null, null, null)).isInstanceOf(UsageException.class) // env x --set /x
                .hasMessageContaining("--set expects the name of a variable, got /x");
        assertThat(context.environment).doesNotContainKeys("MY_VAR", "A", "A=b", "/x");
    }

    @Test
    void missingVariableIsANonBlockingError() {
        env("NOPE", null, null, null, null);
        assertThat(context.emitted).isEmpty();
        assertThat(context.errors).containsExactly("no such variable: NOPE");
    }

    @AfterEach
    void restoreLanguage() {
        LanguageSetting.set(Locale.ENGLISH);
    }

    @Test
    void missingVariableInFrench() {
        LanguageSetting.set(Locale.FRENCH);
        env("NOPE", null, null, null, null);
        assertThat(context.errors).containsExactly("variable absente : NOPE");
    }

    @Test
    void invalidSetInFrench() {
        LanguageSetting.set(Locale.FRENCH);
        assertThatThrownBy(() -> env(null, "SANS_VALEUR", null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("valeur attendue : env --set NOM valeur");
    }

    @Test
    void providerTranslatesCmdletTexts() {
        var provider = new BuiltinCmdlets();
        assertThat(provider.messages(Locale.ENGLISH).getString("env.summary"))
                .isEqualTo("Shows or changes the session environment variables");
        assertThat(provider.messages(Locale.FRENCH).getString("category.System")).isEqualTo("Système");
        assertThat(provider.messages(Locale.FRENCH).getString("env.option.set"))
                .isEqualTo("Crée ou modifie une variable : --set NOM valeur (une valeur qui commence par - se met après --)");
    }

    @Test
    void oneActionAtATimeAndAValueToAppend() {
        assertThatThrownBy(() -> env("1", "A", "B", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("only one action at a time among --set, --unset, --append, --prepend");
        assertThatThrownBy(() -> env(null, null, null, "PATH", null))
                .hasMessage("value expected: env --append NAME value");
        assertThatThrownBy(() -> env(null, null, null, null, "PATH"))
                .hasMessage("value expected: env --prepend NAME value");
        env("/a", null, null, "NEW_LIST", null);
        context.environment.put("EMPTY", "");
        env("/b", null, null, null, "EMPTY");
        assertThat(context.environment).containsEntry("NEW_LIST", "/a").containsEntry("EMPTY", "/b");
    }

    @Test
    void onlyReadingIsPreviewed() {
        assertThat(new Env().canPreview(params("PATH JAVA_HOME", null, null, null, null))).isTrue();
        assertThat(new Env().canPreview(params("x", "A", null, null, null))).isFalse();
        assertThat(new Env().canPreview(params("1", "A", null, null, null))).isFalse();
    }

    // --- audit v2, U-09 ---

    @Test
    void aNameBeforeTheActionWithAPathAsItsArgumentIsAUsageErrorNotAVariableCalledSlashX() {
        // "env PATH --append /x" binds PATH to the positional name and /x to --append: /x is no variable
        assertThatThrownBy(() -> env("PATH", null, null, "/x", null)).isInstanceOf(UsageException.class)
                .hasMessageContaining("/x").hasMessageContaining("env --append NAME value");

        assertThat(context.environment).doesNotContainKey("/x");
        assertThat(context.environment.get("PATH")).isEqualTo("/bin");
    }

    @Test
    void twoValuesAfterTheNameAreAUsageErrorNotOneOfThemSilentlyDropped() {
        assertThatThrownBy(() -> env("/a /b", null, null, "PATH", null)).isInstanceOf(UsageException.class)
                .hasMessage("--append takes one value, got /a, /b");

        assertThat(context.environment.get("PATH")).isEqualTo("/bin");
    }

    @Test
    void anEntryThatIsAlreadyInTheListIsNotAddedAgain() {
        env("/tools", null, null, "PATH", null);
        env("/tools", null, null, "PATH", null);
        env("/bin", null, null, null, "PATH");

        assertThat(context.environment.get("PATH")).isEqualTo("/bin" + File.pathSeparator + "/tools");
    }

    @Test
    void aVariableNameMayHaveParenthesesAsOnWindows() {
        context.environment.put("ProgramFiles(x86)", "C:\\pf");

        env("D:\\more", null, null, "ProgramFiles(x86)", null);

        assertThat(context.environment.get("ProgramFiles(x86)")).isEqualTo("C:\\pf" + File.pathSeparator + "D:\\more");
    }

    @Test
    void prependMovesAnEntryThatIsAlreadyLaterInTheListToTheHead() {
        context.environment.put("PATH", "/usr/bin" + File.pathSeparator + "/opt/tools");

        env("/opt/tools", null, null, null, "PATH"); // "env --prepend PATH /opt/tools"

        assertThat(context.environment.get("PATH")).isEqualTo("/opt/tools" + File.pathSeparator + "/usr/bin");
    }

    @Test
    void appendLeavesAnEntryThatIsAlreadyInTheListWhereItIs() {
        context.environment.put("PATH", "/usr/bin" + File.pathSeparator + "/opt/tools");

        env("/usr/bin", null, null, "PATH", null);

        assertThat(context.environment.get("PATH")).isEqualTo("/usr/bin" + File.pathSeparator + "/opt/tools");
    }
}
