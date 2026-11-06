package io.powerj.core.nativeexec;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.nativeexec.WindowsSpawn.Spawned;
import io.powerj.core.nativeexec.WindowsSpawn.Spec;
import io.powerj.core.nativeexec.WindowsSpawn.Stdio;

/**
 * Issue #128 (Windows): start a background process in a new process group with {@code CreateProcessW} through the FFM
 * API. Each test is one check of the plan ({@code docs/plans/background-process-group.md}, Phase 2); the numbers are
 * printed and appended to {@code target/background/windows-spawn-results.txt}.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsSpawnTest {

    private static final Path RESULTS = Path.of("target", "background", "windows-spawn-results.txt");
    private static final Pattern ERROR_NUMBER = Pattern.compile("error=(\\d+)");

    @TempDir
    Path tmp;

    private final List<Spawned> started = new ArrayList<>();
    private final List<Process> startedByJava = new ArrayList<>();

    @AfterEach
    void stopEverything() {
        started.forEach(Spawned::terminate);
        startedByJava.forEach(Process::destroyForcibly);
    }

    // --- 1. Ctrl+C ---

    @Test
    void check01_ctrlCDoesNotReachAProcessStartedInANewProcessGroup() throws Exception {
        // The Ctrl+C goes to every process of the console of the sender: the probe has a console of its own, with the
        // standard handles of that console as a program in a terminal has them, so that it reaches nothing of the build.
        Path result = tmp.resolve("console.txt");
        Spawned probe = start(javaProgram(WindowsConsoleProbe.class, result.toString()).flags(WindowsTestSupport.CREATE_NEW_CONSOLE)
                .standardHandles(false));
        Integer code = probe.exit().get(45, TimeUnit.SECONDS);

        String report = Files.exists(result) ? Files.readString(result).replace("\r\n", "\n") : "(no result file)";
        Map<String, String> r = keyValues(result);
        log("01 Ctrl+C on a console of its own, probe exit=%d:%n%s", code, report.stripTrailing());
        assertThat(r).as(report).doesNotContainKey("error");
        assertThat(r.get("ctrl_c_sent")).as(report).isEqualTo("true");
        // What the process group has to prove: the flag spares the process, whatever its standard handles are...
        assertThat(r.get("B_alive_before")).as(report).isEqualTo("true");
        assertThat(r.get("B_ended")).as("B, started in a new process group, must survive Ctrl+C: " + report)
                .isEqualTo("false");
        assertThat(r.get("B2_ended")).as("B2, in a new process group with the handles of the console, must survive: "
                + report).isEqualTo("false");
        // Giving the console to B3 must not leave the standard output of this JVM inheritable by what it starts later.
        assertThat(r.get("stdout_inheritable_after")).as("the standard output handle is left as it was: " + report)
                .isEqualTo(r.get("stdout_inheritable_before"));
        assertThat(r.get("B3_ended")).as("B3, in a new process group with output and errors inherited, must survive: "
                + report).isEqualTo("false");
        // ... and that it is the flag, not our way of starting: the same start without it dies of Ctrl+C.
        assertThat(r.get("C_ended")).as("the control C, started by CreateProcessW without the flag, must die: " + report)
                .isEqualTo("true");
        // The premise of issue #128, measured on the Windows job: a console program started by ProcessBuilder as PowerJ
        // starts a background command at a terminal (A2: input from the null device, output and errors inherited) or with
        // everything inherited (A3) is on the console of its parent and dies of Ctrl+C, so it is the defect that the new
        // process group removes (B2 has the same handles and survives).
        assertThat(r.get("A2_attached")).as(report).isEqualTo("true");
        assertThat(r.get("A2_ended")).as("A2 (ProcessBuilder, as PowerJ at a terminal) dies of Ctrl+C: " + report)
                .isEqualTo("true");
        assertThat(r.get("A3_ended")).as("A3 (ProcessBuilder, inheritIO) dies of Ctrl+C: " + report).isEqualTo("true");
        // A (ProcessBuilder, all three streams redirected) is not on the console of its parent, so Ctrl+C does not reach
        // it: the JDK detaches it. Recorded in the report, not asserted (the mechanism is the JDK's to change).
    }

    // --- 2. exit code ---

    @Test
    void check02_exitCodeAndProcessHandle() throws Exception {
        Spawned seven = start(Spec.of(cmd(), "/c", "exit", "7"));
        assertThat(seven.exit().get(30, TimeUnit.SECONDS)).isEqualTo(7);

        Spawned slow = start(Spec.of(ping(), "-n", "3", "127.0.0.1"));
        ProcessHandle handle = ProcessHandle.of(slow.pid()).orElseThrow();
        assertThat(handle.isAlive()).isTrue();
        handle.onExit().get(30, TimeUnit.SECONDS);
        assertThat(slow.exit().get(30, TimeUnit.SECONDS)).isZero();
        log("02 exit codes: cmd /c exit 7 -> %d; ProcessHandle.isAlive/onExit work on a CreateProcessW child, "
                + "ping -n 3 -> %d", seven.exit().get(), slow.exit().get());
    }

    @Test
    void check02b_noStatusLostAndNoHandleLeakInTwoHundredRuns() throws Exception {
        int before = WindowsTestSupport.handleCount();
        int threads = 4;
        int perThread = 50;
        long wrong = 0;
        long begin = System.nanoTime();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Long>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    long bad = 0;
                    for (int i = 0; i < perThread; i++) {
                        Spawned child = WindowsSpawn.spawn(Spec.of(cmd(), "/c", "exit", "5"));
                        if (child.exit().get(30, TimeUnit.SECONDS) != 5) {
                            bad++;
                        }
                    }
                    return bad;
                }));
            }
            for (Future<Long> future : futures) {
                wrong += future.get(120, TimeUnit.SECONDS);
            }
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        int after = WindowsTestSupport.handleCount();
        assertThat(wrong).isZero();
        assertThat(after - before).as("handles of the JVM before=%d after=%d", before, after).isLessThan(40);
        log("02b %d runs on %d threads: %d wrong codes, %d ms, handles of the JVM before=%d after=%d", threads * perThread,
                threads, wrong, millis, before, after);
    }

    // --- 3. kill ---

    @Test
    void check03_terminateProcessAndProcessHandleStopTheChild() throws Exception {
        Spawned ours = start(Spec.of(ping(), "-t", "127.0.0.1"));
        Spawned gentle = start(Spec.of(ping(), "-t", "127.0.0.1"));
        Spawned forcible = start(Spec.of(ping(), "-t", "127.0.0.1"));
        assertThat(ours.terminate()).isTrue();
        ProcessHandle.of(gentle.pid()).orElseThrow().destroy();
        ProcessHandle.of(forcible.pid()).orElseThrow().destroyForcibly();
        assertThat(ours.exit().get(30, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(gentle.exit().get(30, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(forcible.exit().get(30, TimeUnit.SECONDS)).isEqualTo(1);
        log("03 TerminateProcess -> exit %d; ProcessHandle.destroy() -> %d; destroyForcibly() -> %d", ours.exit().get(),
                gentle.exit().get(), forcible.exit().get());
    }

    // --- 4. redirections and pipeline ---

    @Test
    void check04_redirectionsAndStandardInputOnTheNullDevice() throws Exception {
        Path out = tmp.resolve("out.txt");
        Path err = tmp.resolve("err.txt");
        Spec echo = javaProgram(StdioEcho.class);

        assertThat(start(echo.out(Stdio.file(out)).err(Stdio.file(err))).exit().get(60, TimeUnit.SECONDS)).isZero();
        assertThat(lines(out)).containsExactly("out", "stdin=0"); // > truncates; stdin is NUL: the program ends at once
        assertThat(lines(err)).contains("err");

        assertThat(start(echo.out(Stdio.append(out)).err(Stdio.append(err))).exit().get(60, TimeUnit.SECONDS)).isZero();
        assertThat(lines(out)).containsExactly("out", "stdin=0", "out", "stdin=0"); // >> appends
        assertThat(lines(err).stream().filter("err"::equals).count()).isEqualTo(2);

        Path both = tmp.resolve("both.txt");
        assertThat(start(echo.out(Stdio.file(both)).err(Stdio.STDOUT)).exit().get(60, TimeUnit.SECONDS)).isZero();
        assertThat(lines(both)).containsExactly("out", "err", "stdin=0"); // 2>&1: one handle for both
        log("04 redirections: > truncates, >> appends (FILE_APPEND_DATA), 2>&1 shares one handle, stdin on NUL ends at once");
    }

    @Test
    void check04a_twoJobsCanWriteTheSameFile() throws Exception {
        // On Linux and with FileOutputStream a file can be opened for writing by two processes; a handle opened with
        // FILE_SHARE_READ only made the second open fail with error 32 (sharing violation).
        Path shared = tmp.resolve("shared.txt");
        Spawned first = start(Spec.of(ping(), "-n", "3", "127.0.0.1").out(Stdio.file(shared)));
        Spawned second = start(Spec.of(cmd(), "/c", "echo", "second").out(Stdio.append(shared)));
        Spawned third = start(Spec.of(cmd(), "/c", "echo", "third").out(Stdio.file(shared)));
        assertThat(second.exit().get(30, TimeUnit.SECONDS)).isZero();
        assertThat(third.exit().get(30, TimeUnit.SECONDS)).isZero();
        assertThat(first.exit().get(30, TimeUnit.SECONDS)).isZero();
        log("04a three processes wrote the same file with > and >> without a sharing violation");
    }

    @Test
    void check04b_pipelineWithAPipeAndTheHandleList() throws Exception {
        Path input = tmp.resolve("in.txt");
        Files.writeString(input, "b\r\na\r\n");
        Path sorted = tmp.resolve("sorted.txt");
        long[] pipe = WindowsSpawn.pipe();
        Spawned left;
        Spawned right;
        try {
            left = start(Spec.of(cmd(), "/c", "type", input.toString()).out(Stdio.handle(pipe[1])));
            right = start(Spec.of(system32("sort.exe")).in(Stdio.handle(pipe[0])).out(Stdio.file(sorted)));
        } finally {
            WindowsSpawn.close(pipe[0]);
            WindowsSpawn.close(pipe[1]);
        }
        assertThat(left.exit().get(30, TimeUnit.SECONDS)).isZero();
        // sort only ends when it sees the end of its input: no other process holds the write end of the pipe.
        assertThat(right.exit().get(30, TimeUnit.SECONDS)).isZero();
        assertThat(lines(sorted)).containsExactly("a", "b");
        log("04b pipeline `type | sort > f`: the end of the pipe reaches sort, so the write end is held by nobody else");
    }

    @Test
    void check04c_theHandleListKeepsTheOtherInheritableHandlesOut() throws Exception {
        // A pipe whose two ends can be inherited. Once the parent has closed its write end, a read on the read end
        // fails with ERROR_BROKEN_PIPE as soon as no other process holds the write end.
        long[] guardedPipe = WindowsSpawn.pipe();
        Spawned guarded = start(Spec.of(ping(), "-t", "127.0.0.1"));
        WindowsSpawn.close(guardedPipe[1]);
        CompletableFuture<Integer> guardedRead = readInBackground(guardedPipe[0]);
        int guardedResult = guardedRead.get(10, TimeUnit.SECONDS);
        WindowsSpawn.close(guardedPipe[0]);

        // The control: without the handle list the child inherits every inheritable handle, the write end included.
        long[] leakyPipe = WindowsSpawn.pipe();
        Spawned leaky = start(Spec.of(ping(), "-t", "127.0.0.1").handleList(false));
        WindowsSpawn.close(leakyPipe[1]);
        CompletableFuture<Integer> leakyRead = readInBackground(leakyPipe[0]);
        boolean leakyBlocked;
        try {
            leakyRead.get(2, TimeUnit.SECONDS);
            leakyBlocked = false;
        } catch (TimeoutException _) {
            leakyBlocked = true;
        }
        assertThat(leaky.terminate()).isTrue();
        int leakyAfterKill = leakyRead.get(10, TimeUnit.SECONDS);
        WindowsSpawn.close(leakyPipe[0]);

        log("04c with the handle list the read ended at once (error %d); without it the child held the write end: read "
                + "blocked=%b, then error %d once the child was killed", guardedResult, leakyBlocked, leakyAfterKill);
        assertThat(guarded.exit().isDone()).isFalse();
        assertThat(guardedResult).isEqualTo(WindowsTestSupport.ERROR_BROKEN_PIPE);
        assertThat(leakyBlocked).as("the control must leak, or this test proves nothing").isTrue();
        assertThat(leakyAfterKill).isEqualTo(WindowsTestSupport.ERROR_BROKEN_PIPE);
    }

    // --- 5. command line ---

    @Test
    void check05_theChildReceivesTheArgumentsWeGave() throws Exception {
        List<String> tricky = List.of("plain", "with space", "with\"quote", "back\\slash", "trail\\", "trail space\\",
                "a\\\"b", "tab\there", "two  spaces", "C:\\Program Files\\x\\", "\"already quoted\"",
                "--flag=value with space");
        Path ours = tmp.resolve("ours.txt");
        Path jdk = tmp.resolve("jdk.txt");
        assertThat(start(javaProgram(ArgvEcho.class, withFirst(ours.toString(), tricky))).exit().get(60, TimeUnit.SECONDS)).isZero();
        runByJava(javaProcessBuilder(ArgvEcho.class, withFirst(jdk.toString(), tricky)));

        List<String> expected = tricky.stream().map(a -> "[" + a + "]").toList();
        List<String> oursGot = arguments(ours);
        List<String> jdkGot = arguments(jdk);
        List<String> jdkDiffers = new ArrayList<>();
        for (int i = 0; i < expected.size() && i < jdkGot.size(); i++) {
            if (!expected.get(i).equals(jdkGot.get(i))) {
                jdkDiffers.add(expected.get(i) + " -> " + jdkGot.get(i));
            }
        }
        log("05 arguments: CreateProcessW round trip %s (%d of %d); ProcessBuilder differs on %d: %s",
                oursGot.equals(expected) ? "exact" : "DIFFERENT: " + oursGot, oursGot.size(), expected.size(),
                jdkDiffers.size(), jdkDiffers);
        assertThat(oursGot).isEqualTo(expected);

        // An empty argument: informative only (the Java launcher may drop it for both).
        Path oursEmpty = tmp.resolve("ours-empty.txt");
        Path jdkEmpty = tmp.resolve("jdk-empty.txt");
        assertThat(start(javaProgram(ArgvEcho.class, oursEmpty.toString(), "", "x")).exit().get(60, TimeUnit.SECONDS)).isZero();
        runByJava(javaProcessBuilder(ArgvEcho.class, jdkEmpty.toString(), "", "x"));
        log("05 an empty argument gives %s with CreateProcessW and %s with ProcessBuilder", arguments(oursEmpty),
                arguments(jdkEmpty));
    }

    // --- 6. directory and environment ---

    @Test
    void check06_directoryAndEnvironmentAreTheOnesOfTheSession() throws Exception {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("SystemRoot", System.getenv("SystemRoot"));
        environment.put("windir", System.getenv("SystemRoot"));
        environment.put("POWERJ_PROBE", "deux é");
        Path file = tmp.resolve("env.txt");
        assertThat(start(javaProgram(ArgvEcho.class, file.toString()).env(environment).directory(tmp)).exit()
                .get(60, TimeUnit.SECONDS)).isZero();

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines).contains("POWERJ_PROBE=deux é", "USERPROFILE=null");
        String userDir = lines.stream().filter(l -> l.startsWith("user.dir=")).findFirst().orElseThrow().substring(9);
        assertThat(Path.of(userDir).toRealPath()).isEqualTo(tmp.toRealPath());
        log("06 the child sees the Unicode variable of the session and not USERPROFILE of the JVM; its directory is %s",
                userDir);
    }

    // --- 7. batch files ---

    @Test
    void check07_batchFilesAreLeftToProcessBuilder() throws Exception {
        Path script = tmp.resolve("hello.cmd");
        Files.writeString(script, "@echo off\r\necho hello %1\r\n");
        boolean refused = false;
        try {
            WindowsSpawn.spawn(Spec.of(script, "x"));
        } catch (UnsupportedOperationException _) {
            refused = true;
        }
        assertThat(refused).isTrue();
        Path out = tmp.resolve("hello.txt");
        runByJava(new ProcessBuilder(script.toString(), "x").redirectOutput(out.toFile()));
        assertThat(lines(out)).containsExactly("hello x");
        log("07 .cmd/.bat: refused by WindowsSpawn (the launcher hands them to ProcessBuilder, which runs this one: %s)",
                lines(out));
    }

    // --- 8. errors ---

    @Test
    void check08_startErrorsCarryTheNumbersOfProcessBuilder() throws Exception {
        Path missing = tmp.resolve("missing.exe");
        Path notExecutable = Files.writeString(tmp.resolve("data.txt"), "x");
        List<String> lines = new ArrayList<>();
        for (Path path : List.of(missing, notExecutable)) {
            int ours = errorNumber(failure(() -> WindowsSpawn.spawn(Spec.of(path))));
            int theirs = errorNumber(failure(() -> new ProcessBuilder(path.toString()).start()));
            assertThat(ours).isEqualTo(theirs);
            lines.add(path.getFileName() + ": error=" + ours);
        }
        // A folder: ProcessBuilder does not pass the name as the application, so its answer may differ. Informative.
        lines.add("a folder: CreateProcessW " + failure(() -> WindowsSpawn.spawn(Spec.of(tmp))) + ", ProcessBuilder "
                + failure(() -> new ProcessBuilder(tmp.toString()).start()));
        log("08 start errors, same numbers as ProcessBuilder: %s", lines);
    }

    // --- 9. latency ---

    @Test
    void check09_latencyOfAStart() throws Exception {
        long[] ours = new long[100];
        long[] jdk = new long[100];
        for (int i = -20; i < 100; i++) { // the first 20 warm up
            long t0 = System.nanoTime();
            assertThat(WindowsSpawn.spawn(Spec.of(cmd(), "/c", "exit", "0")).exit().get(30, TimeUnit.SECONDS)).isZero();
            long t1 = System.nanoTime();
            new ProcessBuilder(cmd().toString(), "/c", "exit", "0").redirectInput(ProcessBuilder.Redirect.from(new File("NUL")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor();
            long t2 = System.nanoTime();
            if (i >= 0) {
                ours[i] = t1 - t0;
                jdk[i] = t2 - t1;
            }
        }
        log("09 start + wait of `cmd /c exit 0`, median of 100 (ms): CreateProcessW=%d, ProcessBuilder=%d",
                median(ours) / 1_000_000, median(jdk) / 1_000_000);
        assertThat(median(ours)).isLessThan(TimeUnit.SECONDS.toNanos(2));
    }

    // --- helpers ---

    private Spawned start(Spec spec) throws IOException {
        Spawned spawned = WindowsSpawn.spawn(spec);
        started.add(spawned);
        return spawned;
    }

    private void runByJava(ProcessBuilder builder) throws Exception {
        Process process = builder.redirectInput(ProcessBuilder.Redirect.from(new File("NUL"))).start();
        startedByJava.add(process);
        if (builder.redirectOutput() == ProcessBuilder.Redirect.PIPE) {
            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        }
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
    }

    private static CompletableFuture<Integer> readInBackground(long handle) {
        CompletableFuture<Integer> result = new CompletableFuture<>();
        Thread.ofPlatform().daemon().name("powerj-read").start(() -> result.complete(WindowsTestSupport.readOneByte(handle)));
        return result;
    }

    /** The start, by {@link WindowsSpawn}, of the JVM running the {@code main} of a helper class of the tests. */
    private static Spec javaProgram(Class<?> main, String... args) {
        List<String> all = javaArguments(main, args);
        return Spec.of(javaExe(), all.toArray(String[]::new));
    }

    /** The same start, as a {@link ProcessBuilder}, to compare with what the JDK does. */
    private static ProcessBuilder javaProcessBuilder(Class<?> main, String... args) {
        List<String> command = new ArrayList<>();
        command.add(javaExe().toString());
        command.addAll(javaArguments(main, args));
        return new ProcessBuilder(command);
    }

    private static List<String> javaArguments(Class<?> main, String... args) {
        String classpath = classes(main) + File.pathSeparator + Path.of("target", "classes").toAbsolutePath();
        List<String> all = new ArrayList<>(List.of("-XX:TieredStopAtLevel=1", "-cp", classpath, main.getName()));
        all.addAll(Arrays.asList(args));
        return all;
    }

    private static String[] withFirst(String first, List<String> others) {
        List<String> all = new ArrayList<>();
        all.add(first);
        all.addAll(others);
        return all.toArray(String[]::new);
    }

    /**
     * The folder of the compiled test classes, where the helper programs are (they also need {@code target/classes}, where
     * {@link WindowsSpawn} is). Surefire runs from the folder of the module, and the code source of a test class would
     * name {@code target/classes}, because the test classes are patched into the module.
     */
    private static Path classes(Class<?> main) {
        Path folder = Path.of("target", "test-classes").toAbsolutePath();
        assertThat(folder.resolve(main.getName().replace('.', File.separatorChar) + ".class")).exists();
        return folder;
    }

    private static Path javaExe() {
        return Path.of(System.getProperty("java.home"), "bin", "java.exe");
    }

    private static Path system32(String name) {
        return Path.of(System.getenv("SystemRoot"), "System32", name);
    }

    private static Path cmd() {
        return system32("cmd.exe");
    }

    private static Path ping() {
        return system32("PING.EXE");
    }

    /** The lines of a file as the tests compare them: no carriage return, no "Picked up ..." line of the JVM. */
    private static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream().map(l -> l.replace("\r", ""))
                .filter(l -> !l.startsWith("Picked up ")).toList();
    }

    /** The {@code [argument]} lines written by {@link ArgvEcho}. */
    private static List<String> arguments(Path file) throws IOException {
        return lines(file).stream().filter(l -> l.startsWith("[")).toList();
    }

    private static Map<String, String> keyValues(Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int equals = line.indexOf('=');
                if (equals > 0) {
                    values.put(line.substring(0, equals), line.substring(equals + 1));
                }
            }
        }
        return values;
    }

    private static String readOrEmpty(Path file) throws IOException {
        return Files.exists(file) ? Files.readString(file) : "";
    }

    private static String failure(java.util.concurrent.Callable<?> start) throws Exception {
        try {
            start.call();
        } catch (IOException e) {
            return String.valueOf(e.getMessage());
        }
        return "(no failure)";
    }

    private static int errorNumber(String message) {
        Matcher matcher = ERROR_NUMBER.matcher(message);
        assertThat(matcher.find()).as(message).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static void log(String format, Object... args) {
        String line = String.format(format, args);
        System.out.println("[windows-spawn] " + line);
        try {
            Files.createDirectories(RESULTS.getParent());
            Files.writeString(RESULTS, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
