package io.powerj.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** What of a line may be written in the log: the name of the command and a marker, never the rest (#91). */
class LoggedLineTest {

    static Stream<Arguments> lines() {
        return Stream.of(
                // the name, and the marker when there is more
                Arguments.of("ls", "ls"),
                Arguments.of("ls -l", "ls [truncated...]"),
                Arguments.of("curl -u user:password https://example.org", "curl [truncated...]"),
                Arguments.of(" curl -u user:password", "curl [truncated...]"), // starts with a space: not in the history
                Arguments.of("curl   ", "curl"),
                Arguments.of("^git push --force", "^git [truncated...]"),
                Arguments.of("^ls", "^ls"),
                Arguments.of("cd /tmp; rm secret.txt", "cd [truncated...]"),
                Arguments.of("cmd|grep x", "cmd [truncated...]"),
                Arguments.of("cmd > out.txt", "cmd [truncated...]"),
                Arguments.of("git-lfs track", "git-lfs [truncated...]"),
                Arguments.of("greet:greet Yves", "greet:greet [truncated...]"),
                Arguments.of("éclair --glaçage", "éclair [truncated...]"),
                // no command name: nothing of the line
                Arguments.of("$token=\"s3cret\"", "[truncated...]"),
                Arguments.of("$x = 1", "[truncated...]"),
                Arguments.of("\"s3cret\" | cmd", "[truncated...]"),
                Arguments.of("Files.readString(Path.of(\"s3cret\"))", "[truncated...]"),
                Arguments.of("password=s3cret", "[truncated...]"),
                Arguments.of("{ $_.password }", "[truncated...]"),
                Arguments.of("(1 + 2)", "[truncated...]"),
                Arguments.of("1234", "[truncated...]"),
                Arguments.of("./run.sh --token s3cret", "[truncated...]"),
                Arguments.of("/usr/bin/curl -u user:s3cret", "[truncated...]"),
                Arguments.of("^", "[truncated...]"));
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" is logged as \"{1}\"")
    @MethodSource("lines")
    void aLineIsLoggedAsItsCommandNameAndAMarker(String line, String logged) {
        assertThat(LoggedLine.of(line)).isEqualTo(logged);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t  "})
    void aBlankLineHasNothingToHide(String line) {
        assertThat(LoggedLine.of(line)).isEmpty();
    }

    @Test
    void noLineHasNothingToHide() {
        assertThat(LoggedLine.of(null)).isEmpty();
    }

    @Test
    void aVeryLongNameIsCutAndMarked() {
        String name = "a".repeat(64);

        assertThat(LoggedLine.of(name)).isEqualTo(name);
        assertThat(LoggedLine.of(name + "b")).isEqualTo(name + " [truncated...]");
        assertThat(LoggedLine.of("x".repeat(500) + " --token s3cret")).isEqualTo("x".repeat(64) + " [truncated...]");
    }

    /** A line that is only a word is a name whatever the word is: this is the limit, written down. */
    @Test
    void aWordTypedAloneIsLoggedAsTheNameOfAnUnknownCommand() {
        assertThat(LoggedLine.of("hunter2")).isEqualTo("hunter2");
    }
}
