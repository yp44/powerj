package io.powerj.core.nativeexec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The prototype's command line and environment block (issue #128): pure strings, checked on every system. */
class WindowsCommandLineTest {

    @Test
    void anArgumentIsQuotedOnlyWhenItHasToBe() {
        assertThat(WindowsCommandLine.quote("plain")).isEqualTo("plain");
        assertThat(WindowsCommandLine.quote("back\\slash")).isEqualTo("back\\slash");
        assertThat(WindowsCommandLine.quote("trail\\")).isEqualTo("trail\\");
        assertThat(WindowsCommandLine.quote("--flag=value")).isEqualTo("--flag=value");
        assertThat(WindowsCommandLine.quote("é")).isEqualTo("é");
    }

    @Test
    void anEmptyArgumentOrOneWithBlanksIsQuoted() {
        assertThat(WindowsCommandLine.quote("")).isEqualTo("\"\"");
        assertThat(WindowsCommandLine.quote("with space")).isEqualTo("\"with space\"");
        assertThat(WindowsCommandLine.quote("tab\there")).isEqualTo("\"tab\there\"");
    }

    @Test
    void aQuoteIsEscapedAndTheBackslashesBeforeItAreDoubled() {
        assertThat(WindowsCommandLine.quote("a\"b")).isEqualTo("\"a\\\"b\"");
        assertThat(WindowsCommandLine.quote("a\\\"b")).isEqualTo("\"a\\\\\\\"b\"");
        assertThat(WindowsCommandLine.quote("\"already quoted\"")).isEqualTo("\"\\\"already quoted\\\"\"");
    }

    @Test
    void backslashesBeforeTheClosingQuoteAreDoubled() {
        assertThat(WindowsCommandLine.quote("trail space\\")).isEqualTo("\"trail space\\\\\"");
        assertThat(WindowsCommandLine.quote("C:\\Program Files\\x\\")).isEqualTo("\"C:\\Program Files\\x\\\\\"");
    }

    @Test
    void aNulCannotBePassed() {
        assertThatThrownBy(() -> WindowsCommandLine.quote("a\0b")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCommandLineStartsWithTheQuotedProgram() {
        assertThat(WindowsCommandLine.of("C:\\Program Files\\x\\a.exe", List.of("one", "two words", "")))
                .isEqualTo("\"C:\\Program Files\\x\\a.exe\" one \"two words\" \"\"");
        assertThat(WindowsCommandLine.of("a.exe", List.of())).isEqualTo("\"a.exe\"");
    }

    @Test
    void theEnvironmentBlockIsSortedWithoutCaseAndEndsWithTwoNuls() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("b", "2");
        environment.put("C", "3");
        environment.put("A", "deux é");
        assertThat(WindowsCommandLine.environmentBlock(environment)).isEqualTo("A=deux é\0b=2\0C=3\0\0");
    }

    @Test
    void anEmptyEnvironmentIsTwoNuls() {
        assertThat(WindowsCommandLine.environmentBlock(Map.of())).isEqualTo("\0\0");
    }
}
