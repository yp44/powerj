package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.powerj.core.exec.Highlights.Kind;
import io.powerj.core.exec.Highlights.Span;

/** Coloration de la saisie (FR-08). */
class HighlightsTest {

    private static List<Span> of(String line) {
        return Highlights.of(line, name -> switch (name) {
            case "ls", "where" -> Interpreter.CommandKind.CMDLET;
            case "cd" -> Interpreter.CommandKind.BUILTIN;
            case "git", "^ls" -> Interpreter.CommandKind.NATIVE;
            default -> Interpreter.CommandKind.UNKNOWN;
        }, Set.of("Math.PI")::contains);
    }

    private static String colored(String line, Kind kind) {
        return String.join(",", of(line).stream().filter(s -> s.kind() == kind)
                .map(s -> line.substring(s.start(), s.end())).toList());
    }

    @Test
    void commandsByNature() {
        String line = "ls -r | where { $_.dir } ; git log && nope || cd ..";
        assertThat(colored(line, Kind.CMDLET)).isEqualTo("ls,where");
        assertThat(colored(line, Kind.NATIVE)).isEqualTo("git");
        assertThat(colored(line, Kind.UNKNOWN)).isEqualTo("nope");
        assertThat(colored(line, Kind.BUILTIN)).isEqualTo("cd");
        assertThat(colored(line, Kind.OPTION)).isEqualTo("-r");
        assertThat(colored(line, Kind.VARIABLE)).isEqualTo("$_");
        assertThat(colored("^ls", Kind.NATIVE)).isEqualTo("^ls");
    }

    @Test
    void stringsAndVariables() {
        String line = "$x = ls \"a b\" $y";
        assertThat(colored(line, Kind.VARIABLE)).isEqualTo("$x,$y");
        assertThat(colored(line, Kind.STRING)).isEqualTo("\"a b\"");
        assertThat(colored(line, Kind.CMDLET)).isEqualTo("ls");
        assertThat(colored("git commit -m \"non ferm", Kind.STRING)).isEqualTo("\"non ferm");
    }

    @Test
    void javaExpressionsAreNotUnknownCommands() {
        assertThat(colored("Math.max(1, 2)", Kind.UNKNOWN)).isEmpty();
        assertThat(colored("Math.PI", Kind.UNKNOWN)).isEmpty();
        assertThat(colored("new File(\"x\")", Kind.UNKNOWN)).isEmpty();
        assertThat(colored("(ls)*.name", Kind.CMDLET)).isEqualTo("ls");
        assertThat(colored("ls | where { f -> f.size > 1 }", Kind.UNKNOWN)).isEmpty();
        assertThat(colored("ls | where { f -> f.size > 1 }", Kind.OPTION)).isEmpty();
    }
}
