package io.powerj.core.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.powerj.api.internal.LanguageSetting;
import io.powerj.api.perf.JvmStat;
import io.powerj.api.perf.PerfReport;
import io.powerj.api.perf.PhaseStat;
import io.powerj.api.perf.StageStat;

/** The footer of the perf mode is in the language of the session, its column headers included (FR-76, FR-61). */
class PerfFooterTest {

    private static final Duration MS = Duration.ofMillis(1);

    private static PerfReport report() {
        return new PerfReport(1, "ls | count", Duration.ofMillis(10), false,
                List.of(new PhaseStat(1, "execute", MS), new PhaseStat(1, "display", MS)),
                List.of(new StageStat(1, 1, "ls", null, 5L, MS, MS, MS, MS, true),
                        new StageStat(1, 2, "count", 5L, 1L, MS, MS, MS, MS, false)),
                List.of(), List.of(), new JvmStat(1, 0, Duration.ZERO, 1024, 3, Duration.ZERO),
                List.of(), List.of(), List.of(), null);
    }

    @AfterEach
    void english() {
        LanguageSetting.set(Locale.ENGLISH);
    }

    @Test
    void theColumnsAreNamedInEnglish() {
        List<String> lines = PerfFooter.lines(report(), false);
        assertThat(lines.get(1).split("\\s+")).containsExactly("stage", "in", "out", "active", "starved", "blocked");
        assertThat(lines.get(lines.size() - 2)).startsWith("display ");
    }

    @Test
    void theFrenchFooterHoldsNoEnglishColumnHeader() {
        LanguageSetting.set(Locale.FRENCH);
        List<String> lines = PerfFooter.lines(report(), false);
        assertThat(lines.get(1)).matches("étape +entrée +sortie +actif +à vide +bloqué");
        assertThat(lines.get(lines.size() - 2)).startsWith("affichage ");
        assertThat(String.join("\n", lines)).doesNotContain(" in ", " out ", "active", "starved", "blocked", "display");
    }
}
