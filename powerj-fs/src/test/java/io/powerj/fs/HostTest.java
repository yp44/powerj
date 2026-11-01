package io.powerj.fs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The operating system, read in one place (#86): {@code os.name} says which system this is, and the system says whether
 * the names of files ignore case. Every system is tried here by its name, whatever the machine of the test.
 */
class HostTest {

    @ParameterizedTest(name = "{0}: windows={1}, mac={2}, names ignore case={3}")
    @CsvSource({
            "Windows 11,           true,  false, true",
            "Windows 10,           true,  false, true",
            "Windows Server 2022,  true,  false, true",
            "windows 11,           true,  false, true",
            "Mac OS X,             false, true,  true",
            "macOS,                false, true,  true",
            "Linux,                false, false, false",
            "FreeBSD,              false, false, false",
            "SunOS,                false, false, false",
            "AIX,                  false, false, false",
    })
    void eachSystemIsKnownByItsName(String osName, boolean windows, boolean mac, boolean namesIgnoreCase) {
        assertThat(Host.windows(osName)).as("windows").isEqualTo(windows);
        assertThat(Host.mac(osName)).as("mac").isEqualTo(mac);
        assertThat(Host.namesIgnoreCase(osName)).as("names ignore case").isEqualTo(namesIgnoreCase);
    }

    @Test
    void anUnknownNameIsNeitherWindowsNorMacAndCaseCounts() {
        assertThat(Host.windows("")).isFalse();
        assertThat(Host.mac("")).isFalse();
        assertThat(Host.namesIgnoreCase("")).isFalse();
        assertThat(Host.windows(null)).isFalse();
        assertThat(Host.namesIgnoreCase(null)).isFalse();
    }

    @Test
    void theRealSystemAnswersLikeItsName() {
        String name = System.getProperty("os.name", "");
        assertThat(Host.windows()).isEqualTo(Host.windows(name));
        assertThat(Host.mac()).isEqualTo(Host.mac(name));
        assertThat(Host.namesIgnoreCase()).isEqualTo(Host.namesIgnoreCase(name));
    }

    // --- the home folder of a session ---

    private static final Path FALLBACK = Path.of("fallback-home").toAbsolutePath();

    private static Map<String, String> env(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void homeIsTheHomeVariableThenUserProfileThenTheFallback() {
        Path home = Path.of("some-home").toAbsolutePath();
        Path profile = Path.of("some-profile").toAbsolutePath();

        assertThat(Host.home(env("HOME", home.toString(), "USERPROFILE", profile.toString()), FALLBACK)).isEqualTo(home);
        assertThat(Host.home(env("USERPROFILE", profile.toString()), FALLBACK)).isEqualTo(profile);
        assertThat(Host.home(env(), FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    void aBlankVariableIsSkipped() {
        Path profile = Path.of("some-profile").toAbsolutePath();

        assertThat(Host.home(env("HOME", "   ", "USERPROFILE", profile.toString()), FALLBACK)).isEqualTo(profile);
        assertThat(Host.home(env("HOME", "", "USERPROFILE", ""), FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    void aValueThatIsNotAPathIsSkippedToo() {
        Path profile = Path.of("some-profile").toAbsolutePath();
        String notAPath = "bad\0path"; // no file system accepts a NUL character in a path

        assertThat(Host.home(env("HOME", notAPath, "USERPROFILE", profile.toString()), FALLBACK)).isEqualTo(profile);
        assertThat(Host.home(env("HOME", notAPath), FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    void theHomeIsAbsoluteAndNormalizedWhateverIsWritten() {
        Path home = Path.of("some-home").toAbsolutePath();

        assertThat(Host.home(env("HOME", "  " + home + "  "), FALLBACK)).isEqualTo(home);
        assertThat(Host.home(env("HOME", home + "/sub/.."), FALLBACK)).isEqualTo(home);
        assertThat(Host.home(env("HOME", "relative-home"), FALLBACK)).isEqualTo(Path.of("relative-home").toAbsolutePath());
        assertThat(Host.home(env(), Path.of("relative-fallback"))).isEqualTo(Path.of("relative-fallback").toAbsolutePath());
    }
}
