package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeAccessConsentTest {

    @TempDir
    Path tmp;

    private final List<String> asked = new ArrayList<>();
    private final List<List<String>> layers = new ArrayList<>();
    private final List<String> notices = new ArrayList<>();

    private Path file() {
        return tmp.resolve("config.properties");
    }

    private NativeAccessConsent consent(Optional<Boolean> answer) {
        return new NativeAccessConsent(new ConfigFile(file()), (module, layer) -> {
            asked.add(module);
            layers.add(layer);
            return answer;
        }, notices::add);
    }

    private NativeAccessConsent silent() {
        return new NativeAccessConsent(new ConfigFile(file()), null, notices::add);
    }

    @Test
    void aMalformedConfigurationDoesNotPreventAskingTheUser() throws IOException {
        Files.writeString(file(), "x=C:\\users\\yves\n");
        assertThat(consent(Optional.of(true)).consent("org.a", List.of("org.a"))).contains(true);
        assertThat(asked).containsExactly("org.a");
    }

    @Test
    void aRecordedYesIsNotAskedAgain() throws IOException {
        Files.writeString(file(), "modules.nativeAccess.io.powerj.fx=true\n");
        assertThat(consent(Optional.of(false)).consent("io.powerj.fx", List.of("a"))).contains(true);
        assertThat(asked).isEmpty();
    }

    @Test
    void aRecordedNoIsNotAskedAgain() throws IOException {
        Files.writeString(file(), "modules.nativeAccess.io.powerj.fx = FALSE \n");
        assertThat(consent(Optional.of(true)).consent("io.powerj.fx", List.of("a"))).contains(false);
        assertThat(asked).isEmpty();
    }

    @Test
    void theAnswersAreKeptModuleByModule() throws IOException {
        Files.writeString(file(), "modules.nativeAccess.org.a=true\nmodules.nativeAccess.org.b=false\n");
        var consent = silent();
        assertThat(consent.consent("org.a", List.of())).contains(true);
        assertThat(consent.consent("org.b", List.of())).contains(false);
        assertThat(consent.consent("org.c", List.of())).isEmpty();
    }

    @Test
    void withoutAnswerAndWithoutAnybodyToAskThereIsNoAnswer() {
        assertThat(silent().consent("org.a", List.of("org.a"))).isEmpty();
        assertThat(notices).isEmpty();
        assertThat(file()).doesNotExist();
    }

    @Test
    void theUserIsAskedAndTheAnswerIsRecorded() throws IOException {
        var consent = consent(Optional.of(true));
        assertThat(consent.consent("org.a", List.of("javafx.base", "org.a"))).contains(true);
        assertThat(asked).containsExactly("org.a");
        assertThat(layers).containsExactly(List.of("javafx.base", "org.a"));
        assertThat(Files.readAllLines(file())).containsExactly("modules.nativeAccess.org.a=true");
        assertThat(notices).containsExactly("answer recorded: modules.nativeAccess.org.a=true in " + file());
        // The next time, the file answers.
        assertThat(consent.consent("org.a", List.of())).contains(true);
        assertThat(asked).hasSize(1);
    }

    @Test
    void aRefusalIsRecordedToo() throws IOException {
        assertThat(consent(Optional.of(false)).consent("org.a", List.of())).contains(false);
        assertThat(Files.readAllLines(file())).containsExactly("modules.nativeAccess.org.a=false");
    }

    @Test
    void noAnswerFromTheUserIsNotRecorded() {
        assertThat(consent(Optional.empty()).consent("org.a", List.of())).isEmpty();
        assertThat(file()).doesNotExist();
        assertThat(notices).isEmpty();
    }

    @Test
    void somethingThatIsNotAnAnswerIsAskedAgainAndReplaced() throws IOException {
        Files.writeString(file(), "language=fr\nmodules.nativeAccess.org.a=maybe\n");
        assertThat(consent(Optional.of(true)).consent("org.a", List.of())).contains(true);
        assertThat(Files.readAllLines(file())).containsExactly("language=fr", "modules.nativeAccess.org.a=true");
    }

    @Test
    void anUnreadableFileHasNoAnswer() throws IOException {
        Files.createDirectories(file().resolve("sub"));
        assertThat(silent().consent("org.a", List.of())).isEmpty();
    }

    @Test
    void theAnswerValuesForTheSessionEvenIfItCannotBeRecorded() throws IOException {
        Files.createDirectories(file().resolve("sub"));
        assertThat(consent(Optional.of(true)).consent("org.a", List.of())).contains(true);
        assertThat(notices).singleElement().asString().startsWith("cannot record the answer in " + file());
    }

    @Test
    void yesIsYesInEnglishAndFrench() {
        for (String yes : List.of("y", "Y", "yes", "YES", " oui ", "o", "O", "Oui")) {
            assertThat(NativeAccessConsent.isYes(yes)).as(yes).isTrue();
        }
        for (String no : List.of("n", "no", "non", "", "  ", "yep", "ok", "true")) {
            assertThat(NativeAccessConsent.isYes(no)).as(no).isFalse();
        }
        assertThat(NativeAccessConsent.isYes(null)).isFalse();
    }
}
