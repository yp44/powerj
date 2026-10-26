package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;

import org.junit.jupiter.api.Test;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletInfo;

/** The naming rule of the cmdlets (FR-12): one short lowercase name, a hyphenated compound word if it takes two. */
class CmdletNamesTest {

    private static final String NAME = "[a-z]+(-[a-z]+)*";

    @Test
    void everyBuiltInCmdletHasOneShortLowercaseName() {
        for (Cmdlet<?, ?, ?> cmdlet : new BuiltinCmdlets().cmdlets()) {
            String name = cmdlet.getClass().getAnnotation(CmdletInfo.class).name();

            assertThat(name).as(cmdlet.getClass().getSimpleName()).matches(NAME).hasSizeLessThanOrEqualTo(12);
        }
    }

    @Test
    void noTwoBuiltInCmdletsShareAName() {
        var seen = new HashSet<String>();
        for (Cmdlet<?, ?, ?> cmdlet : new BuiltinCmdlets().cmdlets()) {
            String name = cmdlet.getClass().getAnnotation(CmdletInfo.class).name();

            assertThat(seen.add(name)).as("name used twice: " + name).isTrue();
        }
    }

    @Test
    void theRuleRejectsWhatItIsMeantTo() {
        assertThat("to-json").matches(NAME);
        assertThat("Where").doesNotMatch(NAME);
        assertThat("to_json").doesNotMatch(NAME);
        assertThat("get-child-item-").doesNotMatch(NAME);
    }
}
