package io.powerj.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Path;
import java.util.regex.PatternSyntaxException;

import org.junit.jupiter.api.Test;

class GlobsTest {

    @Test
    void onlyStarAndQuestionMarkMakeAPattern() {
        assertThat(Globs.isPattern("*.java")).isTrue();
        assertThat(Globs.isPattern("a?.txt")).isTrue();
        assertThat(Globs.isPattern("plain.txt")).isFalse();
    }

    @Test
    void theDefaultMatcherIgnoresCase() {
        var matcher = Globs.matcher("*.TXT");

        assertThat(matcher.matches(Path.of("a.txt"))).isTrue();
        assertThat(matcher.matches(Path.of("A.Txt"))).isTrue();
        assertThat(matcher.matches(Path.of("a.md"))).isFalse();
    }

    @Test
    void aMatcherThatIgnoresCaseSaysSo() {
        assertThat(Globs.matcher("README*", true).matches(Path.of("readme.md"))).isTrue();
    }

    @Test
    void aMatcherThatKeepsCaseOnlyMatchesTheSameCase() {
        assumeFalse(System.getProperty("os.name", "").startsWith("Windows"), "the matcher of Windows ignores case");
        var matcher = Globs.matcher("README*", false);

        assertThat(matcher.matches(Path.of("README.md"))).isTrue();
        assertThat(matcher.matches(Path.of("readme.md"))).isFalse();
    }

    @Test
    void anInvalidGlobIsAPatternSyntaxException() {
        assertThatThrownBy(() -> Globs.matcher("a[*", false)).isInstanceOf(PatternSyntaxException.class);
        assertThatThrownBy(() -> Globs.matcher("a[*")).isInstanceOf(PatternSyntaxException.class);
    }
}
