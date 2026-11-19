package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.powerj.api.Cmdlet;
import io.powerj.fs.Globs;
import io.powerj.fs.Host;
import io.powerj.testing.CmdletDriver;
import io.powerj.testing.TestContext;

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
            CmdletDriver.run(cmdlet, params, context, inputs);
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
        assertThat(run(new Ps(), new Ps.Params(List.of("no-such-process", "jav?"), user)))
                .extracting(ProcessEntry::pid).contains(self());
        context.emitted.clear();
        if (!Host.windows()) { // the names of the processes ignore case only where the system does
            assertThat(run(new Ps(), new Ps.Params(List.of("JAV?"), null))).extracting(ProcessEntry::pid)
                    .doesNotContain(self());
            context.emitted.clear();
        }
        assertThat(run(new Ps(), new Ps.Params(List.of("no-such-process*"), null))).isEmpty();
        assertThat(context.errors).isEmpty();
    }

    @Test
    void nameAndUserMatching() {
        var java = List.of(Globs.matcher("java", true));
        assertThat(Ps.matches("java.exe", java)).isTrue();
        assertThat(Ps.matches("JAVA", java)).isTrue();
        assertThat(Ps.matches("javaw.exe", java)).isFalse();
        assertThat(Ps.matches("javaw.exe", List.of(Globs.matcher("java*", true)))).isTrue();
        assertThat(Ps.matches(null, java)).isFalse();
        assertThat(Ps.matches(null, List.of())).isTrue();
        assertThat(Ps.ofUser("bob", "alice")).isFalse();
        assertThat(Ps.ofUser("alice", "alice")).isTrue();
        assertThat(Ps.ofUser("Alice", "alice")).isEqualTo(Host.windows()); // the users of Linux and macOS are case-sensitive
        if (Host.windows()) {
            assertThat(Ps.ofUser("DESKTOP\\Alice", "alice")).isTrue();
            assertThat(Ps.ofUser("desktop\\alice", "DESKTOP\\ALICE")).isTrue();
        }
        assertThat(Ps.ofUser(null, "alice")).isFalse();
        assertThat(ProcessEntry.name("C:\\Windows\\notepad.exe")).isEqualTo("notepad.exe");
        assertThat(ProcessEntry.name("/usr/bin/java")).isEqualTo("java");
    }

    // --- kill ---

    @Test
    void killStopsAProcessAndWaits() throws Exception {
        Process sleeper = sleeper();
        List<ProcessEntry> out = run(new Kill(), new Kill.Params(List.of(String.valueOf(sleeper.pid())), false, true,
                true, false, false, null));
        assertThat(alive(sleeper)).isFalse();
        assertThat(out).singleElement().extracting(ProcessEntry::pid).isEqualTo(sleeper.pid());
        assertThat(context.errors).isEmpty();
    }

    /** A background job that the terminal has stopped keeps SIGTERM pending: kill must continue it (issue #128). */
    @Test
    void killEndsAProcessThatTheTerminalHasStopped() throws Exception {
        assumeFalse(Host.windows(), "SIGSTOP and SIGCONT are Unix signals");
        Process sleeper = sleeper();
        assertThat(new ProcessBuilder("kill", "-STOP", String.valueOf(sleeper.pid())).start().waitFor()).isZero();

        run(new Kill(), new Kill.Params(List.of(String.valueOf(sleeper.pid())), false, true, false, false, false, null));

        assertThat(alive(sleeper)).isFalse();
        assertThat(context.errors).isEmpty();
    }

    @Test
    void killForceFromThePipeline() throws Exception {
        Process a = sleeper();
        Process b = sleeper();
        Process c = sleeper();
        List<ProcessEntry> out = run(new Kill(), new Kill.Params(List.of(), true, true, false, false, false, null),
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
        run(new Kill(), new Kill.Params(List.of(), false, true, false, false, false, null), new WithPid(1, sleeper.pid()),
                new WithoutPid("12"));
        assertThat(alive(sleeper)).isFalse();
        assertThat(context.errors).singleElement().asString().startsWith("not a process: ");
    }

    /** A record that this module could not read by reflection: it is the core's, as the job of {@code jobs} (#130). */
    @Test
    void theNumberOfARecordIsReadByTheContextAndNotByTheCmdlet() throws Exception {
        Process sleeper = sleeper();
        context.reader = (target, name) -> name.equals("pid") ? (Object) sleeper.pid() : "?";

        run(new Kill(), new Kill.Params(List.of(), false, true, false, false, false, null), new WithPid(1, 123_456_789L));

        assertThat(alive(sleeper)).as("the pid is the one the context answered, not the one in the record").isFalse();
        assertThat(context.errors).isEmpty();
    }

    @Test
    void aRecordWhoseNumberCannotBeReadIsNotAProcess() {
        context.reader = (target, name) -> {
            throw new IllegalStateException("cannot read " + name);
        };

        run(new Kill(), new Kill.Params(List.of(), false, false, false, false, false, null), new WithPid(1, 5L));

        assertThat(context.errors).singleElement().asString().startsWith("not a process: ");
    }

    @Test
    void dryRunStopsNothing() throws Exception {
        Process sleeper = sleeper();
        List<ProcessEntry> out = run(new Kill(), new Kill.Params(List.of(), false, false, false, true, false, null), sleeper);
        assertThat(out).singleElement().extracting(ProcessEntry::pid).isEqualTo(sleeper.pid());
        assertThat(sleeper.isAlive()).isTrue();
    }

    @Test
    void killErrors() {
        run(new Kill(), new Kill.Params(List.of(), false, false, false, false, false, null), 999_999_999L, self(), "x");
        assertThat(context.errors).containsExactly("no process 999999999",
                "cannot stop PowerJ itself (use exit)", "not a process: x");

        assertThatThrownBy(() -> run(new Kill(), new Kill.Params(List.of("abc"), false, false, false, false, false, null)))
                .hasMessage("invalid pid: abc (a process number is expected)");
        assertThatThrownBy(() -> run(new Kill(), new Kill.Params(List.of(), false, false, false, false, false, null)))
                .hasMessage("pid expected: kill <pid>... or ... | kill");
    }

    public record WithBoxedPid(Long pid) { }

    public record WithIntPid(int pid) { }

    @Test
    void pidsOfEveryNumberTypeAndRecordComponent() {
        int me = (int) self();
        var dryRun = new Kill.Params(null, false, false, false, true, false, null);
        assertThat(run(new Kill(), dryRun, me, new WithBoxedPid(self()))).extracting(ProcessEntry::pid)
                .containsExactly(self(), self());
        context.emitted.clear();
        run(new Kill(), new Kill.Params(List.of(me), false, false, false, true, false, null));
        assertThat(context.emitted).extracting(ProcessEntry::pid).containsExactly(self());
        context.emitted.clear();
        run(new Kill(), dryRun, (short) 1, new WithIntPid(me), new WithBoxedPid(null));
        assertThat(context.errors).doesNotContain("not a process: 1")
                .contains("not a process: WithIntPid[pid=" + me + "]", "not a process: WithBoxedPid[pid=null]");
    }

    // --- kill: the processes that must not be stopped by a loose filter (audit v2, U-07) ---

    private static Kill.Params dry(boolean allowProtected) {
        return new Kill.Params(List.of(), false, false, false, true, allowProtected, null);
    }

    /** {@code -n} is used throughout: a test that stops the parent of the test would end the build. */
    @Test
    void theParentsOfPowerJAreRefusedWithoutAllowProtected() {
        List<ProcessHandle> ancestors = new ArrayList<>();
        for (ProcessHandle p = ProcessHandle.current().parent().orElse(null); p != null; p = p.parent().orElse(null)) {
            ancestors.add(p);
        }
        assumeFalse(ancestors.isEmpty(), "this process has no parent");

        List<ProcessEntry> out = run(new Kill(), dry(false), ancestors.stream().map(ProcessHandle::pid).toArray());

        assertThat(out).isEmpty();
        assertThat(context.errors).hasSize(ancestors.size()).allSatisfy(e -> assertThat(e).startsWith("refusing to stop ")
                .contains("--allow-protected"));
    }

    @Test
    void allowProtectedLiftsTheRefusalOfAParent() {
        ProcessHandle parent = ProcessHandle.current().parent().orElse(null);
        assumeFalse(parent == null, "this process has no parent");

        List<ProcessEntry> out = run(new Kill(), dry(true), parent.pid());

        assertThat(out).extracting(ProcessEntry::pid).containsExactly(parent.pid());
        assertThat(context.errors).isEmpty();
    }

    @Test
    void theInitProcessIsRefused() {
        assumeFalse(Host.windows() || ProcessHandle.of(1).isEmpty(), "no init process to name");

        List<ProcessEntry> out = run(new Kill(), dry(false), 1L);

        assertThat(out).isEmpty();
        assertThat(context.errors).singleElement().asString().startsWith("refusing to stop 1 ").contains("init");
    }

    @Test
    void anUnrelatedProcessIsNotProtected() throws Exception {
        Process sleeper = sleeper();

        assertThat(run(new Kill(), dry(false), sleeper.pid())).extracting(ProcessEntry::pid)
                .containsExactly(sleeper.pid());
        assertThat(context.errors).isEmpty();
    }

    @Test
    void aStoredEntryWhosePidWasReusedStopsNothing() throws Exception {
        Process sleeper = sleeper();
        ProcessEntry real = ProcessEntry.of(sleeper.toHandle());
        ProcessEntry stale = new ProcessEntry(real.pid(), real.parent(), real.name(), real.command(), real.commandLine(),
                real.user(), java.time.Instant.EPOCH, real.cpu()); // another process, started long before

        run(new Kill(), new Kill.Params(List.of(), true, true, false, false, false, null), stale);

        assertThat(context.errors).containsExactly("no process " + real.pid());
        assertThat(alive(sleeper)).isTrue();

        context.errors.clear();
        run(new Kill(), new Kill.Params(List.of(), true, true, false, false, false, null), real);
        assertThat(context.errors).isEmpty();
        assertThat(alive(sleeper)).isFalse();
    }

    @Test
    void waitGivesUpAfterTheTimeoutOnAProcessThatDoesNotEnd() throws Exception {
        assumeFalse(Host.windows(), "on Windows every stop is forcible");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(StubbornSleeper.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        Process stubborn = new ProcessBuilder(javaBin, "-cp", classes, StubbornSleeper.class.getName()).start();
        started.add(stubborn);
        assertThat(new java.io.BufferedReader(new java.io.InputStreamReader(stubborn.getInputStream())).readLine())
                .isEqualTo("ready");

        long before = System.nanoTime();
        List<ProcessEntry> out = run(new Kill(), new Kill.Params(List.of(), false, true, true, false, false,
                java.time.Duration.ofSeconds(1)), stubborn);
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - before);

        assertThat(out).isEmpty(); // not stopped: not reported as stopped
        assertThat(context.errors).singleElement().asString().contains("still running").contains("-f");
        assertThat(seconds).isLessThan(30);
        assertThat(alive(stubborn)).isTrue();
    }

    @Test
    void aTimeoutThatIsNotPositiveIsAUsageError() {
        assertThatThrownBy(() -> run(new Kill(), new Kill.Params(List.of(String.valueOf(self())), false, true, false, false, false,
                java.time.Duration.ZERO))).hasMessageContaining("timeout");
    }

    @Test
    void anEnormousTimeoutIsNotAnInternalError() throws Exception {
        Process sleeper = sleeper();

        run(new Kill(), new Kill.Params(List.of(String.valueOf(sleeper.pid())), true, true, false, false, false,
                java.time.Duration.ofDays(99_999_999_999L)));

        assertThat(context.errors).isEmpty();
        assertThat(alive(sleeper)).isFalse();
    }
}
