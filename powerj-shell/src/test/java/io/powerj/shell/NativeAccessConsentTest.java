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

/**
 * The consent to load a module that uses native code is given for the <em>content</em> of the module (review C-24): an
 * answer recorded for one version of a jar does not hold for another.
 */
class NativeAccessConsentTest {

    private static final String FINGERPRINT = "0123456789abcdef".repeat(4);
    private static final String OTHER = "fedcba9876543210".repeat(4);

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

    private Optional<Boolean> ask(NativeAccessConsent consent, String module) {
        return consent.consent(module, List.of(module), FINGERPRINT);
    }

    @Test
    void aMalformedConfigurationDoesNotPreventAskingTheUser() throws IOException {
        Files.writeString(file(), "x=C:\\users\\yves\n");
        assertThat(ask(consent(Optional.of(true)), "org.a")).contains(true);
        assertThat(asked).containsExactly("org.a");
    }

    @Test
    void aYesRecordedForThisContentIsNotAskedAgain() throws IOException {
        Files.writeString(file(), "modules.nativeAccess.io.powerj.fx=sha256:" + FINGERPRINT + "\n");
        assertThat(ask(consent(Optional.of(false)), "io.powerj.fx")).contains(true);
        assertThat(asked).isEmpty();
        assertThat(notices).isEmpty();
    }

    @Test
    void theRecordedDigestIsReadInAnyCase() throws IOException {
        Files.writeString(file(), "modules.nativeAccess.org.a = SHA256:" + FINGERPRINT.toUpperCase() + " \n");
        assertThat(ask(silent(), "org.a")).contains(true);
    }

    @Test
    void aYesRecordedForAnotherContentIsNotAYes() throws IOException {
        Files.writeString(file(), "modules.nativeAccess.org.a=sha256:" + OTHER + "\n");

        assertThat(ask(silent(), "org.a")).isEmpty();
        assertThat(notices).singleElement().asString().contains("org.a").contains("another version");
    }

    @Test
    void aYesRecordedForAnotherContentIsAskedAgainAndReplaced() throws IOException {
        Files.writeString(file(), "language=fr\nmodules.nativeAccess.org.a=sha256:" + OTHER + "\n");

        assertThat(ask(consent(Optional.of(true)), "org.a")).contains(true);

        assertThat(asked).containsExactly("org.a");
        assertThat(Files.readAllLines(file()))
                .containsExactly("language=fr", "modules.nativeAccess.org.a=sha256:" + FINGERPRINT);
        assertThat(notices).hasSize(2); // the old answer does not hold, then the new one is recorded
    }

    @Test
    void theOldYesThatWasNotTiedToAnyContentIsNotAYes() throws IOException {
        Files.writeString(file(), "modules.nativeAccess.org.a=true\n");

        assertThat(ask(silent(), "org.a")).isEmpty();
        assertThat(notices).singleElement().asString().contains("org.a");
    }

    @Test
    void aRecordedNoIsNotAskedAgainWhateverTheContent() throws IOException {
        Files.writeString(file(), "modules.nativeAccess.io.powerj.fx = FALSE \n");
        assertThat(ask(consent(Optional.of(true)), "io.powerj.fx")).contains(false);
        assertThat(asked).isEmpty();
        assertThat(notices).isEmpty();
    }

    @Test
    void theAnswersAreKeptModuleByModule() throws IOException {
        Files.writeString(file(), "modules.nativeAccess.org.a=sha256:" + FINGERPRINT
                + "\nmodules.nativeAccess.org.b=false\nmodules.nativeAccess.org.d=sha256:" + OTHER + "\n");
        var consent = silent();
        assertThat(ask(consent, "org.a")).contains(true);
        assertThat(ask(consent, "org.b")).contains(false);
        assertThat(ask(consent, "org.c")).isEmpty();
        assertThat(ask(consent, "org.d")).isEmpty();
    }

    @Test
    void withoutAnswerAndWithoutAnybodyToAskThereIsNoAnswer() {
        assertThat(ask(silent(), "org.a")).isEmpty();
        assertThat(notices).isEmpty();
        assertThat(file()).doesNotExist();
    }

    @Test
    void theUserIsAskedAndTheAnswerIsRecordedWithTheContent() throws IOException {
        var consent = consent(Optional.of(true));
        assertThat(consent.consent("org.a", List.of("javafx.base", "org.a"), FINGERPRINT)).contains(true);
        assertThat(asked).containsExactly("org.a");
        assertThat(layers).containsExactly(List.of("javafx.base", "org.a"));
        assertThat(Files.readAllLines(file()))
                .containsExactly("modules.nativeAccess.org.a=sha256:" + FINGERPRINT);
        assertThat(notices).containsExactly(
                "answer recorded: modules.nativeAccess.org.a=sha256:" + FINGERPRINT + " in " + file());
        // The next time, the file answers, for the same content only.
        assertThat(consent.consent("org.a", List.of(), FINGERPRINT)).contains(true);
        assertThat(asked).hasSize(1);
        assertThat(consent.consent("org.a", List.of(), OTHER)).contains(true); // asked again: the double says yes
        assertThat(asked).hasSize(2);
    }

    @Test
    void aRefusalIsRecordedToo() throws IOException {
        assertThat(ask(consent(Optional.of(false)), "org.a")).contains(false);
        assertThat(Files.readAllLines(file())).containsExactly("modules.nativeAccess.org.a=false");
    }

    @Test
    void noAnswerFromTheUserIsNotRecorded() {
        assertThat(ask(consent(Optional.empty()), "org.a")).isEmpty();
        assertThat(file()).doesNotExist();
        assertThat(notices).isEmpty();
    }

    @Test
    void somethingThatIsNotAnAnswerIsAskedAgainAndReplaced() throws IOException {
        Files.writeString(file(), "language=fr\nmodules.nativeAccess.org.a=maybe\n");
        assertThat(ask(consent(Optional.of(true)), "org.a")).contains(true);
        assertThat(Files.readAllLines(file()))
                .containsExactly("language=fr", "modules.nativeAccess.org.a=sha256:" + FINGERPRINT);
    }

    @Test
    void aDigestOfTheWrongShapeIsNotAnAnswer() throws IOException {
        for (String value : List.of("sha256:", "sha256:abc", "sha256:" + "g".repeat(64), "md5:" + FINGERPRINT,
                FINGERPRINT)) {
            Files.writeString(file(), "modules.nativeAccess.org.a=" + value + "\n");
            assertThat(ask(silent(), "org.a")).as(value).isEmpty();
        }
    }

    @Test
    void anUnreadableFileHasNoAnswer() throws IOException {
        Files.createDirectories(file().resolve("sub"));
        assertThat(ask(silent(), "org.a")).isEmpty();
    }

    @Test
    void theAnswerValuesForTheSessionEvenIfItCannotBeRecorded() throws IOException {
        Files.createDirectories(file().resolve("sub"));
        assertThat(ask(consent(Optional.of(true)), "org.a")).contains(true);
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
