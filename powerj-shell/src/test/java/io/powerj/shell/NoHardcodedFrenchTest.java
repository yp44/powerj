package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * No French text left in the string literals of the main sources (FR-61): user-visible texts come from the
 * {@code messages_*.properties} bundles. Comments of the sources are not checked.
 * <p>
 * The build files, the scripts of {@code ci/} and the workflows are in English too, comments included (CONTRIBUTING,
 * "Language", audit v2 C-08): their French is found by its accents and by the words that are not English, because a
 * sentence of the build does not always have an accent ({@code Construit l'application autonome}).
 */
class NoHardcodedFrenchTest {

    /** French accented letters and guillemets, upper or lower case. */
    private static final String FRENCH_CHARACTERS = "àâçéèêëîïôûùüÿœ«»";

    /**
     * Literals allowed to contain French characters, as {@code path/relative/to/root:literal}, each with its
     * justification: only internal texts never shown to the user (rules: control flow, input syntax). None today.
     */
    private static final Set<String> ALLOWED = Set.of();

    /**
     * Words of French that are not words of English, whole and whatever the case, and the contractions {@code l'} and
     * {@code d'} before a letter. Short ambiguous ones ({@code des}, {@code sans}, {@code du}: a cipher, a font, a command) are
     * left out on purpose.
     */
    private static final Pattern FRENCH_WORDS = Pattern.compile(
            "\\b(avec|pour|dans|une|les|sont|construit|exemples?|lanceur|installeur|autonome|fichiers?|dossiers?|erreurs?"
                    + "|commandes?|inconnu(e|s|es)?|profils?)\\b|\\b[ld]'\\p{L}",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * French that is test data and not a text of the project, by file: the lines that contain the marker ({@code ""}: the
     * whole file). The smoke test of the languages checks the French messages of the packaged launcher.
     */
    private static final Map<String, String> FRENCH_TEST_DATA = Map.of(
            "ci/smoke/languages.sh", "",
            ".github/workflows/ci.yml", "commande inconnue : nope"); // the same check, inline, in the Windows job

    /** A string, text block or char literal of a source file. */
    record Literal(int line, String value) { }

    @Test
    void mainSourcesHaveNoFrenchLiterals() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : javaSources()) {
            String relative = MessageBundlesTest.ROOT.relativize(file).toString().replace('\\', '/');
            for (Literal literal : literals(Files.readString(file, StandardCharsets.UTF_8))) {
                if (isFrench(literal.value()) && !ALLOWED.contains(relative + ":" + literal.value())) {
                    found.add(relative + ":" + literal.line() + ": \"" + literal.value() + "\"");
                }
            }
        }
        assertThat(found).as("French texts in literals: move them to messages_en/fr.properties").isEmpty();
    }

    @Test
    void buildFilesScriptsAndWorkflowsHaveNoFrench() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : buildFiles()) {
            String relative = MessageBundlesTest.ROOT.relativize(file).toString().replace('\\', '/');
            String testData = FRENCH_TEST_DATA.get(relative);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                boolean allowed = testData != null && (testData.isEmpty() || line.contains(testData));
                if (isFrenchLine(line) && !allowed) {
                    found.add(relative + ":" + (i + 1) + ": " + line.strip());
                }
            }
        }
        assertThat(found).as("French in build files, scripts or workflows: write it in English").isEmpty();
    }

    @Test
    void theDetectorOfFrenchLinesFindsFrenchWithoutAccentsAndLeavesEnglishAlone() {
        assertThat(isFrenchLine("<name>PowerJ Exemple : module greet</name>")).isTrue();
        assertThat(isFrenchLine("Construit l'application autonome (runtime jlink + lanceur jpackage) avec le profil")).isTrue();
        assertThat(isFrenchLine("# the file is not écrit here")).isTrue();
        assertThat(isFrenchLine("expect \"$WORK/lang.txt\" 'commande inconnue : nope'")).isTrue();
        assertThat(isFrenchLine("<description>Builds the application image (jlink runtime and jpackage launcher) with the "
                + "\"dist\" profile</description>")).isFalse();
        assertThat(isFrenchLine("# jpackage makes an application bundle on macOS: the launcher is inside it.")).isFalse();
        assertThat(isFrenchLine("du -sh target; I'd rather not; the user's file; DES and a sans-serif font")).isFalse();
    }

    @Test
    void buildFilesAreFound() throws IOException {
        assertThat(buildFiles()).extracting(p -> MessageBundlesTest.ROOT.relativize(p).toString().replace('\\', '/'))
                .contains("pom.xml", "powerj-dist/pom.xml", "examples/greet/pom.xml", "ci/smoke/fx.sh",
                        "ci/smoke/languages.sh", ".github/workflows/ci.yml")
                .noneMatch(path -> path.contains("/target/"));
    }

    @Test
    void sourcesAreFound() throws IOException {
        assertThat(javaSources()).extracting(p -> p.getFileName().toString())
                .contains("Main.java", "Interpreter.java", "Language.java", "Ls.java");
    }

    @Test
    void tokenizerSkipsCommentsAndReadsAllLiteralForms() {
        String source = """
                // "commentée"
                /* "bloc é" */ /** "doc à" */
                class A {
                    String s = "a\\"b" + 'c' + '\\'' + '"' + "// pas un commentaire";
                    String t = \"""
                        texte é
                        \""";
                    String u = "\\u00e9";
                }
                """;
        List<Literal> literals = literals(source);
        assertThat(literals).extracting(Literal::value)
                .containsExactly("a\\\"b", "c", "\\'", "\"", "// pas un commentaire", "        texte é\n        ", "é");
        assertThat(literals).extracting(Literal::line).containsExactly(4, 4, 4, 4, 4, 5, 8);
        assertThat(literals).filteredOn(l -> isFrench(l.value())).hasSize(2);
    }

    static boolean isFrenchLine(String line) {
        return isFrench(line) || FRENCH_WORDS.matcher(line).find();
    }

    /** The poms, the scripts of {@code ci/} and the workflows, without the build output. */
    private static List<Path> buildFiles() throws IOException {
        Path root = MessageBundlesTest.ROOT;
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).filter(file -> {
                String path = root.relativize(file).toString().replace('\\', '/');
                boolean build = path.endsWith("pom.xml") || path.startsWith("ci/") && path.endsWith(".sh")
                        || path.startsWith(".github/workflows/") && path.endsWith(".yml");
                return build && !("/" + path).contains("/target/") && !path.startsWith(".git/");
            }).sorted().toList();
        }
    }

    static boolean isFrench(String text) {
        return text.toLowerCase(java.util.Locale.ROOT).chars().anyMatch(c -> FRENCH_CHARACTERS.indexOf(c) >= 0);
    }

    private static List<Path> javaSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        Path root = MessageBundlesTest.ROOT;
        for (Path parent : List.of(root, root.resolve("examples"))) {
            if (!Files.isDirectory(parent)) {
                continue;
            }
            try (Stream<Path> modules = Files.list(parent)) {
                for (Path java : modules.map(m -> m.resolve("src/main/java")).filter(Files::isDirectory).sorted()
                        .toList()) {
                    try (Stream<Path> files = Files.walk(java)) {
                        files.filter(f -> f.toString().endsWith(".java")).sorted().forEach(sources::add);
                    }
                }
            }
        }
        return sources;
    }

    /**
     * String literals (raw content between the delimiters, Unicode escapes {@code \}{@code uXXXX} decoded), text
     * blocks and char literals of a Java source, comments skipped.
     */
    static List<Literal> literals(String source) {
        List<Literal> literals = new ArrayList<>();
        int line = 1;
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '\n') {
                line++;
                i++;
            } else if (source.startsWith("//", i)) {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                line += count(source, i, end);
                i = end;
            } else if (source.startsWith("\"\"\"", i)) {
                int start = source.indexOf('\n', i + 3) + 1;
                int end = start;
                while (end < n && !source.startsWith("\"\"\"", end)) {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(end, n);
                literals.add(new Literal(line, unescapeUnicode(source.substring(start, end))));
                int after = Math.min(end + 3, n);
                line += count(source, i, after);
                i = after;
            } else if (c == '"' || c == '\'') {
                int end = i + 1;
                while (end < n && source.charAt(end) != c && source.charAt(end) != '\n') {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(end, n);
                literals.add(new Literal(line, unescapeUnicode(source.substring(i + 1, end))));
                i = end + 1;
            } else {
                i++;
            }
        }
        return literals;
    }

    private static int count(String source, int from, int to) {
        int lines = 0;
        for (int k = from; k < to && k < source.length(); k++) {
            if (source.charAt(k) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static String unescapeUnicode(String text) {
        var result = new StringBuilder(text.length());
        for (int k = 0; k < text.length(); k++) {
            if (text.startsWith("\\u", k)) {
                int hex = k + 2;
                while (hex < text.length() && text.charAt(hex) == 'u') {
                    hex++;
                }
                if (hex + 4 <= text.length()) {
                    try {
                        result.append((char) Integer.parseInt(text.substring(hex, hex + 4), 16));
                        k = hex + 3;
                        continue;
                    } catch (NumberFormatException _) {
                        // not a Unicode escape: kept as is
                    }
                }
            }
            result.append(text.charAt(k));
        }
        return result.toString();
    }
}
