package io.powerj.core.i18n;

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

import org.junit.jupiter.api.Test;

/**
 * The keys that the code builds instead of writing them whole exist in both languages (audit v2, B-09; FR-61).
 * {@code MessageBundlesTest} checks every key written as a literal; a key made of a prefix and a suffix escapes it, and a
 * missing one shows the key itself to the user. Each family is listed here with the place that builds it. The help of
 * the built-in commands ({@code help.builtin.<name>.*}) is checked against the list of the interpreter by
 * {@code CommandResolutionTest}.
 */
class DynamicMessageKeysTest {

    private static final Path I18N = Path.of("src/main/resources/io/powerj/core/i18n");
    private static final Path LANG = Path.of("src/main/resources/io/powerj/core/lang");
    private static final Path PARSER = Path.of("src/main/java/io/powerj/core/lang/ExpressionParser.java");

    private static Properties load(Path file) throws IOException {
        var properties = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(in);
        }
        return properties;
    }

    /** The keys among {@code keys} that one of the two bundles of {@code folder} does not define. */
    private static List<String> missing(Path folder, Set<String> keys) throws IOException {
        List<String> missing = new ArrayList<>();
        for (String language : List.of("en", "fr")) {
            Set<String> defined = load(folder.resolve("messages_" + language + ".properties")).stringPropertyNames();
            keys.stream().filter(key -> !defined.contains(key)).forEach(key -> missing.add(language + ": " + key));
        }
        return missing;
    }

    /** The first argument, a literal, of each call to {@code method} in {@code source}. */
    private static Set<String> literalKeys(Path source, String method) throws IOException {
        Matcher calls = Pattern.compile("\\b" + method + "\\(\"([\\w.]+)\"").matcher(Files.readString(source));
        Set<String> keys = new TreeSet<>();
        while (calls.find()) {
            keys.add(calls.group(1));
        }
        return keys;
    }

    @Test
    void theErrorsOfTheParserAtTheEndAndAtAPosition() throws IOException {
        // ExpressionParser.located: key + ".end" at the end of the line, key + ".at" before a token
        Set<String> bases = literalKeys(PARSER, "located");
        assertThat(bases).as("calls to located(\"…\")").contains("syntax.expected", "syntax.nameExpectedAfterDot");
        Set<String> keys = new TreeSet<>();
        bases.forEach(base -> keys.addAll(List.of(base + ".end", base + ".at")));
        assertThat(missing(LANG, keys)).isEmpty();
    }

    @Test
    void theErrorsOfTheParserInABlock() throws IOException {
        // ExpressionParser.inContext: key on a line, key + ".block" in a block
        Set<String> bases = literalKeys(PARSER, "inContext");
        assertThat(bases).as("calls to inContext(\"…\")").contains("syntax.missing", "syntax.incompleteExpression");
        Set<String> keys = new TreeSet<>();
        bases.forEach(base -> keys.addAll(List.of(base, base + ".block")));
        assertThat(missing(LANG, keys)).isEmpty();
    }

    @Test
    void theArityOfALambda() throws IOException {
        // CompiledBlock.apply: "block.arity." + one|other (declared) + "." + one|other (received)
        Set<String> keys = new TreeSet<>();
        for (String declared : List.of("one", "other")) {
            for (String received : List.of("one", "other")) {
                keys.add("block.arity." + declared + "." + received);
            }
        }
        assertThat(missing(I18N, keys)).isEmpty();
    }

    @Test
    void aMissingKeyIsSeen() throws IOException {
        assertThat(missing(I18N, Set.of("block.arity.none.one"))).containsExactly("en: block.arity.none.one",
                "fr: block.arity.none.one");
    }
}
