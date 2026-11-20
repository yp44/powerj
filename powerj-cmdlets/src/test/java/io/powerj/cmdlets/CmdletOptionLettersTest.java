package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;

/**
 * One meaning per short option (specification FR-18b, audit v2 B-07): a reserved letter is the same option in every
 * built-in cmdlet, so that the habit of {@code rm -r} is right in {@code ls -r}, and never {@code join -r} for
 * {@code --right}.
 */
class CmdletOptionLettersTest {

    /** Reserved letter → its long form, wherever it is used. */
    static final Map<Character, String> RESERVED = Map.of(
            'r', "recurse",
            'n', "dry-run",
            'f', "force",
            'v', "verbose",
            'p', "parallel",
            'i', "ignore-case",
            'a', "all",
            'l', "lines");

    /** {@code cmdlet -x long}: the exceptions, each taken from the GNU program of the same name (FR-18b). */
    static final Set<String> EXCEPTIONS = Set.of(
            "grep -v invert",    // GNU grep -v; grep has no verbose mode
            "cat -n numbered",   // GNU cat -n; cat has no dry run
            "mkdir -p parents",  // GNU mkdir -p; mkdir has no parallel walk
            "ls -f filter");     // ls changes nothing: there is nothing to force

    /** {@code cmdlet -x long} for every short option of the built-in cmdlets. */
    static List<String> shortOptions() {
        List<String> found = new ArrayList<>();
        for (Cmdlet<?, ?, ?> cmdlet : new BuiltinCmdlets().cmdlets()) {
            String name = cmdlet.getClass().getAnnotation(CmdletInfo.class).name();
            for (RecordComponent component : params(cmdlet).getRecordComponents()) {
                Option option = component.getAnnotation(Option.class);
                if (option != null && option.shortName() != '\0') {
                    found.add(name + " -" + option.shortName() + " " + longName(component, option));
                }
            }
        }
        return found;
    }

    /** The record of parameters: the first type argument of {@code Cmdlet<Params, In, Out>}. */
    private static Class<?> params(Cmdlet<?, ?, ?> cmdlet) {
        for (Type type : cmdlet.getClass().getGenericInterfaces()) {
            if (type instanceof java.lang.reflect.ParameterizedType p && p.getRawType() == Cmdlet.class) {
                return (Class<?>) p.getActualTypeArguments()[0];
            }
        }
        throw new AssertionError("no Cmdlet<Params, …> on " + cmdlet.getClass());
    }

    /** As {@code OptionBinder} names it: the {@code longName}, or the component in kebab case. */
    static String longName(RecordComponent component, Option option) {
        if (!option.longName().isEmpty()) {
            return option.longName();
        }
        return component.getName().replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    /** Whether {@code cmdlet -x long} breaks the table. */
    static boolean breaksTheTable(String shortOption) {
        String[] parts = shortOption.split(" ");
        String expected = RESERVED.get(parts[1].charAt(1));
        return expected != null && !expected.equals(parts[2]) && !EXCEPTIONS.contains(shortOption);
    }

    @Test
    void aReservedLetterMeansTheSameInEveryBuiltInCmdlet() {
        List<String> options = shortOptions();

        assertThat(options).as("the scan found the options").contains("ls -r recurse", "rm -n dry-run", "grep -i ignore-case");
        assertThat(options).filteredOn(CmdletOptionLettersTest::breaksTheTable).isEmpty();
    }

    @Test
    void everyExceptionIsStillUsed() {
        assertThat(shortOptions()).containsAll(EXCEPTIONS);
    }

    @Test
    void joinAndGroupHaveNoShortFormForTheirRareOptions() {
        assertThat(shortOptions()).filteredOn(o -> o.startsWith("join ") || o.startsWith("group "))
                .noneMatch(o -> o.endsWith(" right") || o.endsWith(" left") || o.endsWith(" no-items"));
    }

    @Test
    void theRuleRejectsWhatItIsMeantTo() {
        assertThat(breaksTheTable("join -r right")).isTrue();
        assertThat(breaksTheTable("group -n no-items")).isTrue();
        assertThat(breaksTheTable("cp -v verbose")).isFalse();
        assertThat(breaksTheTable("grep -v invert")).isFalse();
        assertThat(breaksTheTable("to-csv -d delimiter")).isFalse();
    }
}
