# Profiling and benchmarking PowerJ

PowerJ is an ordinary Java 25 application: every standard JDK tool works on it. This guide says which tool answers
which question, and how to get **accurate CPU and memory figures** out of it. Requirement: FR-77 in the
[specification](SPECIFICATION.md).

- [Choosing the tool](#choosing-the-tool)
- [Perf mode: where does the time go in my line?](#perf-mode-where-does-the-time-go-in-my-line)
- [Preparing the runtime](#preparing-the-runtime)
- [Accurate CPU measurement](#accurate-cpu-measurement)
- [Accurate memory measurement](#accurate-memory-measurement)
- [Reading the PowerJ events and threads](#reading-the-powerj-events-and-threads)
- [Micro-benchmarks (JMH)](#micro-benchmarks-jmh)
- [Startup time](#startup-time)
- [Pitfalls](#pitfalls)

## Choosing the tool

| Question | Tool | Cost |
|---|---|---|
| Which stage of my pipeline is slow? | **Perf mode** (Alt+P) | none to set up |
| Which Java methods burn CPU? Who allocates? | **Flight Recorder** (JFR) + JDK Mission Control | ~1-2 %, safe on a real session |
| Same, with native frames and blocked time | **async-profiler** | low |
| How much faster/slower is `Parser.parse` after my change? | **JMH** (`powerj-bench`) | a build profile |
| How big is the heap, what is in it? | `jcmd GC.heap_info`, `GC.class_histogram`, `GC.heap_dump` | on demand |
| Where does the non-heap memory go? | **Native Memory Tracking** | ~5-10 % |

## Perf mode: where does the time go in my line?

Press **Alt+P** (prompt `PJ⏱`). While you type, the **plan** of the line is shown (what each stage resolves to, and in
which thread it runs); after Enter, the **wall-clock time** of the line, of each statement and of each stage follows the
output, with the objects read and produced and the time to the first value (FR-76).

- The stages of a pipeline run at the same time, so their durations overlap: look at the statement figure for the cost
  of the pipeline, and at `first` to spot a stage that waits for its whole input (`sort`, `group`).
- These are **wall-clock times**: time spent blocked (waiting for a slow disk, for a slower downstream stage) counts.

### Automatic Flight Recorder analysis, command by command

In perf mode every line is also recorded by the JVM's Flight Recorder, **in the shell's own process** (nothing to attach,
no option to give), and analysed as soon as it has run (FR-78). Under the wall-clock report you get the CPU, the
allocation, the garbage collections and the waiting of that command, then one line per stage with its three hottest
methods:

```text
jfr · cpu 143 ms (79 %) · alloc 48.2 MB · gc 2× 7.1 ms · wait 31 ms
  where        cpu  98 ms · alloc 31.0 MB · wait 2 ms  hot Foo.test:41 41 %, Baz.size:88 18 %
  sort         cpu  45 ms · alloc 17.2 MB · wait 29 ms  hot Arrays.sort:12 63 %
recording ~/.powerj/perf/perf-1792507896426.jfr
```

How to read it:

- **cpu** is `samples × 5 ms` (the Java threads are sampled every 5 ms): an estimate, good when there are many samples.
  With fewer than 20 (a command of ~100 ms or less) the summary says so; run the line a few times, or on more data.
- **wait** is time spent parked, sleeping, blocked on a monitor or in file I/O (events of 1 ms or more). A stage that waits
  for its upstream stage shows a large `wait` and almost no `cpu`: that is the pipeline working, not a problem. A stage
  with a high `cpu` and a hot method is where to optimise.
- **alloc** is sampled, and counts what was allocated, not what stays alive (for the live set use `GC.class_histogram`
  below). `gc` counts the collections that happened *during the line*.
- The hot-method line numbers are exact thanks to `-XX:+DebugNonSafepoints`, set in the packaged launcher.

The detail is available as objects, which a pipeline can filter and sort:

```text
PJ⏱ > perf --stages | sort cpu
PJ⏱ > perf | where percent > 20          # dominant methods
PJ⏱ > perf --allocations                 # where the objects are created
PJ⏱ > perf --waits                       # park / sleep / monitor / file, by stage
PJ⏱ > perf --gc
PJ⏱ > perf --file                        # path of the recording
```

**Keeping the recordings.** The `.jfr` file of each line goes to `~/.powerj/perf/`; the session variable
`POWERJ_PERF_KEEP` says how many to keep, read at every line: unset or `0` deletes the file once analysed, `1` keeps only
the last, `N` keeps the last N. Being a session variable, it can be set from the profile:

```text
# ~/.powerj/profile.pj
env --set POWERJ_PERF_KEEP=5
```

A kept file opens in JDK Mission Control or with `jfr print` / `jfr view`, like any recording below. Without the
`jdk.jfr` module (a slimmed image), perf mode says so once and keeps giving the plan and the wall-clock report.

## Preparing the runtime

The packaged runtime (`./mvnw -Pdist verify`, image in `powerj-dist/target/jpackage/powerj/`) already contains what
the tools need: `jdk.jfr`, `jdk.management`, `jdk.management.jfr`, `jdk.management.agent`, `jdk.jcmd`, `jdk.attach`,
and the Java debug attributes (line numbers in profiles and stack traces). Check:

```bash
powerj/lib/runtime/bin/java --list-modules | grep -E 'jdk.jfr|jdk.attach|jdk.jcmd'
```

The `jcmd` and `jfr` command-line tools are those of a JDK 25 installation (look in `powerj/lib/runtime/bin` too: the
image contains the `jdk.jcmd` and `jdk.jfr` modules, which bring their launchers). They talk to the running shell through
the attach API, so they must run as the same user.

### Giving JVM options to the packaged launcher

Pick one:

1. **`JAVA_TOOL_OPTIONS`**, read by every JVM, nothing to rebuild (a `Picked up JAVA_TOOL_OPTIONS` line is printed):

   ```bash
   JAVA_TOOL_OPTIONS="-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -Xmx2g" powerj/bin/powerj
   ```

2. **`powerj/lib/app/powerj.cfg`**, section `[JavaOptions]`, one `java-options=` line per option, applies to every start
   of this image.
3. **At build time**, with the Maven property `dist.extra.args`, passed to `jpackage` (a repeated `--java-options`):

   ```bash
   ./mvnw -Pdist verify "-Ddist.extra.args=--java-options -XX:+DebugNonSafepoints --java-options -Xmx2g"
   ```

   Slimmest image (no JFR, no debug attributes), for distribution rather than for investigation:

   ```bash
   ./mvnw -Pdist verify -Ddist.add.modules=java.se,jdk.unsupported \
     "-Ddist.jlink.options=--strip-java-debug-attributes --no-header-files --no-man-pages"
   ```

### `-XX:+DebugNonSafepoints`: always use it for CPU profiles

By default the JVM only knows exactly where it is at *safepoints*, so samples are attributed to the nearest safepoint,
often a loop back-edge or a method return, not to the line that is really hot. Both JFR and async-profiler give much
more accurate results with `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints`. It does not change the generated
code.

## Accurate CPU measurement

### Flight Recorder

Record a whole run, from a script so that it is repeatable:

```bash
JAVA_TOOL_OPTIONS="-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints \
  -XX:StartFlightRecording=filename=pj.jfr,settings=profile,dumponexit=true" \
  powerj/bin/powerj -c 'ls -r /usr | where { f -> f.size > 10kb } | sort size | first 20'
```

or attach to a running interactive shell (`jcmd -l` lists the JVMs and their pid):

```bash
jcmd <pid> JFR.start name=pj settings=profile
#   ... work in the shell ...
jcmd <pid> JFR.dump name=pj filename=pj.jfr
jcmd <pid> JFR.stop name=pj
```

Open `pj.jfr` in [JDK Mission Control](https://adoptium.net/jmc/) (Method Profiling, Flame View), or from the command
line:

```bash
jfr print --events jdk.ExecutionSample pj.jfr | head
jfr view hot-methods pj.jfr
jfr view allocation-by-site pj.jfr
```

What makes the numbers trustworthy:

- **`settings=profile`** samples Java stacks every 10 ms (the default `default` setting uses 20 ms).
- Sampling is **statistical**: a method seen in 3 samples out of 1,000 is noise. Record long enough (thousands of
  samples), repeat the run, and compare ratios rather than single values.
- **JIT warm-up**: the first seconds run interpreted code and compile it. For a short command, either record many
  repetitions of it or discard the first seconds (`jfr print --events ... --stack-depth` plus a time filter in JMC).
- JFR samples **running Java threads only**. Time spent blocked (I/O, `BlockingQueue.put/take` between stages, native
  commands) does not appear in `jdk.ExecutionSample`; use `jdk.JavaMonitorWait`, `jdk.FileRead`, `jdk.SocketRead` or the
  wall-clock mode of async-profiler for that.
- **Virtual threads** (the pipeline stages): CPU samples are attributed to the carrier threads (`ForkJoinPool-1-worker-N`)
  while a virtual thread runs; JFR stacks show the virtual thread's frames. A stage's time spent parked is not CPU.
  `jdk.VirtualThreadPinned` (enabled in `profile`) reports pinning.
- Per-thread CPU time: `jdk.ThreadCPULoad` (every 10 s with `profile`) and `jdk.CPULoad` for the whole process.

### async-profiler

[async-profiler](https://github.com/async-profiler/async-profiler) needs no safepoints at all and also sees native
frames (JLine, the FFM console, the GC):

```bash
# at start
JAVA_TOOL_OPTIONS="-agentpath:/opt/async-profiler/lib/libasyncProfiler.so=start,event=cpu,interval=1ms,file=cpu.html,flamegraph" \
  powerj/bin/powerj -c '...'

# attached to a running shell (needs jdk.attach, present in the image)
asprof -d 30 -e cpu -f cpu.html <pid>
asprof -d 30 -e wall -t -f wall.html <pid>   # where the time goes, blocked or not, per thread
```

`-e cpu` answers "who uses the CPU", `-e wall` answers "where does the elapsed time go" (including blocked pipeline
stages); `-t` splits per thread, so the `powerj-stage-N` names appear.

### Process-level check

For a plain CPU figure that does not depend on a profiler: `/usr/bin/time -v powerj/bin/powerj -c '...'` (user + system
time, peak RSS), or `jcmd <pid> Thread.print` / `jcmd <pid> VM.info` for a snapshot.

## Accurate memory measurement

There are three different memories; measure the one you mean.

| Memory | What it is | How to measure |
|---|---|---|
| **Live heap** | objects still reachable | `jcmd <pid> GC.class_histogram` after a `GC.run`; `GC.heap_dump` + Mission Control / Eclipse MAT |
| **Allocation rate** | objects created (most die young) | JFR `jdk.ObjectAllocationSample`; `jfr view allocation-by-site` |
| **Process footprint (RSS)** | heap + metaspace + thread stacks + code cache + direct buffers + native | `ps -o rss`, `/usr/bin/time -v`, Native Memory Tracking |

### Heap

```bash
jcmd <pid> GC.heap_info                                   # used / committed per region
jcmd <pid> GC.run                                         # force a full GC: what remains is the live set
jcmd <pid> GC.class_histogram | head -30                  # live objects per class
jcmd <pid> GC.heap_dump /tmp/pj.hprof                     # full dump, analyse with Mission Control or MAT
```

Always measure the **live set after a GC**, not the heap in use at a random time: the JVM happily fills the heap with
garbage up to `-Xmx` before collecting. To see the collector at work:

```bash
JAVA_TOOL_OPTIONS="-Xlog:gc*:file=gc.log:time,uptime" powerj/bin/powerj -c '...'
```

Fix the heap size (`-Xms` = `-Xmx`, e.g. `-Xms512m -Xmx512m`) when comparing two runs, otherwise the JVM sizes the heap
from the machine and the runs are not comparable. `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp` keeps the
evidence of a crash (the shell keeps an 8 MB reserve to survive an `OutOfMemoryError` and print it, see §3.14).

### Allocation (who creates the garbage)

JFR with the `profile` setting records allocation samples with their stacks:

```bash
jfr view allocation-by-site pj.jfr
jfr print --events jdk.ObjectAllocationSample --stack-depth 8 pj.jfr | less
```

This is the right tool for a pipeline over a large tree (`ls -r | ...`): it shows whether the pressure comes from
`FileEntry` records, `Path` objects, formatting or boxing. The `FileEntry`-per-file cost is by design (NFR-04 needs the
heap to stay flat, which the bounded queues of 256 objects between stages guarantee: check that the live set after
`GC.run` does not grow with the size of the tree).

### Non-heap and RSS

```bash
JAVA_TOOL_OPTIONS="-XX:NativeMemoryTracking=summary" powerj/bin/powerj
jcmd <pid> VM.native_memory summary                       # heap, metaspace, threads, code, GC, internal...
jcmd <pid> VM.native_memory baseline                      # then, later:
jcmd <pid> VM.native_memory summary.diff                  # what grew since the baseline
```

Platform threads have a 16 MB reserved stack (`powerj-command`, `powerj-preview`): the *reserved* size shows in the
virtual size but only the touched pages count in RSS. Pipeline stages are virtual threads, whose stacks live on the
heap.

## Reading the PowerJ events and threads

Custom Flight Recorder events (FR-76b) are recorded by any recording that is running; switch them off with
`powerj.Stage#enabled=false` if you only want the JVM's own events:

```bash
jfr print --events powerj.Command,powerj.Stage pj.jfr
jfr summary pj.jfr | grep powerj          # how many of each
```

| Event | Fields |
|---|---|
| `powerj.Command` | `commands` (names, joined by ` \| `), `statements`, duration |
| `powerj.Stage` | `command`, `kind` (`builtin` / `cmdlet` / `native` / `expression`), `in`, `out`, duration |

Arguments are never recorded. Line by line, the stage events give the same figures as Perf mode, inside a recording that
also holds the CPU samples and the allocations of the same period.

Thread names in a thread dump (`jcmd <pid> Thread.print`), in JFR and in async-profiler `-t` views:

| Name | Role |
|---|---|
| `powerj-command` | executes the line typed (the last stage of its pipeline runs here) |
| `powerj-stage-N` | pipeline stage N, other than the last (virtual thread) |
| `powerj-walk-N` | parallel file-tree walk (`ls -r -p`, `cp -r -p`, `rm -r -p`; virtual) |
| `powerj-stdin`, `powerj-pump` | feed and drain a native command (virtual) |
| `powerj-preview`, `powerj-preview-deadline` | preview mode evaluation and its time limit |
| `powerj-index-java` | background indexing of the Java classes for completion |
| `powerj-shutdown` | terminal restoration at exit |

## Micro-benchmarks (JMH)

For "did this change make the parser faster?" a profile is the wrong instrument: use [JMH](https://github.com/openjdk/jmh),
which handles warm-up, forking, dead-code elimination and statistics. The benchmarks live in the `powerj-bench`
module, outside the default build:

```bash
./mvnw -Pbench -DskipTests package
java -jar powerj-bench/target/benchmarks.jar                       # everything
java -jar powerj-bench/target/benchmarks.jar parse plan -p files=200   # a selection
java -jar powerj-bench/target/benchmarks.jar -prof gc              # + allocation per operation (B/op)
java -jar powerj-bench/target/benchmarks.jar -prof jfr             # + a JFR recording per benchmark
```

| Benchmark | Measures |
|---|---|
| `lex`, `parse` | tokenizing and parsing one pipeline line (run on every keystroke by highlighting and preview) |
| `plan` | the plan of perf mode (also on every keystroke) |
| `walkOnly` | `ls -r \| count` over a generated tree of `files` files |
| `pipeline` | `ls -r --filter *.txt \| where … \| sort name \| first 5` over the same tree |

Reading the output: *Score* is the average time per operation, *Error* the 99.9 % confidence interval. If the error is
as large as the score the benchmark is noisy; run more iterations (`-i 10 -wi 5`) on a quiet machine. Compare two
builds with the **same** parameters and on the same machine: absolute numbers do not travel. `-prof gc` is the most
reliable memory figure of all: `gc.alloc.rate.norm` is the bytes allocated per operation, independent of timing.

Add a benchmark by adding a `@Benchmark` method to `ShellBenchmarks` (or a new class) in
`powerj-bench/src/main/java/io/powerj/bench/`. The module works on the class path, not the module path, to keep JMH's
generated code simple; the cmdlets are found through the `META-INF/services` file as usual.

## Startup time

Startup is not a micro-benchmark: measure the whole process, several times, and look at the median.

```bash
for i in 1 2 3 4 5; do /usr/bin/time -f '%e s %M KB' powerj/bin/powerj --no-profile -c 'exit 0'; done
```

`--no-profile` leaves out your `profile.pj`. To see where the startup time goes, record it:

```bash
JAVA_TOOL_OPTIONS="-XX:StartFlightRecording=filename=startup.jfr,settings=profile" powerj/bin/powerj --no-profile -c 'exit 0'
jfr summary startup.jfr
jfr print --events jdk.ClassLoad,jdk.Compilation startup.jfr | head
```

`-Xshare:auto` (the default) uses the JDK's class-data-sharing archive for the JDK's own classes; `-Xlog:class+load`
prints every class loaded, with its source (`shared objects file` or the jar/module it came from), which shows how much
of the start-up is PowerJ and JLine classes that are not archived.

## Pitfalls

- **Profile the packaged image or the same JDK you ship**, not an IDE run: class-path vs module-path and JIT flags differ.
- **Do not profile with JaCoCo or a debugger attached**: the coverage agent instruments every class
  (`-Djacoco.skip=true` for builds you measure).
- **Interactive time includes you.** The REPL waits for keys; record non-interactively (`-c`, a script) or attach and
  start the recording right before the command.
- **Warm up.** The first run of a line is dominated by class loading and JIT. Run it three times and look at the third.
- **`-Xint` / `-XX:TieredStopAtLevel=1`** change what is hot; do not use them to "make profiles clearer".
- **Share recordings carefully.** `powerj.*` events carry command names, never arguments, but a recording also holds
  thread dumps and system properties; read `jfr summary` and `jfr print --events jdk.InitialSystemProperty` first.
