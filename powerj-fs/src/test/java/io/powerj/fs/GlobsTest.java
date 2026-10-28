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
    void theDefaultMatcherKeepsCase() {
        assumeFalse(System.getProperty("os.name", "").startsWith("Windows"), "the matcher of Windows ignores case");
        var matcher = Globs.matcher("*.TXT");

        assertThat(matcher.matches(Path.of("a.TXT"))).isTrue();
        assertThat(matcher.matches(Path.of("a.txt"))).isFalse();
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

    @Test
    void aBracketExpressionIsAWildcardOfAShellButNotOfACmdlet() {
        assertThat(Globs.hasWildcard("[ab].txt")).isTrue();
        assertThat(Globs.hasWildcard("a[0-9]")).isTrue();
        assertThat(Globs.hasWildcard("a*")).isTrue();
        assertThat(Globs.hasWildcard("a?")).isTrue();
        assertThat(Globs.isPattern("[ab].txt")).isFalse();
    }

    @Test
    void aBracketWithoutItsClosingBracketIsNotAWildcard() {
        assertThat(Globs.hasWildcard("[ab.txt")).isFalse();
        assertThat(Globs.hasWildcard("a[]")).isFalse();
        assertThat(Globs.hasWildcard("plain.txt")).isFalse();
    }

    @Test
    void theVariableThatIgnoresCaseIsTrueOr1() {
        assertThat(Globs.IGNORE_CASE_VARIABLE).isEqualTo("POWERJ_GLOB_IGNORE_CASE");
        assertThat(Globs.ignoreCase("true")).isTrue();
        assertThat(Globs.ignoreCase("TRUE")).isTrue();
        assertThat(Globs.ignoreCase(" 1 ")).isTrue();
        assertThat(Globs.ignoreCase("false")).isFalse();
        assertThat(Globs.ignoreCase("0")).isFalse();
    }

    @Test
    void withoutTheVariableCaseIsKept() {
        assertThat(Globs.ignoreCase(null)).isFalse();
        assertThat(Globs.ignoreCase("")).isFalse();
        assertThat(Globs.ignoreCase("  ")).isFalse();
    }

    @Test
    void anotherValueOfTheVariableIsRefused() {
        assertThatThrownBy(() -> Globs.ignoreCase("yes")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("yes");
    }

    @Test
    void aBackslashEscapesTheNextCharacterOfAPattern() {
        assertThat(Globs.hasWildcard("a\\*b")).isFalse();
        assertThat(Globs.hasWildcard("a\\*b*")).isTrue();
        assertThat(Globs.hasWildcard("a\\\\*")).isTrue(); // an escaped backslash, then a star
        assertThat(Globs.hasWildcard("\\[ab]")).isFalse();
        assertThat(Globs.hasWildcard("[a\\]b]")).isTrue();
    }

    @Test
    void escapeBuildsThePatternThatMatchesExactlyATextAndUnescapeGivesItBack() {
        assertThat(Globs.escape("a*b?[c]{d}\\")).isEqualTo("a\\*b\\?\\[c\\]\\{d\\}\\\\");
        for (String text : new String[] {"plain", "a*b", "my docs", "[x].txt", "{a,b}", "back\\slash", ""}) {
            assertThat(Globs.hasWildcard(Globs.escape(text))).as(text).isFalse();
            assertThat(Globs.unescape(Globs.escape(text))).as(text).isEqualTo(text);
        }
    }

    @Test
    void anEscapedPatternMatchesOnlyTheLiteralText() {
        assumeFalse(System.getProperty("os.name", "").startsWith("Windows"), "a name cannot hold a star there");
        var matcher = Globs.matcher(Globs.escape("a*b"));

        assertThat(matcher.matches(Path.of("a*b"))).isTrue();
        assertThat(matcher.matches(Path.of("aXb"))).isFalse();
    }
}
