package io.powerj.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.powerj.api.Option;

class OptionBinderTest {

    enum Mode { FAST, SAFE }

    record Params(
            @Option(position = 0) List<String> paths,
            @Option(shortName = 'a') boolean all,
            @Option(shortName = 'r') boolean recurse,
            @Option(shortName = 'f') String filter,
            @Option(longName = "max-depth") int maxDepth,
            Mode mode,
            Path output) {
    }

    record Required(@Option(mandatory = true) String name, @Option(position = 0) String first,
                    @Option(position = 1) String second) {
    }

    private static Params bind(Object... args) {
        return OptionBinder.bind("t", Params.class, List.of(args));
    }

    @Test
    void defaults() {
        assertThat(bind()).isEqualTo(new Params(List.of(), false, false, null, 0, null, null));
    }

    @Test
    void shortLongAndGroupedFlags() {
        assertThat(bind("-r").recurse()).isTrue();
        assertThat(bind("--recurse").recurse()).isTrue();
        Params grouped = bind("-ra");
        assertThat(grouped.all()).isTrue();
        assertThat(grouped.recurse()).isTrue();
        assertThat(bind("--recurse=false").recurse()).isFalse();
    }

    @Test
    void valuesInEveryForm() {
        assertThat(bind("--filter", "*.java").filter()).isEqualTo("*.java");
        assertThat(bind("--filter=*.java").filter()).isEqualTo("*.java");
        assertThat(bind("-f", "*.java").filter()).isEqualTo("*.java");
        assertThat(bind("-f*.java").filter()).isEqualTo("*.java");
        assertThat(bind("-rf", "*.java").filter()).isEqualTo("*.java");
    }

    @Test
    void conversions() {
        Params p = bind("--max-depth", "3", "--mode", "safe", "--output", "out.txt");
        assertThat(p.maxDepth()).isEqualTo(3);
        assertThat(p.mode()).isEqualTo(Mode.SAFE);
        assertThat(p.output()).isEqualTo(Path.of("out.txt"));
        assertThat(bind("--max-depth", 7).maxDepth()).isEqualTo(7);
    }

    @Test
    void theCaseOfAnOptionNameMatters() {
        assertThatThrownBy(() -> bind("--RECURSE")).hasMessage("t: unknown option --RECURSE, did you mean --recurse?");
        assertThatThrownBy(() -> bind("--Recurse")).hasMessageContaining("did you mean --recurse");
        assertThatThrownBy(() -> bind("--REC")).as("an abbreviation is exact too").hasMessageContaining("unknown option --REC");
        assertThatThrownBy(() -> bind("-R")).as("a short name is exact").hasMessage("t: unknown option -R");
    }

    @Test
    void theValueOfAnEnumeratedOptionIsSpelledInLowerCase() {
        assertThat(bind("--mode", "fast").mode()).isEqualTo(Mode.FAST);
        assertThatThrownBy(() -> bind("--mode", "FAST")).hasMessageContaining("--mode accepts [fast, safe], not 'FAST'");
        assertThatThrownBy(() -> bind("--mode", "Safe")).hasMessageContaining("not 'Safe'");
    }

    @Test
    void aBooleanValueIsTrueOrFalseAndNothingElse() {
        assertThat(bind("--recurse=true").recurse()).isTrue();
        assertThat(bind("--recurse=false").recurse()).isFalse();
        for (String other : List.of("yes", "no", "oui", "non", "1", "0", "TRUE", "True", "FALSE", "No", "")) {
            assertThatThrownBy(() -> bind("--recurse=" + other))
                    .as("--recurse=%s", other)
                    .hasMessage("t: --recurse expects true or false, not '" + other + "'");
        }
    }

    @Test
    void positionalListCollectsTheRestAndDoubleDashEndsOptions() {
        assertThat(bind("a", "-r", "b").paths()).containsExactly("a", "b");
        assertThat(bind("--", "-r", "x").paths()).containsExactly("-r", "x");
        assertThat(bind("-5").paths()).containsExactly("-5");
    }

    @Test
    void pathIsOneValueOfAListButACollectionGivesItsElements() {
        Path path = Path.of("tmp", "dir", "file.txt").toAbsolutePath();
        assertThat(bind(path).paths()).containsExactly(path.toString());
        assertThat(bind(List.of("a", "b"), "c").paths()).containsExactly("a", "b", "c");
    }

    /** An option is written as the help shows it: the beginning of a name is not the name (#88). */
    @Test
    void anAbbreviationIsNotAName() {
        assertThatThrownBy(() -> bind("--rec")).hasMessage("t: unknown option --rec, did you mean --recurse?");
        assertThatThrownBy(() -> bind("--max", "2")).hasMessage("t: unknown option --max, did you mean --max-depth?");
        assertThatThrownBy(() -> bind("--fil", "*.java")).hasMessage("t: unknown option --fil, did you mean --filter?");
        assertThatThrownBy(() -> bind("--recurse-all")).hasMessage("t: unknown option --recurse-all");
    }

    @Test
    void aBeginningThatFitsSeveralNamesSuggestsNone() {
        // --m begins --max-depth and --mode: neither is the name, and the error does not pick one.
        assertThatThrownBy(() -> bind("--m", "x")).hasMessage("t: unknown option --m");
    }

    /** Two names a typo apart: {@code --dir} begins both, so the error picks neither, though one is one edit away. */
    record Near(boolean dirs, boolean dirty) {
    }

    @Test
    void severalNamesBeginningWithTheTextAreNotPickedByEditDistance() {
        assertThatThrownBy(() -> OptionBinder.bind("t", Near.class, List.of("--dir"))).hasMessage("t: unknown option --dir");
        // no name begins with --dirz: it is a typo, and the nearest name is suggested
        assertThatThrownBy(() -> OptionBinder.bind("t", Near.class, List.of("--dirz")))
                .hasMessage("t: unknown option --dirz, did you mean --dirs?");
        // exactly one name begins with --dirt
        assertThatThrownBy(() -> OptionBinder.bind("t", Near.class, List.of("--dirt")))
                .hasMessage("t: unknown option --dirt, did you mean --dirty?");
    }

    @Test
    void aNameIsExactAboutItsHyphens() {
        assertThatThrownBy(() -> bind("--maxdepth", "2")).hasMessage("t: unknown option --maxdepth, did you mean --max-depth?");
        assertThat(bind("--max-depth", "2").maxDepth()).isEqualTo(2);
    }

    @Test
    void errorsAreExplicit() {
        assertThatThrownBy(() -> bind("--recurce"))
                .hasMessage("t: unknown option --recurce, did you mean --recurse?");
        assertThatThrownBy(() -> bind("-z")).hasMessage("t: unknown option -z");
        assertThatThrownBy(() -> bind("--filter")).hasMessage("t: value expected after --filter");
        assertThatThrownBy(() -> bind("--max-depth", "trois"))
                .hasMessage("t: invalid value for --max-depth: 'trois'");
        assertThatThrownBy(() -> bind("--mode", "slow")).hasMessageContaining("--mode accepts [fast, safe]");
    }

    @Test
    void mandatoryAndPositionals() {
        assertThatThrownBy(() -> OptionBinder.bind("t", Required.class, List.of("x")))
                .hasMessage("t: missing required option: --name");
        var r = OptionBinder.bind("t", Required.class, List.of("un", "--name", "n", "deux"));
        assertThat(r).isEqualTo(new Required("n", "un", "deux"));
        assertThatThrownBy(() -> OptionBinder.bind("t", Required.class, List.of("--name", "n", "1", "2", "3")))
                .hasMessage("t: unexpected argument: 3");
    }
}
