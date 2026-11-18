package io.powerj.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class SuggestionsTest {

    private static final List<String> NAMES = List.of("grep", "grid", "group", "where", "which", "first");

    @Test
    void anExchangeOfTwoLettersIsOneEdit() {
        assertThat(Suggestions.forName("grpe", NAMES)).contains("grep");
        assertThat(Suggestions.forName("whre", NAMES)).contains("where");
        assertThat(Suggestions.forName("fisrt", NAMES)).contains("first");
    }

    @Test
    void theBeginningOfExactlyOneNameIsASuggestion() {
        assertThat(Suggestions.forName("whe", NAMES)).contains("where");
    }

    @Test
    void severalBeginningsOrSeveralEquallyNearNamesAreNeverGuessed() {
        assertThat(Suggestions.forName("gr", NAMES)).isEmpty(); // grep, grid, group
        assertThat(Suggestions.forName("grip", NAMES)).isEmpty(); // grep and grid are both one edit away
        assertThat(Suggestions.forName("wh", NAMES)).isEmpty(); // where, which
    }

    @Test
    void aShortNameIsNotATypoOfAnotherShortName() {
        List<String> commands = List.of("ls", "cp", "rm", "mv", "cat", "env", "ps");

        assertThat(Suggestions.forName("xyz", commands)).isEmpty(); // 3 letters: one edit at most
        assertThat(Suggestions.forName("qq", commands)).isEmpty(); // 2 letters: no near name at all
        assertThat(Suggestions.forName("lz", commands)).isEmpty();
        assertThat(Suggestions.forName("cta", commands)).contains("cat"); // one exchange
        assertThat(Suggestions.forName("evn", commands)).contains("env");
    }

    @Test
    void theLimitGrowsWithTheLengthOfTheName() {
        assertThat(List.of(0, 1, 2, 3, 5, 6, 12).stream().map(n -> Suggestions.limit("x".repeat(n))).toList())
                .containsExactly(0, 0, 0, 1, 1, 2, 2);
    }

    @Test
    void aNameBeyondTwoEditsGetsNothing() {
        assertThat(Suggestions.forName("xyzzy", NAMES)).isEqualTo(Optional.empty());
        assertThat(Suggestions.forName("", NAMES)).isEmpty();
    }

    @Test
    void aNameListedTwiceIsNotATie() {
        assertThat(Suggestions.forName("grpe", List.of("grep", "grep"))).contains("grep");
    }
}
