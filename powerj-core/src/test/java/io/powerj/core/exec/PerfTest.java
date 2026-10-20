package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.core.lang.Connector;

/** Perf mode (FR-76): the plan of a line, the figures of a line that ran, and the Flight Recorder events (FR-76b). */
@Timeout(30)
class PerfTest {

    @CmdletInfo(name = "items", summary = "Produces test objects")
    static final class Items implements Cmdlet<Items.Params, Void, Integer> {

        record Params(@Option(shortName = 'n') int count) { }

        @Override
        public void begin(Params params, CmdletContext<Integer> context) {
            for (int i = 1; i <= params.count(); i++) {
                context.emit(i);
            }
        }
    }

    @CmdletInfo(name = "twice", summary = "Emits every object twice")
    static final class Twice implements Cmdlet<Twice.Params, Integer, Integer> {

        record Params() { }

        @Override
        public void process(Params params, Integer input, CmdletContext<Integer> context) {
            context.emit(input);
            context.emit(input);
        }
    }

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private Interpreter interpreter;

    @BeforeEach
    void setUp() throws Exception {
        var session = new Session(tmp, tmp, new java.util.HashMap<>(System.getenv()));
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false), Map.of(),
                CmdletRegistry.of(List.of(new Items(), new Twice())));
    }

    private PerfPlan.Statements plan(String line) {
        PerfPlan plan = interpreter.plan(line);
        assertThat(plan).isInstanceOf(PerfPlan.Statements.class);
        return (PerfPlan.Statements) plan;
    }

    @Test
    void planResolvesEachStageWithoutRunningAnything() {
        var statements = plan("items -n 3 | twice | twice").statements();
        assertThat(statements).hasSize(1);
        var stages = statements.getFirst().stages();
        assertThat(stages).extracting(PerfPlan.Stage::text).containsExactly("items -n 3", "twice", "twice");
        assertThat(stages).extracting(PerfPlan.Stage::kind).containsOnly(PerfPlan.Kind.CMDLET);
        assertThat(stages).extracting(PerfPlan.Stage::placement).containsExactly(PerfPlan.Placement.THREAD,
                PerfPlan.Placement.THREAD, PerfPlan.Placement.CALLER);
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void planShowsConnectorsKindsAndAssignments() {
        var statements = plan("$x = items -n 1 && cd . || nosuchcommand").statements();
        assertThat(statements).extracting(PerfPlan.Statement::connector)
                .containsExactly(Connector.ALWAYS, Connector.IF_SUCCESS, Connector.IF_FAILURE);
        assertThat(statements.getFirst().assignTo()).isEqualTo("x");
        assertThat(statements.get(1).stages().getFirst().kind()).isEqualTo(PerfPlan.Kind.BUILTIN);
        assertThat(statements.get(2).stages().getFirst().kind()).isEqualTo(PerfPlan.Kind.UNKNOWN);
        assertThat(statements.getFirst().stages().getFirst().placement()).isEqualTo(PerfPlan.Placement.CALLER);
    }

    @Test
    void planOfAnEmptyOrBrokenLine() {
        assertThat(interpreter.plan("  ")).isInstanceOf(PerfPlan.Empty.class);
        assertThat(interpreter.plan("items | \"unterminated")).isInstanceOf(PerfPlan.Incomplete.class);
    }

    @Test
    void recorderCountsObjectsAndTimesEachStage() throws Exception {
        var recorder = new PerfRecorder();
        interpreter.record(recorder);
        interpreter.execute("items -n 5 | twice | twice");
        interpreter.record(null);

        PerfReport report = recorder.report();
        assertThat(report.statements()).hasSize(1);
        var statement = report.statements().getFirst();
        assertThat(statement.succeeded()).isTrue();
        assertThat(statement.stages()).extracting(PerfReport.Stage::in).containsExactly(-1L, 5L, 10L);
        assertThat(statement.stages()).extracting(PerfReport.Stage::out).containsExactly(5L, 10L, 20L);
        assertThat(statement.stages()).allSatisfy(stage -> assertThat(stage.duration()).isGreaterThanOrEqualTo(java.time.Duration.ZERO));
        assertThat(report.total()).isGreaterThanOrEqualTo(statement.duration());
    }

    @Test
    void recorderSkipsStatementsThatDidNotRun() throws Exception {
        var recorder = new PerfRecorder();
        interpreter.record(recorder);
        interpreter.execute("nosuchcommand && items -n 1; items -n 2");
        interpreter.record(null);
        var statements = recorder.report().statements();
        assertThat(statements).hasSize(2);
        assertThat(statements.getFirst().succeeded()).isFalse();
        assertThat(statements.getLast().stages().getFirst().out()).isEqualTo(2L);
    }

    @Test
    void nothingIsRecordedWithoutARecorder() throws Exception {
        interpreter.execute("items -n 2 | twice");
        assertThat(out.toString()).isNotEmpty();
    }

    @Test
    void flightRecorderEventsCarryCommandNamesButNoArguments() throws Exception {
        Path file = tmp.resolve("pj.jfr");
        try (var recording = new jdk.jfr.Recording()) {
            recording.enable("powerj.Command");
            recording.enable("powerj.Stage");
            recording.start();
            interpreter.execute("items -n 5 | twice");
            recording.stop();
            recording.dump(file);
        }
        var events = jdk.jfr.consumer.RecordingFile.readAllEvents(file);
        assertThat(events).filteredOn(e -> e.getEventType().getName().equals("powerj.Command"))
                .singleElement().satisfies(e -> {
                    assertThat(e.getString("commands")).isEqualTo("items | twice");
                    assertThat(e.getInt("statements")).isEqualTo(1);
                });
        var stages = events.stream().filter(e -> e.getEventType().getName().equals("powerj.Stage")).toList();
        assertThat(stages).extracting(e -> e.getString("command")).containsExactlyInAnyOrder("items", "twice");
        assertThat(stages).filteredOn(e -> e.getString("command").equals("twice"))
                .singleElement().satisfies(e -> {
                    assertThat(e.getLong("in")).isEqualTo(5L);
                    assertThat(e.getLong("out")).isEqualTo(10L);
                    assertThat(e.getString("kind")).isEqualTo("cmdlet");
                });
        assertThat(stages).noneMatch(e -> e.toString().contains("-n"));
    }
}
