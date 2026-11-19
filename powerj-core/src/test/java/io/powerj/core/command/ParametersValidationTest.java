package io.powerj.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletInfo;
import io.powerj.api.CmdletProvider;
import io.powerj.api.Option;

/** A cmdlet whose parameter record cannot work is reported once, when it is registered (audit v2 R-02, #166). */
class ParametersValidationTest {

    record Good(@Option(shortName = 'r') float ratio) { }

    record Bad(@Option(shortName = 'f') String first, @Option(shortName = 'f') String second) { }

    @CmdletInfo(name = "good", summary = "Works")
    static final class GoodCmdlet implements Cmdlet<Good, Void, String> { }

    @CmdletInfo(name = "bad", summary = "Two options share -f")
    static final class BadCmdlet implements Cmdlet<Bad, Void, String> { }

    private record Provider(List<Cmdlet<?, ?, ?>> cmdlets) implements CmdletProvider { }

    @Test
    void aModuleWithAFaultyRecordGetsOneWarningNamingTheComponentAndTheCmdletIsLeftOut() {
        var registry = CmdletRegistry.of(List.of());
        var provider = new Provider(List.of(new BadCmdlet(), new GoodCmdlet()));

        List<String> warnings = registry.addModule(getClass().getModule(), Optional.empty(), provider,
                provider.cmdlets());

        assertThat(warnings).singleElement().asString().contains("ignored", "second", "-f");
        assertThat(registry.find("bad")).isEmpty();
        assertThat(registry.find("good")).isPresent();
    }
}
