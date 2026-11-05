package io.powerj.core.nativeexec;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.nativeexec.PosixSpawn.Spawned;
import io.powerj.core.nativeexec.PosixSpawn.Spec;
import io.powerj.core.nativeexec.PosixSpawn.Stdio;


/**
 * PROTOTYPE of issue #128 (Linux): start a background process in its own process group with {@code posix_spawn}
 * through the FFM API. Each test is one check of the plan ({@code docs/plans/background-process-group.md}, Phase 1);
 * the numbers are printed and appended to {@code target/prototype/posix-spawn-results.txt}. Not shipped.
 */
@EnabledOnOs(OS.LINUX)
class PosixSpawnPrototypeTest {

    private static final Path RESULTS = Path.of("target", "prototype", "posix-spawn-results.txt");
    private static final String SH_SHIELD = "trap '' INT QUIT; exec \"$0\" \"$@\"";

    @TempDir
    Path tmp;

    private final List<Spawned> started = new ArrayList<>();
    private final List<Process> startedByJava = new ArrayList<>();

    @AfterEach
    void killEverything() {
        started.forEach(p -> {
            PosixSpawn.kill(-p.pid(), PosixSpawn.SIGKILL);
            PosixSpawn.kill(p.pid(), PosixSpawn.SIGKILL);
        });
        startedByJava.forEach(Process::destroyForcibly);
    }

    // --- 1. own process group ---

    @Test
    void check01_theChildIsInItsOwnProcessGroup() throws Exception {
        Spawned own = start(sleep().group(PosixSpawn.OWN_GROUP));
        Spawned shared = start(sleep());
        Process shielded = startByJava(new ProcessBuilder("/bin/sh", "-c", SH_SHIELD, exe("sleep").toString(), "30"));
        int jvmGroup = PosixSpawn.getpgrp();

        assertThat(PosixSpawn.getpgid(own.pid())).isEqualTo(own.pid()).isNotEqualTo(jvmGroup);
        assertThat(procStat(own.pid()).pgrp()).isEqualTo(own.pid());
        assertThat(PosixSpawn.getpgid(shared.pid())).isEqualTo(jvmGroup);
        // The `sh` wrapper of today: same group as the JVM, so the terminal's SIGINT reaches it (and is ignored there).
        assertThat(PosixSpawn.getpgid((int) shielded.pid())).isEqualTo(jvmGroup);
        log("01 pgid: jvm=%d, posix_spawn(setpgroup 0)=%d (own pid %d), posix_spawn(no group)=%d, sh+trap=%d",
                jvmGroup, PosixSpawn.getpgid(own.pid()), own.pid(), PosixSpawn.getpgid(shared.pid()),
                PosixSpawn.getpgid((int) shielded.pid()));
    }

    // --- 2. SIGINT of the foreground group ---

    @Test
    void check02_sigintOfTheForegroundGroupDoesNotReachTheBackgroundGroup() throws Exception {
        // The terminal sends SIGINT to the foreground group: here the group of `leader`, with `member` in it.
        Spawned leader = start(sleep().group(PosixSpawn.OWN_GROUP));
        Spawned member = start(sleep().group(leader.pid()));
        Spawned background = start(sleep().group(PosixSpawn.OWN_GROUP));
        assertThat(PosixSpawn.getpgid(member.pid())).isEqualTo(leader.pid());
        assertThat(PosixSpawn.getpgid(background.pid())).isEqualTo(background.pid());

        assertThat(PosixSpawn.kill(-leader.pid(), PosixSpawn.SIGINT)).isZero();

        assertThat(leader.exit().get(10, TimeUnit.SECONDS)).isEqualTo(130);
        assertThat(member.exit().get(10, TimeUnit.SECONDS)).isEqualTo(130);
        // "It did not die" can only be seen by letting time pass: a death would be immediate.
        Thread.sleep(300);
        assertThat(background.exit().isDone()).isFalse();
        assertThat(PosixSpawn.kill(background.pid(), 0)).isZero();
        log("02 SIGINT to the foreground group %d: leader exit=%d, member exit=%d, background (pgid %d) alive=%b",
                leader.pid(), leader.exit().get(), member.exit().get(), background.pid(), !background.exit().isDone());
    }

