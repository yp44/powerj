package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every {@code messages_en.properties} of the repository has a {@code messages_fr.properties} sibling with the
 * same keys and the same placeholders, and no empty value (FR-61).
 */
class MessageBundlesTest {

    /** Repository root: surefire runs in {@code powerj-shell}. */
    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    static List<Path> englishBundles() throws IOException {
        List<Path> bundles = new ArrayList<>();
        for (Path resources : resourceDirs()) {
            try (Stream<Path> files = Files.walk(resources)) {
                files.filter(f -> f.getFileName().toString().equals("messages_en.properties")).forEach(bundles::add);
            }
        }
        return bundles;
    }

    private static List<Path> resourceDirs() throws IOException {
        List<Path> dirs = new ArrayList<>();
        for (Path parent : List.of(ROOT, ROOT.resolve("examples"))) {
            if (!Files.isDirectory(parent)) {
                continue;
            }
            try (Stream<Path> modules = Files.list(parent)) {
                modules.map(m -> m.resolve("src/main/resources")).filter(Files::isDirectory).sorted()
                        .forEach(dirs::add);
            }
        }
        return dirs;
    }

    private static Properties load(Path file) throws IOException {
        var props = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(in);
        }
        return props;
    }

    private static Set<String> placeholders(String value) {
        Set<String> found = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(value);
        while (m.find()) {
            found.add(m.group());
        }
        return found;
    }

    @Test
    void bundlesOfEveryModuleAreFound() throws IOException {
        assertThat(englishBundles()).extracting(p -> ROOT.relativize(p).toString().replace('\\', '/'))
                .contains("powerj-api/src/main/resources/io/powerj/api/messages_en.properties",
                        "powerj-shell/src/main/resources/io/powerj/shell/messages_en.properties",
                        "powerj-cmdlets/src/main/resources/io/powerj/cmdlets/messages_en.properties",
                        "powerj-core/src/main/resources/io/powerj/core/exec/messages_en.properties",
                        "powerj-core/src/main/resources/io/powerj/core/lang/messages_en.properties",
                        "examples/greet/src/main/resources/com/example/greet/messages_en.properties");
    }

    @Test
    void englishAndFrenchBundlesMatch() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path english : englishBundles()) {
            Path french = english.resolveSibling("messages_fr.properties");
            String name = ROOT.relativize(english.getParent()).toString();
            if (!Files.isRegularFile(french)) {
                problems.add(name + ": messages_fr.properties missing");
                continue;
            }
            Properties en = load(english);
            Properties fr = load(french);
            Set<String> keys = new TreeSet<>(en.stringPropertyNames());
            keys.addAll(fr.stringPropertyNames());
            for (String key : keys) {
                String enValue = en.getProperty(key);
                String frValue = fr.getProperty(key);
                if (enValue == null || frValue == null) {
                    problems.add(name + ": key " + key + " missing in " + (enValue == null ? "en" : "fr"));
                    continue;
                }
                if (enValue.isBlank() || frValue.isBlank()) {
                    problems.add(name + ": key " + key + " has an empty value");
                }
                if (!placeholders(enValue).equals(placeholders(frValue))) {
                    problems.add(name + ": key " + key + " placeholders differ: en " + placeholders(enValue)
                            + ", fr " + placeholders(frValue));
                }
            }
        }
        assertThat(problems).isEmpty();
    }
}
