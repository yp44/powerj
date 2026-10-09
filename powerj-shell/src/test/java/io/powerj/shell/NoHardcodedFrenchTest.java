package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * No French text left in the string literals of the main sources (FR-61): user-visible texts come from the
 * {@code messages_*.properties} bundles. Comments are not checked.
 */
class NoHardcodedFrenchTest {

    /** French accented letters and guillemets, upper or lower case. */
    private static final String FRENCH_CHARACTERS = "àâçéèêëîïôûùüÿœ«»";

    /**
     * Literals allowed to contain French characters, as {@code path/relative/to/root:literal}, each with its
     * justification: only internal texts never shown to the user (rules: control flow, input syntax).
     */
    /** Justified exceptions, as {@code path:literal}; none today. */
    private static final Set<String> ALLOWED = Set.of();

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
