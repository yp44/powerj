package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.command.CmdletRegistry;

/** The settings of the preview mode (FR-66): defaults, validation, steps, and the environment of the session. */
class PreviewSettingsTest {

    private static PreviewSettings from(String deadline, String limit) {
        Map<String, String> environment = new HashMap<>();
        if (deadline != null) {
            environment.put(PreviewSettings.DEADLINE_VARIABLE, deadline);
        }
        if (limit != null) {
            environment.put(PreviewSettings.LIMIT_VARIABLE, limit);
        }
        return PreviewSettings.from(environment);
    }

    @Test
    void withoutVariablesTheSettingsAreTheDefaultOnes() {
        assertThat(from(null, null)).isEqualTo(new PreviewSettings(400, 50)).isEqualTo(PreviewSettings.defaults());
    }

    @Test
    void theVariablesSetTheDeadlineAndTheLimit() {
        assertThat(from("800", "20")).isEqualTo(new PreviewSettings(800, 20));
        assertThat(from(" 1600 ", " 100 ")).isEqualTo(new PreviewSettings(1600, 100));
    }

    @Test
    void aValueThatIsNotValidIsTheDefaultOne() {
        assertThat(from("fast", "many")).isEqualTo(PreviewSettings.defaults());
        assertThat(from("0", "0")).isEqualTo(PreviewSettings.defaults());
        assertThat(from("-5", "-5")).isEqualTo(PreviewSettings.defaults());
        assertThat(from("", "")).isEqualTo(PreviewSettings.defaults());
        assertThat(from("1.5", "99999999999")).isEqualTo(PreviewSettings.defaults());
    }

    @Test
    void theTwoVariablesAreIndependent() {
        assertThat(from("800", "oops")).isEqualTo(new PreviewSettings(800, 50));
        assertThat(from("oops", "20")).isEqualTo(new PreviewSettings(400, 20));
    }

    @Test
    void aDeadlineThatWouldFreezeTheTypingIsBroughtBackToTheLongestOne() {
        assertThat(from("60000", null).deadlineMillis()).isEqualTo(3200);
        assertThat(from("3200", null).deadlineMillis()).isEqualTo(3200);
        assertThat(from("3201", null).deadlineMillis()).isEqualTo(3200);
        assertThat(from(null, "5000000").stageLimit()).isEqualTo(100_000);
    }

    @Test
    void theDeadlineGoesThroughItsSteps() {
        var settings = PreviewSettings.defaults();
        assertThat(settings.withDeadlineStep(1).deadlineMillis()).isEqualTo(800);
        assertThat(settings.withDeadlineStep(1).withDeadlineStep(1).deadlineMillis()).isEqualTo(1600);
        assertThat(settings.withDeadlineStep(-1).deadlineMillis()).isEqualTo(200);
        assertThat(settings.withDeadlineStep(-1).withDeadlineStep(-1).deadlineMillis()).isEqualTo(200);
        assertThat(new PreviewSettings(3200, 50).withDeadlineStep(1).deadlineMillis()).isEqualTo(3200);
        assertThat(settings.withDeadlineStep(1).stageLimit()).isEqualTo(50);
    }

    @Test
    void theLimitGoesThroughItsSteps() {
        var settings = PreviewSettings.defaults();
        assertThat(settings.withLimitStep(1).stageLimit()).isEqualTo(100);
        assertThat(settings.withLimitStep(-1).stageLimit()).isEqualTo(20);
        assertThat(settings.withLimitStep(-1).withLimitStep(-1).stageLimit()).isEqualTo(10);
        assertThat(settings.withLimitStep(-1).withLimitStep(-1).withLimitStep(-1).stageLimit()).isEqualTo(10);
        assertThat(new PreviewSettings(400, 500).withLimitStep(1).stageLimit()).isEqualTo(500);
        assertThat(settings.withLimitStep(1).deadlineMillis()).isEqualTo(400);
    }

    @Test
    void aValueBetweenTwoStepsGoesToTheNextOneInTheDirectionAsked() {
        assertThat(new PreviewSettings(700, 30).withDeadlineStep(1).deadlineMillis()).isEqualTo(800);
        assertThat(new PreviewSettings(700, 30).withDeadlineStep(-1).deadlineMillis()).isEqualTo(400);
        assertThat(new PreviewSettings(700, 30).withLimitStep(1).stageLimit()).isEqualTo(50);
        assertThat(new PreviewSettings(700, 30).withLimitStep(-1).stageLimit()).isEqualTo(20);
        assertThat(new PreviewSettings(700, 5000).withLimitStep(1).stageLimit()).isEqualTo(5000);
        assertThat(new PreviewSettings(700, 5000).withLimitStep(-1).stageLimit()).isEqualTo(500);
    }

    @Test
    void theEndOfTheStepsIsKnown() {
        assertThat(new PreviewSettings(1600, 100).deadlineAtMax()).isFalse();
        assertThat(new PreviewSettings(3200, 100).deadlineAtMax()).isTrue();
        assertThat(new PreviewSettings(400, 100).limitAtMax()).isFalse();
        assertThat(new PreviewSettings(400, 500).limitAtMax()).isTrue();
        assertThat(new PreviewSettings(400, 5000).limitAtMax()).isTrue();
    }

    @Test
    void theSettingsAreWrittenToTheEnvironmentAndReadBack() {
        Map<String, String> environment = new HashMap<>();
        new PreviewSettings(1600, 20).writeTo(environment);
        assertThat(environment).containsEntry("POWERJ_PREVIEW_DEADLINE_MS", "1600")
                .containsEntry("POWERJ_PREVIEW_STAGE_LIMIT", "20");
        assertThat(PreviewSettings.from(environment)).isEqualTo(new PreviewSettings(1600, 20));
    }

    @Test
    void theInterpreterReadsAndWritesThemInTheEnvironmentOfTheSession(@TempDir Path tmp) {
        var session = new Session(tmp, tmp, Map.of());
        var interpreter = new Interpreter(session,
                new ShellIo(new PrintWriter(new StringWriter(), true), _ -> { }, false), Map.of(),
                CmdletRegistry.discover());
        assertThat(interpreter.previewSettings()).isEqualTo(PreviewSettings.defaults());

        interpreter.previewSettings(new PreviewSettings(800, 20));

        assertThat(session.environment()).containsEntry("POWERJ_PREVIEW_DEADLINE_MS", "800")
                .containsEntry("POWERJ_PREVIEW_STAGE_LIMIT", "20");
        assertThat(interpreter.previewSettings()).isEqualTo(new PreviewSettings(800, 20));

        session.environment().put("POWERJ_PREVIEW_DEADLINE_MS", "1600"); // `env --set` takes effect at once
        assertThat(interpreter.previewSettings().deadlineMillis()).isEqualTo(1600);
    }
}
