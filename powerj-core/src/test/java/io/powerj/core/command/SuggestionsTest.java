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
    void aNameBeyondTwoEditsGetsNothing() {
        assertThat(Suggestions.forName("xyzzy", NAMES)).isEqualTo(Optional.empty());
        assertThat(Suggestions.forName("", NAMES)).isEmpty();
    }

    @Test
    void aNameListedTwiceIsNotATie() {
        assertThat(Suggestions.forName("grpe", List.of("grep", "grep"))).contains("grep");
    }
}
