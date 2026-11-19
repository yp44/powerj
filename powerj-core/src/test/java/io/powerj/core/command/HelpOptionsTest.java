package io.powerj.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;

/** The help of a cmdlet says what each option takes and what the command cannot run without (audit v2, U-05, #176). */
class HelpOptionsTest {

    enum Mode { FAST, SAFE }

    @CmdletInfo(name = "probe", summary = "Takes every kind of option")
    static final class Probe implements Cmdlet<Probe.Params, Void, String> {
        record Params(@Option(mandatory = true, description = "Name") String name,
                      @Option(shortName = 'n', description = "How many") int count,
                      @Option(description = "A size") long size,
                      @Option(description = "How long ago") Duration since,
                      @Option(description = "Where") Path out,
                      @Option(description = "How") Mode mode,
                      @Option(shortName = 'a', description = "Everything") boolean all,
                      @Option(position = 0, description = "Files") List<Path> files) { }
    }

    private static List<String> help() {
        return Help.cmdlet(CmdletRegistry.of(List.of(new Probe())).find("probe").orElseThrow());
    }

    @Test
    void everyValuedOptionShowsItsType() {
        String text = String.join("\n", help());

        assertThat(text).contains("-n, --count <n>").contains("--size <size>").contains("--since <duration>")
                .contains("--out <path>").contains("--mode {fast|safe}").contains("--name <text>")
                .doesNotContain("<value>");
    }

    @Test
    void theUsageLineHasTheMandatoryNamedOptionsWithTheirType() {
        String usage = help().stream().filter(l -> l.startsWith("Usage:")).findFirst().orElseThrow();

        assertThat(usage).isEqualTo("Usage: probe [files...] --name <text> [options]");
    }
}
