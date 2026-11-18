package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;

import org.junit.jupiter.api.Test;

import io.powerj.fs.GlobArgument;

/** What a cmdlet receives for a word of the command line: its value, or a {@link GlobArgument} (audit v2, R-09). */
class WordForCmdletTest {

    @Test
    void aBareWordWithAWildcardIsItsValue() {
        assertThat(new Evaluator.Word("a?.txt", "a?.txt").forCmdlet()).isEqualTo("a?.txt");
    }

    @Test
    void aQuotedTextOrAVariableWithAWildcardIsLiteral() {
        assertThat(new Evaluator.Word("a?.txt", null).forCmdlet())
                .isEqualTo(new GlobArgument("a?.txt", "a\\?.txt"));
        assertThat(new Evaluator.Word("star*", null).forCmdlet()).isEqualTo(new GlobArgument("star*", "star\\*"));
        // a File does not check its name, a Path refuses a ? on Windows
        File file = new File("d?" + File.separator + "x");
        assertThat(new Evaluator.Word(file, null).forCmdlet())
                .isEqualTo(new GlobArgument(file.getPath(), file.getPath().replace("?", "\\?")));
    }

    @Test
    void aCompoundWordKeepsItsUnquotedWildcards() {
        Object word = new Evaluator.Word("d?/*.txt", "d\\?/*.txt").forCmdlet();

        assertThat(word).isEqualTo(new GlobArgument("d?/*.txt", "d\\?/*.txt"));
        assertThat(((GlobArgument) word).hasWildcard()).isTrue();
    }

    @Test
    void aValueWithoutWildcardIsUntouched() {
        assertThat(new Evaluator.Word("a.txt", null).forCmdlet()).isEqualTo("a.txt");
        assertThat(new Evaluator.Word(42, null).forCmdlet()).isEqualTo(42);
        assertThat(new Evaluator.Word(null, null).forCmdlet()).isNull();
        assertThat(new Evaluator.Word("a b", "a b").forCmdlet()).isEqualTo("a b");
    }

    @Test
    void theStringFormIsTheText() {
        assertThat(new Evaluator.Word("a?.txt", null).forCmdlet()).hasToString("a?.txt");
    }
}
