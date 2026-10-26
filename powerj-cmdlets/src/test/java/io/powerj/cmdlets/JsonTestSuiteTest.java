package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link JsonParser} against the JSONTestSuite of Nicolas Seriot (MIT, in {@code src/test/resources/jsontestsuite}, see its
 * README): what RFC 8259 says is valid is accepted, what it says is not is rejected with a position, and neither ever
 * crashes. What the RFC leaves open ({@code i_} files) is pinned file by file: a change of behavior is a decision.
 */
class JsonTestSuiteTest {

    private static final Path DIR = directory();

    /** What the suite calls implementation-defined, and PowerJ rejects; it accepts the other {@code i_} files. */
    private static final Set<String> REJECTED = Set.of(
            // a number that does not fit a double is an error, not Infinity (the underflows are 0, the big integers fit)
            "i_number_huge_exp.json", "i_number_neg_int_huge_exp.json", "i_number_pos_double_huge_exp.json",
            "i_number_real_neg_overflow.json", "i_number_real_pos_overflow.json",
            // text in UTF-16, read as UTF-8 it is not JSON; the byte order mark is skipped by the splitter, not here
            "i_string_UTF-16LE_with_BOM.json", "i_string_utf16BE_no_BOM.json", "i_string_utf16LE_no_BOM.json",
            "i_structure_UTF-8_BOM_empty_object.json");

    private static Path directory() {
        try {
            return Path.of(JsonTestSuiteTest.class.getResource("/jsontestsuite").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static Stream<Path> files(String prefix) throws IOException {
        try (Stream<Path> all = Files.list(DIR)) {
            return all.filter(file -> file.getFileName().toString().startsWith(prefix)
                    && file.getFileName().toString().endsWith(".json")).sorted().toList().stream();
        }
    }

    static Stream<Path> valid() throws IOException {
        return files("y_");
    }

    static Stream<Path> invalid() throws IOException {
        return files("n_");
    }

    static Stream<Path> open() throws IOException {
        return files("i_");
    }

    /** The text as the shell reads it: bytes that are not UTF-8 become U+FFFD, they do not stop the reading. */
    private static String text(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("valid")
    void aValidTextIsAccepted(Path file) throws IOException {
        JsonParser.parse(text(file));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalid")
    void anInvalidTextIsRejectedWithAPosition(Path file) throws IOException {
        String text = text(file);

        assertThatThrownBy(() -> JsonParser.parse(text)).isInstanceOfSatisfying(JsonParser.JsonException.class, e -> {
            assertThat(e.line()).isGreaterThanOrEqualTo(1);
            assertThat(e.column()).isGreaterThanOrEqualTo(1);
            assertThat(e.getMessage()).isNotBlank();
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("open")
    void whatTheSuiteLeavesOpenIsPinned(Path file) throws IOException {
        String text = text(file);
        boolean rejected = REJECTED.contains(file.getFileName().toString());

        if (rejected) {
            assertThatThrownBy(() -> JsonParser.parse(text)).isInstanceOf(JsonParser.JsonException.class);
        } else {
            JsonParser.parse(text);
        }
    }

    @Test
    void theCopyIsWholeAndEveryPinNamesAFile() throws IOException {
        assertThat(valid().count()).isEqualTo(95);
        assertThat(invalid().count()).isEqualTo(186); // 188 files, two of them built below
        assertThat(open().count()).isEqualTo(35);
        List<String> names = open().map(file -> file.getFileName().toString()).toList();
        assertThat(names).containsAll(REJECTED);
    }

    @Test
    void theTwoFilesThatAreOnlyBracketsAreBuiltHere() {
        String openingArrays = "[".repeat(100_000);
        String openArrayObject = "[{\"\":".repeat(50_000);

        assertThatThrownBy(() -> JsonParser.parse(openingArrays)).isInstanceOf(JsonParser.JsonException.class)
                .hasMessageContaining("deep");
        assertThatThrownBy(() -> JsonParser.parse(openArrayObject)).isInstanceOf(JsonParser.JsonException.class)
                .hasMessageContaining("deep");
    }

    @Test
    void aByteOrderMarkAtTheStartIsSkippedByTheSplitterThatReadsTheInput() throws IOException {
        Path file = DIR.resolve("i_structure_UTF-8_BOM_empty_object.json");

        var results = new JsonSplitter().feed(text(file));

        assertThat(results).singleElement().isInstanceOf(JsonSplitter.Parsed.class);
    }
}
