package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.powerj.api.Cmdlet;
import io.powerj.fs.Globs;

/** The cmdlets {@code ps} and {@code kill} (specification FR-74). */
class PsKillTest {

    private final List<Process> started = new ArrayList<>();
    private final TestContext<ProcessEntry> context = new TestContext<>(Path.of("."));

    /** A Java process that sleeps for a minute. */
    private Process sleeper() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(Sleeper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process process = new ProcessBuilder(java, "-cp", classes, Sleeper.class.getName()).start();
        started.add(process);
        return process;
    }

    @AfterEach
    void stopStarted() throws Exception {
        for (Process process : started) {
            process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    private <P extends Record> List<ProcessEntry> run(Cmdlet<P, Object, ProcessEntry> cmdlet, P params,
                                                      Object... inputs) {
        context.hasInput = inputs.length > 0;
        try {
            cmdlet.begin(params, context);
            for (Object input : inputs) {
                cmdlet.process(params, input, context);
            }
            cmdlet.end(params, context);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return context.emitted;
    }

    /**
     * Whether the process still runs, asked to the system: on Windows, {@link Process#isAlive()} may still answer
     * {@code true} for a moment after another handle has seen the end of the process.
     */
    private static boolean alive(Process process) {
        return process.toHandle().isAlive();
    }

    private static long self() {
        return ProcessHandle.current().pid();
    }

    // --- ps ---

    @Test
    void psListsTheCurrentProcessSortedByPid() {
        List<ProcessEntry> all = run(new Ps(), new Ps.Params(null, null));
        assertThat(all).extracting(ProcessEntry::pid).isSorted().contains(self());
        ProcessEntry me = all.stream().filter(e -> e.pid() == self()).findFirst().orElseThrow();
        assertThat(me.name()).startsWithIgnoringCase("java");
        assertThat(me.start()).isNotNull();
        assertThat(me.parent()).isEqualTo(ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(null));
        assertThat(context.errors).isEmpty();
    }

    @Test
    void psFiltersByNameAndUser() {
        String user = ProcessHandle.current().info().user().orElse(null);
        assertThat(run(new Ps(), new Ps.Params(List.of("no-such-process", "JAV?"), user)))
                .extracting(ProcessEntry::pid).contains(self());
        context.emitted.clear();
        assertThat(run(new Ps(), new Ps.Params(List.of("no-such-process*"), null))).isEmpty();
        assertThat(context.errors).isEmpty();
    }

    @Test
    void nameAndUserMatching() {
        var java = List.of(Globs.matcher("java"));
        assertThat(Ps.matches("java.exe", java)).isTrue();
        assertThat(Ps.matches("JAVA", java)).isTrue();
        assertThat(Ps.matches("javaw.exe", java)).isFalse();
        assertThat(Ps.matches("javaw.exe", List.of(Globs.matcher("java*")))).isTrue();
        assertThat(Ps.matches(null, java)).isFalse();
        assertThat(Ps.matches(null, List.of())).isTrue();
        assertThat(Ps.ofUser("DESKTOP\\Alice", "alice")).isTrue();
        assertThat(Ps.ofUser("desktop\\alice", "DESKTOP\\ALICE")).isTrue();
        assertThat(Ps.ofUser("bob", "alice")).isFalse();
        assertThat(Ps.ofUser(null, "alice")).isFalse();
        assertThat(ProcessEntry.name("C:\\Windows\\notepad.exe")).isEqualTo("notepad.exe");
        assertThat(ProcessEntry.name("/usr/bin/java")).isEqualTo("java");
    }

    // --- kill ---

    @Test
    void killStopsAProcessAndWaits() throws Exception {
        Process sleeper = sleeper();
        List<ProcessEntry> out = run(new Kill(), new Kill.Params(List.of(String.valueOf(sleeper.pid())), false, true,
                true, false));
        assertThat(alive(sleeper)).isFalse();
        assertThat(out).singleElement().extracting(ProcessEntry::pid).isEqualTo(sleeper.pid());
        assertThat(context.errors).isEmpty();
    }

    @Test
    void killForceFromThePipeline() throws Exception {
        Process a = sleeper();
        Process b = sleeper();
        Process c = sleeper();
        List<ProcessEntry> out = run(new Kill(), new Kill.Params(List.of(), true, true, false, false),
                ProcessEntry.of(a.toHandle()), b.toHandle(), c.pid());
        assertThat(out).isEmpty();
        assertThat(List.of(a, b, c)).noneMatch(PsKillTest::alive);
    }

    /** Any record with a {@code pid} component, such as a job of {@code jobs} (FR-75). */
    public record WithPid(int id, long pid) { }

    public record WithoutPid(String pid) { }

    @Test
    void killARecordWithAPid() throws Exception {
        Process sleeper = sleeper();
        run(new Kill(), new Kill.Params(List.of(), false, true, false, false), new WithPid(1, sleeper.pid()),
                new WithoutPid("12"));
        assertThat(alive(sleeper)).isFalse();
        assertThat(context.errors).singleElement().asString().startsWith("not a process: ");
    }

    @Test
    void dryRunStopsNothing() throws Exception {
        Process sleeper = sleeper();
        List<ProcessEntry> out = run(new Kill(), new Kill.Params(List.of(), false, false, false, true), sleeper);
        assertThat(out).singleElement().extracting(ProcessEntry::pid).isEqualTo(sleeper.pid());
        assertThat(sleeper.isAlive()).isTrue();
    }

    @Test
    void killErrors() {
        run(new Kill(), new Kill.Params(List.of(), false, false, false, false), 999_999_999L, self(), "x");
        assertThat(context.errors).containsExactly("no process 999999999",
                "cannot stop PowerJ itself (use exit)", "not a process: x");

        assertThatThrownBy(() -> run(new Kill(), new Kill.Params(List.of("abc"), false, false, false, false)))
                .hasMessage("invalid pid: abc (a process number is expected)");
        assertThatThrownBy(() -> run(new Kill(), new Kill.Params(List.of(), false, false, false, false)))
                .hasMessage("pid expected: kill <pid>... or ... | kill");
    }

    public record WithBoxedPid(Long pid) { }

    public record WithIntPid(int pid) { }

    @Test
    void pidsOfEveryNumberTypeAndRecordComponent() {
        int me = (int) self();
        var dryRun = new Kill.Params(null, false, false, false, true);
        assertThat(run(new Kill(), dryRun, me, new WithBoxedPid(self()))).extracting(ProcessEntry::pid)
                .containsExactly(self(), self());
        context.emitted.clear();
        run(new Kill(), new Kill.Params(List.of(me), false, false, false, true));
        assertThat(context.emitted).extracting(ProcessEntry::pid).containsExactly(self());
        context.emitted.clear();
        run(new Kill(), dryRun, (short) 1, new WithIntPid(me), new WithBoxedPid(null));
        assertThat(context.errors).doesNotContain("not a process: 1")
                .contains("not a process: WithIntPid[pid=" + me + "]", "not a process: WithBoxedPid[pid=null]");
    }
}
