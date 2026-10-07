package io.powerj.core.exec;

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
        assertThat(bind("--RECURSE").recurse()).isTrue();
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
    void positionalListCollectsTheRestAndDoubleDashEndsOptions() {
        assertThat(bind("a", "-r", "b").paths()).containsExactly("a", "b");
        assertThat(bind("--", "-r", "x").paths()).containsExactly("-r", "x");
        assertThat(bind("-5").paths()).containsExactly("-5");
    }

    @Test
    void unambiguousAbbreviation() {
        assertThat(bind("--rec").recurse()).isTrue();
        assertThat(bind("--max", "2").maxDepth()).isEqualTo(2);
    }

    @Test
    void errorsAreExplicit() {
        assertThatThrownBy(() -> bind("--recurce"))
                .hasMessage("t : option inconnue --recurce, vouliez-vous dire --recurse ?");
        assertThatThrownBy(() -> bind("-z")).hasMessage("t : option inconnue -z");
        assertThatThrownBy(() -> bind("--filter")).hasMessage("t : valeur attendue après --filter");
        assertThatThrownBy(() -> bind("--max-depth", "trois"))
                .hasMessage("t : valeur invalide pour --max-depth : 'trois'");
        assertThatThrownBy(() -> bind("--mode", "slow")).hasMessageContaining("--mode accepte [fast, safe]");
        assertThatThrownBy(() -> bind("--m", "x")).hasMessageContaining("option ambiguë --m");
    }

    @Test
    void mandatoryAndPositionals() {
        assertThatThrownBy(() -> OptionBinder.bind("t", Required.class, List.of("x")))
                .hasMessage("t : option obligatoire manquante : --name");
        var r = OptionBinder.bind("t", Required.class, List.of("un", "--name", "n", "deux"));
        assertThat(r).isEqualTo(new Required("n", "un", "deux"));
        assertThatThrownBy(() -> OptionBinder.bind("t", Required.class, List.of("--name", "n", "1", "2", "3")))
                .hasMessage("t : argument inattendu : 3");
    }
}
