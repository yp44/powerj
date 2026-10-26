package io.powerj.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletInfo;
import io.powerj.api.ColumnProvider;
import io.powerj.api.Previewable;
import io.powerj.api.Row;

/**
 * The optional capabilities of a cmdlet, asked through {@link CmdletRegistry.Registered}: the interface that implements
 * them, and what a cmdlet without the interface gets.
 */
class RegisteredCapabilitiesTest {

    record Params(String word) { }

    @CmdletInfo(name = "plain", summary = "Declares nothing", preview = true)
    static final class Plain implements Cmdlet<Params, Void, String> { }

    @CmdletInfo(name = "capable", summary = "Implements the capabilities", preview = true)
    static final class Capable implements Cmdlet<Params, Void, Row>, Previewable<Params>, ColumnProvider {
        @Override
        public boolean canPreview(Params params) {
            return !params.word().equals("change");
        }

        @Override
        public List<String> columnsOf(String arguments) {
            return List.of("new:" + arguments.strip());
        }
    }

    private static CmdletRegistry.Registered registered(Cmdlet<?, ?, ?> cmdlet, String name) {
        return CmdletRegistry.of(List.of(cmdlet)).find(name).orElseThrow();
    }

    @Test
    void aCmdletThatImplementsNothingCanBePreviewedAndKnowsNoColumns() {
        var plain = registered(new Plain(), "plain");

        assertThat(plain.previewable(new Params("change"))).isTrue();
        assertThat(plain.columns("a b")).isEmpty();
    }

    @Test
    void thePreviewableCapabilityDecides() {
        var capable = registered(new Capable(), "capable");

        assertThat(capable.previewable(new Params("show"))).isTrue();
        assertThat(capable.previewable(new Params("change"))).isFalse();
    }

    @Test
    void theColumnProviderCapabilityNamesTheColumns() {
        assertThat(registered(new Capable(), "capable").columns(" a b ")).containsExactly("new:a b");
    }
}
