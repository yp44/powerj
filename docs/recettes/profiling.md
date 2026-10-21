# Acceptance test — Mini-iteration 26: profiling and benchmarking readiness

**Goal:** check that the packaged runtime can be recorded and profiled with the standard JDK tools, that the threads have readable names, that JVM options can be given to the launcher, and that the JMH benchmarks build and run. The guide being tested is `docs/profiling.md`.

## Preparation

`./mvnw -Pdist verify` (image in `powerj-dist/target/jpackage/powerj/`). A JDK 25 on the PATH for `jcmd` and `jfr`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `powerj/lib/runtime/bin/java --list-modules \| grep -E 'jdk.jfr\|jdk.jcmd\|jdk.attach\|jdk.management'` | The six modules are listed. |
| 2 | `JAVA_TOOL_OPTIONS="-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:StartFlightRecording=filename=pj.jfr,settings=profile,dumponexit=true" powerj/bin/powerj -c 'ls -r /usr \| where { f -> f.size > 10kb } \| sort size \| first 5'` | The result is displayed; `pj.jfr` exists. |
| 3 | `jfr view hot-methods pj.jfr` | A table of methods with their line numbers (e.g. `Foo.bar:123`), not `Unknown`. |
| 4 | `jfr print --events powerj.Stage pj.jfr` | Stage events for `ls`, `where`, `sort`, `first`, with `kind = cmdlet`, `in` / `out`. |
| 5 | Start `powerj/bin/powerj`, then `jcmd -l`, `jcmd <pid> Thread.print \| grep powerj-` | The `powerj-command` thread is listed; during `ls -r / \| sort name` in another terminal's view, `powerj-stage-1`. |
| 6 | `asprof -d 10 -e wall -t -f wall.html <pid>` (async-profiler installed) while a long pipeline runs | A flame graph with `powerj-command` and `powerj-stage-N` threads. |
| 7 | `jcmd <pid> GC.class_histogram \| head`, `jcmd <pid> GC.heap_info` | Output without error (the attach API works). |
| 8 | `JAVA_TOOL_OPTIONS="-XX:NativeMemoryTracking=summary" powerj/bin/powerj`, then `jcmd <pid> VM.native_memory summary` | The NMT summary is printed. |
| 9 | Edit `powerj/lib/app/powerj.cfg`, add `java-options=-Xmx256m` under `[JavaOptions]`, start the shell and run `jcmd <pid> VM.flags \| grep MaxHeapSize` | `MaxHeapSize` is 256 MB. |
| 10 | `./mvnw -Pdist verify "-Ddist.extra.args=--java-options -Xmx300m"` | The generated `powerj.cfg` contains `java-options=-Xmx300m`. |
| 11 | `./mvnw -Pbench -DskipTests package && java -jar powerj-bench/target/benchmarks.jar parse plan -p files=200 -wi 1 -i 2 -f 1` | A score table (µs/op) for `parse` and `plan`. |
| 12 | `java -jar powerj-bench/target/benchmarks.jar parse -prof gc -wi 1 -i 2 -f 1` | `gc.alloc.rate.norm` (B/op) is reported. |
| 13 | `./mvnw verify` | Succeeds without building `powerj-bench` (the profile is opt-in). |

## Notes

- The guide's advice on accuracy (`-XX:+DebugNonSafepoints`, live set after `GC.run`, fixed `-Xms`/`-Xmx`, wall vs CPU with virtual threads) is not tested here, only that the tools it uses work on the image.
