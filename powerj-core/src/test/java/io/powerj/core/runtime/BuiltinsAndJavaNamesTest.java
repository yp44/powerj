package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.command.Help;
import io.powerj.core.java.CallPolicy;
import io.powerj.core.java.ClassRef;
import io.powerj.core.java.JavaClasses;
import io.powerj.core.java.MethodReference;
import io.powerj.core.support.FakeCmdlets;
import io.powerj.core.support.InvokerFixture;

/** help, cd, pwd and exit; method references; Java names (FR-04, FR-33b, FR-45, FR-52, FR-54). */
class BuiltinsAndJavaNamesTest {

    @TempDir
    Path tmp;

    record Point(int x, int y) { }

    private Session session() {
        return new Session(tmp, tmp, Map.of("PATH", ""));
    }

    private CallPolicy policy() {
        return new CallPolicy(session()::requestExit);
    }

    /** The built-in commands of a shell, in the order of its overview. */
    private static final List<String> BUILTINS = List.of("cd", "pwd", "which", "help", "import", "mod-load", "mod-list",
            "jobs", "history", "exit");

    private List<Object> help(Object... args) {
        return Help.run(Arrays.asList(args), CmdletRegistry.of(FakeCmdlets.all()), BUILTINS, session().java());
    }

    @Test
    void helpErrors() {
        assertThat(Help.isDocumentedBuiltin("cd")).isTrue();
        assertThat(Help.isDocumentedBuiltin("ls")).isFalse();
        assertThatThrownBy(() -> help("members")).hasMessage("help members: one value expected, e.g. help members $f[0]");
        assertThatThrownBy(() -> help("members", null)).hasMessage("help members: the value is null");
        assertThatThrownBy(() -> help("cd", "pwd")).hasMessage("help: one command at a time");
        assertThatThrownBy(() -> help("int")).hasMessage("help: unknown command or class: int");
        assertThat(help("members", "abc")).isNotEmpty();
    }

    @Test
    void helpOnJavaClassesOfEveryKind() {
        assertThat(help(new ClassRef(Math.class)).getFirst()).isEqualTo("class java.lang.Math");
        assertThat(Help.javaClass(Deprecated.class).getFirst()).isEqualTo("annotation java.lang.Deprecated");
        assertThat(Help.javaClass(Runnable.class).getFirst()).isEqualTo("interface java.lang.Runnable");
        assertThat(Help.javaClass(DayOfWeek.class).getFirst()).isEqualTo("enum java.time.DayOfWeek");
        assertThat(Help.javaClass(Point.class).getFirst()).isEqualTo("record " + Point.class.getName());
        assertThat(Help.javaClass(ArrayList.class).getFirst()).isEqualTo("class java.util.ArrayList extends AbstractList");
        assertThat(Help.javaClass(AbstractList.class)).noneMatch(line -> line.toString().startsWith("new "));
        assertThat(Help.javaClass(InvokerFixture.class)).contains("  count : int");
        assertThat(Help.javaClass(Integer.class)).contains("  static MAX_VALUE : int");
    }

    @Test
    void cdPwdAndExitArguments() {
        var session = session();
        assertThatThrownBy(() -> Builtins.cd(List.of("a", "b"), session)).hasMessage("cd: only one argument expected");
        assertThatThrownBy(() -> Builtins.cd(List.of("a\0b"), session)).hasMessage("cd: invalid path: a\0b");
        assertThatThrownBy(() -> Builtins.pwd(List.of("x"), session)).hasMessage("pwd: no arguments expected");
        assertThatThrownBy(() -> Builtins.exit(List.of("x"), session)).hasMessage("exit: invalid exit code 'x'");
        assertThatThrownBy(() -> Builtins.exit(List.of(1, 2), session)).hasMessage("exit: only one argument expected");
        Builtins.exit(List.of(), session);
        assertThat(session.exitRequest()).hasValue(0);
    }

    @Test
    void methodReferencesDescribeThemselves() {
        var bound = new MethodReference.Bound("abc", "length", policy());
        assertThat(bound.source()).isEqualTo("String::length");
        assertThat(bound.apply(new Object[0])).isEqualTo(3);
        var byName = new MethodReference.ByTypeName("CharSequence", "length", policy());
        assertThat(byName.toString()).isEqualTo("CharSequence::length");
        assertThat(byName.invoke("abcd")).isEqualTo(4); // String implements CharSequence
        assertThatThrownBy(() -> byName.apply(new Object[0]))
                .hasMessage("CharSequence::length: CharSequence expected, got no arguments");
        assertThatThrownBy(() -> byName.invoke(42))
                .hasMessage("CharSequence::length: CharSequence expected, got Integer");
        assertThatThrownBy(() -> new MethodReference.OfClass(String.class, "length", policy()).apply(new Object[0]))
                .hasMessage("String::length: does not apply without arguments");
    }

    @Test
    void javaNames() {
        var java = session().java();
        assertThat(java.find("boolean")).contains(boolean.class);
        assertThat(java.find("byte")).contains(byte.class);
        assertThat(java.find("short")).contains(short.class);
        assertThat(java.find("float")).contains(float.class);
        assertThat(java.find("double")).contains(double.class);
        assertThat(java.find("void")).contains(void.class);
        assertThat(java.isStaticReference("Math")).isTrue();
        assertThat(java.isStaticReference("nope")).isFalse();
        assertThat(java.isStaticReference("Math.PI")).isTrue();
        assertThat(JavaClasses.accessible(String[].class)).isTrue();
        assertThat(JavaClasses.accessible(int.class)).isTrue();
        assertThatThrownBy(() -> java.require("Nope")).hasMessage("class not found: Nope");
    }

    @Test
    void anAmbiguousSimpleNameMustBeQualified() {
        var java = session().java();
        java.addImport("java.sql.*");
        assertThatThrownBy(() -> java.require("Date"))
                .hasMessageStartingWith("ambiguous name: Date (");
        assertThat(java.require("java.sql.Date").getName()).isEqualTo("java.sql.Date");
    }

    @Test
    void standardInputKnowsWhetherItIsATerminal() {
        // Under the build, the standard input is not a terminal.
        assertThat(StandardInput.isTerminal()).isFalse();
    }
}
