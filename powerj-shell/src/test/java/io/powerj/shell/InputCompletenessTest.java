package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.powerj.shell.InputCompleteness.Result;

class InputCompletenessTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "", "ls -r", "cd C:\\", "ls C:\\temp\\", "where { $_.dir }", "\"a | b\"",
            "\"guillemet \\\" échappé\"", "a || b && c", "ls |\nwhere { $_.dir }", "f(\")\")"})
    void completeInputs(String input) {
        assertThat(InputCompleteness.check(input)).isEqualTo(new Result.Complete());
    }

    @Test
    void trailingPipeOrLogicalOperatorNeedsAContinuation() {
        assertThat(InputCompleteness.check("ls |")).isEqualTo(new Result.Incomplete(InputCompleteness.MISSING_COMMAND, 0));
        assertThat(InputCompleteness.check("ls ||  ")).isEqualTo(new Result.Incomplete(InputCompleteness.MISSING_COMMAND, 0));
        assertThat(InputCompleteness.check("mvn package &&")).isEqualTo(new Result.Incomplete(InputCompleteness.MISSING_COMMAND, 0));
    }

    @Test
    void openBracketsNeedAContinuation() {
        assertThat(InputCompleteness.check("where {")).isEqualTo(new Result.Incomplete("}", 1));
        assertThat(InputCompleteness.check("f(a, [1, {")).isEqualTo(new Result.Incomplete("}", 3));
    }

    @Test
    void unclosedStringNeedsAContinuation() {
        assertThat(InputCompleteness.check("echo \"bonjour")).isEqualTo(new Result.Incomplete("\"", 0));
        assertThat(InputCompleteness.check("\"fin \\\"")).isEqualTo(new Result.Incomplete("\"", 0));
    }

    @Test
    void bracketsInsideStringsAreIgnored() {
        assertThat(InputCompleteness.check("where { $_.contains(\"{\") }")).isEqualTo(new Result.Complete());
    }
}
