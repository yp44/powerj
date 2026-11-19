package io.powerj.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.powerj.api.Option;
import io.powerj.fs.GlobArgument;

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
        assertThatThrownBy(() -> bind("-R")).as("a short name is exact").hasMessage("t: unknown option -R (short options: -a, -r, -f)");
    }

    @Test
    void theValueOfAnEnumeratedOptionIsSpelledInLowerCase() {
        assertThat(bind("--mode", "fast").mode()).isEqualTo(Mode.FAST);
        assertThatThrownBy(() -> bind("--mode", "FAST")).hasMessageContaining("--mode accepts [fast, safe], not \"FAST\"");
        assertThatThrownBy(() -> bind("--mode", "Safe")).hasMessageContaining("not \"Safe\"");
    }

    @Test
    void aBooleanValueIsTrueOrFalseAndNothingElse() {
        assertThat(bind("--recurse=true").recurse()).isTrue();
        assertThat(bind("--recurse=false").recurse()).isFalse();
        for (String other : List.of("yes", "no", "oui", "non", "1", "0", "TRUE", "True", "FALSE", "No", "")) {
            assertThatThrownBy(() -> bind("--recurse=" + other))
                    .as("--recurse=%s", other)
                    .hasMessage("t: --recurse expects true or false, not \"" + other + "\"");
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
        assertThatThrownBy(() -> bind("-z")).hasMessage("t: unknown option -z (short options: -a, -r, -f)");
        assertThatThrownBy(() -> bind("-o")).hasMessage("t: unknown option -o, did you mean --output?");
        assertThatThrownBy(() -> bind("--filter")).hasMessage("t: value expected after --filter");
        assertThatThrownBy(() -> bind("--max-depth", "trois"))
                .hasMessage("t: invalid value for --max-depth: \"trois\" (an integer expected)");
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

    // --- A word whose wildcards the quotes made literal (audit v2, R-09) ---

    record Paths(@Option(position = 0, path = true) List<Object> objects, @Option(shortName = 'f') String filter) {
    }

    record TextPaths(@Option(position = 0, path = true) List<String> paths) {
    }

    record OtherObjects(@Option(position = 0) List<Object> values) {
    }

    private static final GlobArgument QUOTED = new GlobArgument("a?.txt", "a\\?.txt");

    @Test
    void aGlobArgumentReachesAnObjectOptionThatTakesPaths() {
        Paths params = OptionBinder.bind("t", Paths.class, List.of(QUOTED, "*.log"));

        assertThat(params.objects()).containsExactly(QUOTED, "*.log");
    }

    @Test
    void aGlobArgumentIsItsTextForEveryOtherOption() {
        assertThat(OptionBinder.bind("t", TextPaths.class, List.of(QUOTED)).paths()).containsExactly("a?.txt");
        assertThat(OptionBinder.bind("t", OtherObjects.class, List.of(QUOTED)).values()).containsExactly("a?.txt");
        assertThat(OptionBinder.bind("t", Paths.class, List.of("--filter", QUOTED)).filter()).isEqualTo("a?.txt");
    }

    @Test
    void aGlobArgumentIsNeverTakenForAnOption() {
        record Flags(@Option(shortName = 'x') boolean x, @Option(position = 0, path = true) List<Object> objects) { }

        Flags params = OptionBinder.bind("t", Flags.class, List.of(new GlobArgument("-x?", "-x\\?")));

        assertThat(params.x()).isFalse();
        assertThat(params.objects()).hasSize(1);
    }

    // --- every primitive has a default and a conversion (audit v2 R-02, #166) ---

    record Small(@Option(shortName = 'r') float ratio, short count, byte flags, char letter, @Option(shortName = 'x') boolean x) { }

    @Test
    void everyPrimitiveComponentHasItsDefaultWhenNothingIsGiven() {
        assertThat(OptionBinder.bind("t", Small.class, List.of())).isEqualTo(new Small(0f, (short) 0, (byte) 0, '\0', false));
    }

    @Test
    void floatShortByteAndCharAreConvertedLikeTheOtherNumbers() {
        Small bound = OptionBinder.bind("t", Small.class,
                List.of("--ratio", "1.5", "--count", "300", "--flags", "-7", "--letter", "z"));

        assertThat(bound).isEqualTo(new Small(1.5f, (short) 300, (byte) -7, 'z', false));
    }

    @Test
    void aValueOutOfTheRangeOfTheTypeIsAnInvalidValueNotAnInternalError() {
        assertThatThrownBy(() -> OptionBinder.bind("t", Small.class, List.of("--flags", "300")))
                .isInstanceOf(io.powerj.core.error.PjException.class).hasMessageContaining("--flags");
        assertThatThrownBy(() -> OptionBinder.bind("t", Small.class, List.of("--letter", "ab")))
                .isInstanceOf(io.powerj.core.error.PjException.class).hasMessageContaining("--letter");
    }

    // --- a record of parameters is checked once, when its cmdlet is registered (#166) ---

    record TwiceShort(@Option(shortName = 'f') String first, @Option(shortName = 'f') String second) { }

    record TwiceLong(@Option(longName = "same") String first, @Option(longName = "same") String second) { }

    record TwicePosition(@Option(position = 0) String first, @Option(position = 0) String second) { }

    record ListNotLast(@Option(position = 0) List<String> many, @Option(position = 1) String last) { }

    record Unsupported(Thread thread) { }

    @Test
    void aValidRecordHasNoProblem() {
        OptionBinder.validate(Params.class);
        OptionBinder.validate(Small.class);
        OptionBinder.validate(Required.class);
    }

    @Test
    void aRecordThatCannotWorkIsRefusedWithTheNameOfTheComponent() {
        assertThatThrownBy(() -> OptionBinder.validate(TwiceShort.class)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("second").hasMessageContaining("-f");
        assertThatThrownBy(() -> OptionBinder.validate(TwiceLong.class)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("second").hasMessageContaining("--same");
        assertThatThrownBy(() -> OptionBinder.validate(TwicePosition.class)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("second").hasMessageContaining("position 0");
        assertThatThrownBy(() -> OptionBinder.validate(ListNotLast.class)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("many");
        assertThatThrownBy(() -> OptionBinder.validate(Unsupported.class)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("thread").hasMessageContaining("Thread");
    }

    // --- what the help and the errors tell about the type of an option (audit v2, U-05, #176) ---

    record Typed(@Option(position = 0, mandatory = true) String target, @Option(mandatory = true) int count,
                 long size, java.time.Duration since, Path out, Mode mode, double ratio, @Option(shortName = 'c') char letter,
                 boolean all) { }

    @Test
    void anOptionHasALabelThatSaysWhatItTakes() {
        var labels = OptionBinder.specs(Typed.class).stream()
                .collect(java.util.stream.Collectors.toMap(OptionBinder.OptionSpec::longName, OptionBinder.OptionSpec::valueLabel));

        assertThat(labels).containsEntry("count", "<n>").containsEntry("size", "<size>").containsEntry("since", "<duration>")
                .containsEntry("out", "<path>").containsEntry("mode", "{fast|safe}").containsEntry("ratio", "<number>")
                .containsEntry("letter", "<char>").containsEntry("target", "<text>");
    }

    @Test
    void aMissingPositionalIsNotCalledAnOptionWithDashes() {
        assertThatThrownBy(() -> OptionBinder.bind("t", Typed.class, List.of("--count", "1")))
                .hasMessage("t: missing required argument: <target>");
        assertThatThrownBy(() -> OptionBinder.bind("t", Typed.class, List.of("x")))
                .hasMessage("t: missing required option: --count");
    }

    @Test
    void anInvalidValueSaysWhatWasExpected() {
        assertThatThrownBy(() -> OptionBinder.bind("t", Typed.class, List.of("x", "--count", "1", "--size", "big")))
                .hasMessage("t: invalid value for --size: \"big\" (an integer or a size such as 10kb expected)");
        assertThatThrownBy(() -> OptionBinder.bind("t", Typed.class, List.of("x", "--count", "1", "--ratio", "?")))
                .hasMessage("t: invalid value for --ratio: \"?\" (a number expected)");
    }
}
