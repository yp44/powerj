# Performance

What a pipeline pays for each object that flows through it, and the latencies that the specification states, measured
with [JMH](https://github.com/openjdk/jmh) in the module `powerj-bench` (review C-26, NFR-02, NFR-07). The module is not part
of the default build.

## Running them

```bash
./mvnw -Pbench -DskipTests package                 # builds the benchmarks (needs the network once, for JMH)
java -cp "powerj-bench/target/classes:$(cat powerj-bench/target/classpath.txt)" org.openjdk.jmh.Main
java -cp "powerj-bench/target/classes:$(cat powerj-bench/target/classpath.txt)" org.openjdk.jmh.Main 'Property'   # one of them
```

JMH needs a quiet machine: a result from a loaded laptop or a shared CI runner says little. The tests in
`ReflectionCostTest` and `RecordShapeCostTest` are the guard that runs everywhere: they give a million reads a budget
two orders of magnitude above the cached cost and one below the uncached cost.

## The cost of reflection per object (C-26)

Measured on the machine below, `Mode.AverageTime`, one fork, 3 warm-up and 5 measured iterations of one second.

| Per object | Before | After | |
|---|---|---|---|
| `$_.size` on a record (`where`, `map`, `sort` blocks): `PropertyAccess.property` | 7 017 ns | 38 ns | 185 × |
| the 8 columns of a record, as `to-csv` reads them | 61 352 ns | 410 ns | 150 × |
| a record as a map, as `to-json` reads it | 17 634 ns | 185 ns | 95 × |
| a static Java call (`Math.max(3, 7)`) | 3 893 ns | 782 ns | 5 × |
| an instance Java call (`"abc".toUpperCase()`) | 321 ns | 348 ns | unchanged |

**Why.** `Class.getRecordComponents()` builds new `RecordComponent` objects at every call, about 7 µs. The code asked for
them once per property read, and `to-csv` once per column of each object: `ls -r | where { $_.size > 1mb }` spent seven
seconds per million files in that call alone, `to-csv` of a million rows of eight columns a minute. A `RecordShape` per class
(a `ClassValue`, so that it is freed with the class) now holds the accessors, made accessible once. The static methods of a
class are found once per class too: `getMethods()` copies every public method of the class at each call.

## The latencies of the specification

| | Requirement | Measured |
|---|---|---|
| NFR-02 | completion under 50 ms | 20 to 350 µs per Tab, warm: `l` 354 µs, `ls -` 31 µs, `ls \| where { $_.` 28 µs, `ls \| map { f -> f.name.` 223 µs, `Math.ma` 26 µs, `java.util.Li` 19 µs, `cat src/ma` 27 µs |
| NFR-07 | first Java call under 50 ms | 28 ms on average over ten fresh JVMs (24 to 33 ms), class loading included |
| NFR-07 | later calls under 1 ms | 0.8 µs for a static method, 0.35 µs for an instance method |

NFR-02 is measured **warm**: the shell warms the completion up at start (`Completions.warmUp`), and JMH measures steady
state. The first Tab of a session, before the Java class index is ready, is not a number here.

## The grid at 100 000 rows (C-29, NFR-04)

`grid` reads 100 000 objects by default (`--max-rows`). The model (`powerj-fx`, no window) was measured on a table of 100 000
records of eight columns (text, a size, a date, booleans, numbers), through `ObjectReader` and `GridTable` in batches of
20 000 as the window feeds them, with a property read as cheap as the shell's:

| | Before | After |
|---|---|---|
| reading 100 000 objects into rows (pipeline thread) | 1 020 to 1 390 ms | 83 to 180 ms |
| memory retained per row (the rows, the values, the object kept) | 827 bytes | 387 bytes |
| memory that a row costs by itself (a fixture of shared values) | about 520 bytes | under 100 bytes |
| sort on a numeric column (toolkit thread) | 140 to 390 ms | 140 to 390 ms |
| filter `=java` on a column / `/regex/` / text in every column (toolkit thread) | 22 to 110 ms | unchanged |
| tick all 100 000 rows (toolkit thread) | 590 to 900 ms, 87 bytes each | unchanged |

**Why.** A row held a `LinkedHashMap` of its own (about 470 bytes for eight columns, copied again each time the row was
given its rank), and `ObjectReader` asked the class for its record components, its annotations and its getters for every
object. A row is now an array of its values and a `RowShape`, the names of the columns, that the rows of one type share; the
columns of a type are worked out once. A row also keeps the object it came from only with `--pass-thru`, the only command
that gives it back.

**What was measured and left alone.** The sort, the filters and "tick all" take between a tenth of a second and a second on
the toolkit thread for 100 000 rows: a pause, not a freeze, and each one follows a click or the end of a 200 ms typing
delay. Moving them to another thread would need a filtered list that is not bound to the toolkit thread. `GridScaleTest` keeps
the budgets: under 250 bytes a row, and each step within seconds.

## The machine

Linux container, 4 virtual CPUs of an Intel Xeon at 2.8 GHz (shared), OpenJDK 25, G1. Absolute numbers will differ on
yours; the ratios are what to read.