    @Test
    void check02b_sigintSentToThePidStillKillsIt() throws Exception {
        // What the unit test of today does (`kill -INT <pid>`): with a process group the program does not ignore
        // SIGINT, it only is out of the reach of the terminal. That test has to change.
        Spawned background = start(sleep().group(PosixSpawn.OWN_GROUP));
        assertThat(PosixSpawn.kill(background.pid(), PosixSpawn.SIGINT)).isZero();
        assertThat(background.exit().get(10, TimeUnit.SECONDS)).isEqualTo(130);
        log("02b kill -INT <pid> of a process in its own group: exit=%d (the sh+trap child survives it)",
                background.exit().get());
    }

    // --- 3. exit code ---

    @Test
    void check03_exitCodes() throws Exception {
        Spawned seven = start(sh("exit 7"));
        Spawned term = start(sh("kill -TERM $$"));
        Spawned killed = start(sleep());
        PosixSpawn.kill(killed.pid(), PosixSpawn.SIGKILL);
        assertThat(seven.exit().get(10, TimeUnit.SECONDS)).isEqualTo(7);
        assertThat(term.exit().get(10, TimeUnit.SECONDS)).isEqualTo(128 + PosixSpawn.SIGTERM);
        assertThat(killed.exit().get(10, TimeUnit.SECONDS)).isEqualTo(128 + PosixSpawn.SIGKILL);
        log("03 exit codes: exit 7 -> %d, SIGTERM -> %d, SIGKILL -> %d", seven.exit().get(), term.exit().get(),
                killed.exit().get());
    }

