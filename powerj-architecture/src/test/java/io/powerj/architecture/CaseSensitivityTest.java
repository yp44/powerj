package io.powerj.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * [#130] The shell is case-sensitive, as Java is: what a user writes (the name of an option and its value, a property, a unit, a
 * literal, a variable, a constant) is compared exactly. This rule reads the production code of every module and fails when a
 * comparison that ignores the case appears in a class that is not in the list below. Each exception says why it is one; a new one
 * is added here, with its reason, and in ADR 0012.
 * <p>
 * It reads the sources and not the classes: {@code Pattern.CASE_INSENSITIVE} is a constant that the compiler writes into the call,
 * where a rule on the bytecode could not see it. The text of a comment is not looked at.
 */
class CaseSensitivityTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    /** The ways to compare without regard to case. */
    private static final Pattern IGNORING_CASE = Pattern.compile(
            "equalsIgnoreCase|compareToIgnoreCase|CASE_INSENSITIVE|UNICODE_CASE|regionMatches\\(\\s*true");

    /** The classes that compare without regard to case, and why. The key is the name of the file. */
    private static final Map<String, String> EXCEPTIONS = Map.ofEntries(
            Map.entry("Range.java", "range -i: the user asks to ignore the case of the text that is searched"),
            Map.entry("Grep.java", "grep -i: the user asks to ignore the case of the text that is searched"),
            Map.entry("SortComparator.java", "sort -i: the user asks to ignore the case of the keys"),
            Map.entry("Env.java", "the names of the variables ignore the case where the system does (Windows)"),
            Map.entry("Ps.java", "the users of Windows ignore the case, as the system does"),
            Map.entry("SessionState.java", "the environment of the session is a case-insensitive map on Windows, as the system's"),
            Map.entry("WindowsCommandLine.java", "Windows sorts the variables of an environment block without regard to case"),
            Map.entry("ModuleLoader.java", "Enable-Native-Access is read as the JDK reads a boolean of a manifest"),
            Map.entry("NativeAccessConsent.java", "the digits of a fingerprint are hexadecimal, and the answer to a question is no name"),
            Map.entry("FilterParser.java", "the filter box of the grid is a search typed in a window, not an identifier"),
            Map.entry("ValueOrder.java", "the order of the texts of the grid ignores the case, as the user reads them"));

    @Test
    void onlyTheClassesOfTheListCompareWithoutRegardToCase() throws IOException {
        Map<String, Set<String>> found = new TreeMap<>();
        Files.walkFileTree(ROOT, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                boolean production = !name.equals("target") && !name.equals(".git") && !name.equals("test")
                        && !name.equals("powerj-bench") && !name.equals("powerj-testing");
                return production ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String relative = ROOT.relativize(file).toString().replace('\\', '/');
                if (file.toString().endsWith(".java") && relative.contains("/src/main/java/")) {
                    var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        String code = line.strip();
                        boolean comment = code.startsWith("*") || code.startsWith("//") || code.startsWith("/*");
                        if (!comment && IGNORING_CASE.matcher(line).find()) {
                            found.computeIfAbsent(file.getFileName().toString(), k -> new TreeSet<>())
                                    .add(relative + ":" + (i + 1));
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        Map<String, Set<String>> unexpected = new TreeMap<>(found);
        unexpected.keySet().removeAll(EXCEPTIONS.keySet());
        assertThat(unexpected).as("a comparison without regard to case, in a class that is not in the list of the exceptions "
                + "(CaseSensitivityTest, ADR 0012): compare exactly, or add the class with its reason").isEmpty();
    }

    @Test
    void everyExceptionStillHasAComparisonThatIgnoresTheCase() throws IOException {
        Set<String> sources = new TreeSet<>();
        Files.walkFileTree(ROOT, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return name.equals("target") || name.equals(".git") ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (file.toString().endsWith(".java") && ROOT.relativize(file).toString().replace('\\', '/').contains("/src/main/java/")
                        && EXCEPTIONS.containsKey(file.getFileName().toString())
                        && IGNORING_CASE.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                    sources.add(file.getFileName().toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });

        assertThat(sources).as("an exception that no longer compares without regard to case: remove it from the list")
                .containsExactlyInAnyOrderElementsOf(EXCEPTIONS.keySet());
    }
}
