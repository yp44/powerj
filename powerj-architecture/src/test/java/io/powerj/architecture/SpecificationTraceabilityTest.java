package io.powerj.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The specification and the code say the same thing about which requirements exist (review C-35): every requirement
 * of the specification is cited by the code, a test or a smoke script, unless the specification says it was withdrawn,
 * and no citation points at a requirement the specification does not have. A requirement that was specified and never
 * built (as {@code native.prefer} was) can no longer go unnoticed.
 * <p>
 * A requirement is <em>defined</em> by a line of the specification that starts with its identifier, as a heading
 * ({@code #### FR-nn — …}) or in bold ({@code **FR-nn — …**}). A definition whose text says {@code Withdrawn.} is not
 * expected in the code. A requirement that is only partly built says so in its text ({@code Status: partial}); it is
 * still cited by the code that does the part that exists.
 */
class SpecificationTraceabilityTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    /** Its examples name identifiers on purpose: it does not count as a citation. */
    private static final Path THIS_FILE = ROOT.resolve(
            "powerj-architecture/src/test/java/io/powerj/architecture/SpecificationTraceabilityTest.java");
    private static final Pattern ID = Pattern.compile("FR-\\d+[a-z]?");
    private static final Pattern DEFINITION = Pattern.compile("^(?:#{3,5} |\\*\\*)(FR-\\d+[a-z]?)\\b(.*)$");
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(".java", ".properties", ".sh");

    /** Requirement identifier to "withdrawn?", for the definitions found in the text of the specification. */
    static Map<String, Boolean> definitions(List<String> specification) {
        Map<String, Boolean> found = new TreeMap<>();
        for (String line : specification) {
            Matcher m = DEFINITION.matcher(line);
            if (m.matches()) {
                found.putIfAbsent(m.group(1), m.group(2).contains("Withdrawn."));
            }
        }
        return found;
    }

    /** Every identifier that appears in {@code text}. */
    static Set<String> identifiers(String text) {
        Set<String> found = new TreeSet<>();
        Matcher m = ID.matcher(text);
        while (m.find()) {
            found.add(m.group());
        }
        return found;
    }

    private static List<String> specification() throws IOException {
        return Files.readAllLines(ROOT.resolve("docs/SPECIFICATION.md"), StandardCharsets.UTF_8);
    }

    /** The identifiers cited by the sources of every module, the examples and the smoke scripts of the CI. */
    private static Set<String> citedByTheSources() throws IOException {
        Set<String> cited = new TreeSet<>();
        Files.walkFileTree(ROOT, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                return name.equals("target") || name.equals(".git") || name.equals("docs")
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                boolean source = SOURCE_EXTENSIONS.stream().anyMatch(name::endsWith)
                        && !file.equals(THIS_FILE);
                if (source) {
                    cited.addAll(identifiers(Files.readString(file, StandardCharsets.UTF_8)));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return cited;
    }

    @Test
    void everyRequirementOfTheSpecificationIsCitedByTheCodeUnlessWithdrawn() throws IOException {
        Map<String, Boolean> defined = definitions(specification());
        Set<String> cited = citedByTheSources();

        List<String> never = new ArrayList<>();
        defined.forEach((id, withdrawn) -> {
            if (!withdrawn && !cited.contains(id)) {
                never.add(id);
            }
        });

        assertThat(defined).as("the specification defines requirements").hasSizeGreaterThan(50);
        assertThat(never).as("specified, and cited by no source, test or smoke script (built? then cite it where it is;"
                + " not built? then say so in the specification)").isEmpty();
    }

    @Test
    void noSourceCitesARequirementTheSpecificationDoesNotHaveOrHasWithdrawn() throws IOException {
        Map<String, Boolean> defined = definitions(specification());

        List<String> unknown = new ArrayList<>();
        for (String id : citedByTheSources()) {
            if (!defined.containsKey(id) || defined.get(id)) {
                unknown.add(id);
            }
        }

        assertThat(unknown).as("cited in the code, but not a live requirement of the specification").isEmpty();
    }

    @Test
    void theSpecificationOnlyMentionsRequirementsItDefines() throws IOException {
        List<String> lines = specification();
        Map<String, Boolean> defined = definitions(lines);

        Set<String> dangling = new TreeSet<>(identifiers(String.join("\n", lines)));
        dangling.removeAll(defined.keySet());

        assertThat(dangling).as("mentioned in the specification, never defined").isEmpty();
    }

    @Test
    void aDefinitionIsALineThatStartsWithTheIdentifierAsAHeadingOrInBold() {
        var lines = List.of(
                "#### FR-35 — `ls`: list files",
                "**FR-04b — Directory navigation.** Commands built into the REPL",
                "see FR-99 for the rest",
                "- the **FR-98 — not at the start** of the line",
                "**FR-16 — Withdrawn.** It was never built");

        Map<String, Boolean> found = definitions(lines);

        assertThat(found).containsOnlyKeys("FR-35", "FR-04b", "FR-16");
        assertThat(found).containsEntry("FR-35", false).containsEntry("FR-04b", false).containsEntry("FR-16", true);
    }

    @Test
    void anIdentifierIsTheDigitsAndAnOptionalLowerCaseLetter() {
        assertThat(identifiers("FR-01 to FR-11, FR-33b and FR-04d.; FR-X, fr-12, FR-"))
                .containsExactly("FR-01", "FR-04d", "FR-11", "FR-33b");
    }
}