    @Test
    void check03b_noStatusIsLostInAThousandRunsEvenWithTheJdkStartingProcessesToo() throws Exception {
        int before = PosixSpawn.lostStatuses();
        Path trueExe = exe("true");
        var noise = new AtomicBoolean(true);
        int[] noiseRuns = new int[1];
        Thread jdk = Thread.ofPlatform().daemon().start(() -> {
            while (noise.get()) {
                try {
                    new ProcessBuilder(trueExe.toString()).start().waitFor();
                    noiseRuns[0]++;
                } catch (IOException | InterruptedException e) {
                    return;
                }
            }
        });
        int threads = 4;
        int perThread = 250;
        long wrong = 0;
        long handleCompleted = 0;
        long begin = System.nanoTime();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<long[]>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    long bad = 0;
                    long viaHandle = 0;
                    for (int i = 0; i < perThread; i++) {
                        Spawned child = PosixSpawn.spawn(sh("exit 5"));
                        // Half of the children are also followed by the JDK, as `jobs` or `ps` would.
                        if (i % 2 == 0) {
                            var handle = ProcessHandle.of(child.pid());
                            if (handle.isPresent()) {
                                handle.get().onExit().thenRun(() -> { });
                                viaHandle++;
                            }
                        }
                        if (child.exit().get(30, TimeUnit.SECONDS) != 5) {
                            bad++;
                        }
                    }
                    return new long[] {bad, viaHandle};
                }));
            }
            for (Future<long[]> future : futures) {
                long[] result = future.get(120, TimeUnit.SECONDS);
                wrong += result[0];
                handleCompleted += result[1];
            }
        } finally {
            noise.set(false);
            jdk.join(10_000);
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        assertThat(wrong).isZero();
        assertThat(PosixSpawn.lostStatuses() - before).isZero();
        log("03b %d runs on %d threads, %d ProcessBuilder runs at the same time: %d wrong codes, %d lost statuses "
                        + "(%d ms, %d children also followed through ProcessHandle)", threads * perThread, threads,
                noiseRuns[0], wrong, PosixSpawn.lostStatuses() - before, millis, handleCompleted);
    }

    @Test
    void check03c_processHandleAndWaitpidCoexist() throws Exception {
        Spawned child = start(sh("sleep 0.3; exit 3"));
        ProcessHandle handle = ProcessHandle.of(child.pid()).orElseThrow();
        assertThat(handle.isAlive()).isTrue();
        assertThat(handle.parent()).contains(ProcessHandle.current());
        assertThat(ProcessHandle.current().children().map(ProcessHandle::pid)).contains((long) child.pid());
        handle.onExit().get(10, TimeUnit.SECONDS);
        assertThat(child.exit().get(10, TimeUnit.SECONDS)).isEqualTo(3);
        log("03c ProcessHandle: isAlive/parent/children work on a posix_spawn child; onExit completed and waitpid "
                + "still gave exit=%d", child.exit().get());
    }

    // --- 4. kill ---

    @Test
    void check04_processHandleCanStopTheChild() throws Exception {
        Spawned gentle = start(sleep());
        Spawned forcible = start(sleep());
        ProcessHandle.of(gentle.pid()).orElseThrow().destroy();
        ProcessHandle.of(forcible.pid()).orElseThrow().destroyForcibly();
        assertThat(gentle.exit().get(10, TimeUnit.SECONDS)).isEqualTo(128 + PosixSpawn.SIGTERM);
        assertThat(forcible.exit().get(10, TimeUnit.SECONDS)).isEqualTo(128 + PosixSpawn.SIGKILL);
        log("04 ProcessHandle.destroy() -> exit %d, destroyForcibly() -> exit %d", gentle.exit().get(),
                forcible.exit().get());
    }

    // --- 5. file descriptors ---

    @Test
    void check05_theChildInheritsNoOtherDescriptorThanTheStandardOnes() throws Exception {
        int[] leak = PosixSpawn.pipe(); // not close-on-exec: what a raw fork/exec would leak
        try {
            Spawned clean = start(sleep().closeFds(true));
            Spawned leaky = start(sleep().closeFds(false));
            List<String> cleanFds = fds(clean.pid());
            List<String> leakyFds = fds(leaky.pid());
            assertThat(cleanFds).containsExactlyInAnyOrder("0", "1", "2");
            assertThat(leakyFds).contains(String.valueOf(leak[0]), String.valueOf(leak[1]));
            log("05 descriptors of the child: with addclosefrom_np(3) %s; without (control, leaks the JVM's pipe %d/%d) "
                    + "%d descriptors", cleanFds, leak[0], leak[1], leakyFds.size());
        } finally {
            PosixSpawn.close(leak[0]);
            PosixSpawn.close(leak[1]);
        }
    }

    // --- 6. redirections ---

    @Test
    void check06_redirectionsAndStandardInputOnTheNullDevice() throws Exception {
        Path out = tmp.resolve("out.txt");
        Path err = tmp.resolve("err.txt");
        Spec writer = sh("echo out; echo err >&2").out(Stdio.file(out)).err(Stdio.append(err));
        assertThat(start(writer).exit().get(10, TimeUnit.SECONDS)).isZero();
        assertThat(start(writer).exit().get(10, TimeUnit.SECONDS)).isZero();
        assertThat(Files.readString(out)).isEqualTo("out\n"); // >  truncates
        assertThat(Files.readString(err)).isEqualTo("err\nerr\n"); // 2>> appends

        Path both = tmp.resolve("both.txt");
        assertThat(start(sh("echo out; echo err >&2").out(Stdio.file(both)).err(Stdio.STDOUT)).exit()
                .get(10, TimeUnit.SECONDS)).isZero();
        assertThat(Files.readString(both)).isEqualTo("out\nerr\n"); // 2>&1

        Path input = tmp.resolve("in.txt");
        assertThat(start(sh("cat; echo end").in(Stdio.NULL).out(Stdio.file(input))).exit()
                .get(10, TimeUnit.SECONDS)).isZero();
        assertThat(Files.readString(input)).isEqualTo("end\n"); // stdin is /dev/null: cat returns at once
        log("06 redirections: > truncates, 2>> appends, 2>&1 joins, stdin on /dev/null ends at once");
    }

    // --- 7. pipeline ---

    @Test
    void check07_pipelineWithAPipeAndNoDescriptorLeftInTheParent() throws Exception {
        Path out = tmp.resolve("sorted.txt");
        List<String> before = pipesOfTheJvm();
        for (int round = 0; round < 50; round++) {
            int[] pipe = PosixSpawn.pipe();
            Spawned left;
            Spawned right;
            try {
                left = start(sh("printf 'b\\na\\n'").out(Stdio.fd(pipe[1])));
                right = start(Spec.of(exe("sort")).in(Stdio.fd(pipe[0])).out(Stdio.file(out)));
            } finally {
                PosixSpawn.close(pipe[0]);
                PosixSpawn.close(pipe[1]);
            }
            assertThat(left.exit().get(10, TimeUnit.SECONDS)).isZero();
            assertThat(right.exit().get(10, TimeUnit.SECONDS)).isZero();
            assertThat(Files.readString(out)).isEqualTo("a\nb\n");
        }
        assertThat(pipesOfTheJvm()).hasSameSizeAs(before);
        log("07 pipeline `printf | sort > f`: 50 rounds, EOF reaches sort, pipes of the JVM before=%d after=%d",
                before.size(), pipesOfTheJvm().size());
    }

    // --- 8. directory and environment ---

    @Test
    void check08_directoryAndEnvironmentAreTheOnesOfTheSession() throws Exception {
        Path pwd = tmp.resolve("pwd.txt");
        assertThat(start(Spec.of(exe("pwd")).directory(tmp).out(Stdio.file(pwd))).exit().get(10, TimeUnit.SECONDS))
                .isZero();
        assertThat(Files.readString(pwd).strip()).isEqualTo(tmp.toRealPath().toString());

        Path env = tmp.resolve("env.txt");
        Map<String, String> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("A", "1");
        snapshot.put("B", "deux é");
        assertThat(start(Spec.of(exe("env")).env(snapshot).out(Stdio.file(env))).exit().get(10, TimeUnit.SECONDS))
                .isZero();
        // The names are encoded as ProcessBuilder does it (sun.jnu.encoding): compare with the JDK's own result.
        ProcessBuilder jdk = new ProcessBuilder(exe("env").toString()).redirectOutput(tmp.resolve("jdk-env.txt").toFile());
        jdk.environment().clear();
        jdk.environment().putAll(snapshot);
        assertThat(jdk.start().waitFor()).isZero();
        assertThat(Files.readAllLines(env, java.nio.charset.StandardCharsets.ISO_8859_1))
                .containsExactlyInAnyOrderElementsOf(
                        Files.readAllLines(tmp.resolve("jdk-env.txt"), java.nio.charset.StandardCharsets.ISO_8859_1));
        assertThat(Files.readAllLines(env, java.nio.charset.StandardCharsets.ISO_8859_1)).hasSize(2).contains("A=1");
        assertThat(System.getenv()).isNotEmpty(); // the JVM has an environment of its own; none of it reaches the child
        log("08 chdir (addchdir_np) gives %s; the child's environment is exactly the %d variables given, none of the "
                + "JVM's %d", tmp.toRealPath(), snapshot.size(), System.getenv().size());
    }

    // --- 9. errors ---

    @Test
    void check09_startErrorsAreTheOnesOfProcessBuilder() throws Exception {
        Path missing = tmp.resolve("missing");
        Path notExecutable = Files.writeString(tmp.resolve("data.txt"), "x");
        List<String> lines = new ArrayList<>();
        for (Path path : List.of(missing, notExecutable, tmp)) {
            String ours = failure(() -> PosixSpawn.spawn(Spec.of(path)));
            String theirs = failure(() -> new ProcessBuilder(path.toString()).start());
            // ours: "error=2, No such file or directory"; the JDK 25: "Exec failed, error: 2 (No such file or directory) ".
            String number = ours.substring(ours.indexOf('=') + 1, ours.indexOf(','));
            String text = ours.substring(ours.indexOf(", ") + 2);
            assertThat(theirs).contains("error: " + number + " (" + text + ")");
            lines.add(ours + " | JDK: " + theirs.substring(theirs.indexOf("Exec failed")).strip());
        }
        log("09 start errors carry the same errno and text as ProcessBuilder: %s", lines);
    }

    // --- bonus: signal state of the child (what survives exec) ---

    @Test
    void check12_signalStateOfTheChild() throws Exception {
        Spawned group = start(sleep().group(PosixSpawn.OWN_GROUP));
        Process java = startByJava(new ProcessBuilder(exe("sleep").toString(), "30"));
        Process shield = startByJava(new ProcessBuilder("/bin/sh", "-c", SH_SHIELD, exe("sleep").toString(), "30"));
        Thread.sleep(200); // the shell has become sleep
        String ours = signals(group.pid());
        String jdk = signals((int) java.pid());
        String shielded = signals((int) shield.pid());
        assertThat(ours).isEqualTo("SigBlk=0000000000000000 SigIgn=0000000000000000");
        assertThat(shielded).contains("SigIgn=0000000000000006"); // INT and QUIT ignored
        log("12 signals of the child: posix_spawn(SETSIGDEF+SETSIGMASK) %s | ProcessBuilder %s | sh+trap %s", ours, jdk,
                shielded);
    }

    // --- 10. latency ---

    @Test
    void check10_latencyOfAStart() throws Exception {
        Path trueExe = exe("true");
        long[] posix = new long[200];
        long[] jdk = new long[200];
        long[] shield = new long[200];
        for (int i = -50; i < 200; i++) { // the first 50 warm up
            long t0 = System.nanoTime();
            assertThat(PosixSpawn.spawn(Spec.of(trueExe)).exit().get(10, TimeUnit.SECONDS)).isZero();
            long t1 = System.nanoTime();
            new ProcessBuilder(trueExe.toString()).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor();
            long t2 = System.nanoTime();
            new ProcessBuilder("/bin/sh", "-c", SH_SHIELD, trueExe.toString())
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor();
            long t3 = System.nanoTime();
            if (i >= 0) {
                posix[i] = t1 - t0;
                jdk[i] = t2 - t1;
                shield[i] = t3 - t2;
            }
        }
        log("10 start + wait of `true`, median of 200 (us): posix_spawn=%d, ProcessBuilder=%d, ProcessBuilder via "
                        + "sh+trap=%d", median(posix) / 1000, median(jdk) / 1000, median(shield) / 1000);
        assertThat(median(posix)).isLessThan(TimeUnit.SECONDS.toNanos(1));
    }

    // --- helpers ---

    /** The message of the {@link IOException} that a start must throw. */
    private static String failure(java.util.concurrent.Callable<?> start) throws Exception {
        try {
            start.call();
        } catch (IOException e) {
            return e.getMessage();
        }
        throw new AssertionError("the start was expected to fail");
    }

    private Spawned start(Spec spec) throws IOException {
        Spawned spawned = PosixSpawn.spawn(spec);
        started.add(spawned);
        return spawned;
    }

    private Process startByJava(ProcessBuilder builder) throws IOException {
        Process process = builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        startedByJava.add(process);
        return process;
    }

    private static Spec sleep() {
        return Spec.of(exe("sleep"), "30");
    }

    private static Spec sh(String script) {
        return Spec.of(Path.of("/bin/sh"), "-c", script);
    }

    private static Path exe(String name) {
        for (String dir : List.of("/usr/bin", "/bin")) {
            Path candidate = Path.of(dir, name);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no " + name);
    }

    private record Stat(char state, int ppid, int pgrp, int session) { }

    private static Stat procStat(int pid) throws IOException {
        String line = Files.readString(Path.of("/proc", String.valueOf(pid), "stat"));
        String[] fields = line.substring(line.lastIndexOf(')') + 2).split(" ");
        return new Stat(fields[0].charAt(0), Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
                Integer.parseInt(fields[3]));
    }

    private static String signals(int pid) throws IOException {
        Map<String, String> wanted = new java.util.LinkedHashMap<>();
        for (String line : Files.readAllLines(Path.of("/proc", String.valueOf(pid), "status"))) {
            if (line.startsWith("SigIgn:") || line.startsWith("SigBlk:")) {
                wanted.put(line.substring(0, line.indexOf(':')), line.substring(line.indexOf(':') + 1).strip());
            }
        }
        return wanted.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(" "));
    }

    private static List<String> fds(int pid) throws IOException {
        try (Stream<Path> entries = Files.list(Path.of("/proc", String.valueOf(pid), "fd"))) {
            return entries.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static List<String> pipesOfTheJvm() throws IOException {
        try (Stream<Path> entries = Files.list(Path.of("/proc/self/fd"))) {
            return entries.map(p -> {
                try {
                    return Files.readSymbolicLink(p).toString();
                } catch (IOException e) {
                    return "";
                }
            }).filter(target -> target.startsWith("pipe:")).toList();
        }
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static void log(String format, Object... args) {
        String line = String.format(format, args);
        System.out.println("[prototype] " + line);
        try {
            Files.createDirectories(RESULTS.getParent());
            Files.writeString(RESULTS, line + System.lineSeparator(), java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
