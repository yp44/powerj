# 0011. Every command reads the properties of an object through the engine

**Status:** accepted (decision of [#130](https://github.com/yp44/powerj/issues/130)). It replaces the last sentence of the
consequences of [0010](0010-shared-output-helpers-live-in-powerj-text.md) ("so each module keeps its own accessors").

## Context
To read a component of a record by reflection (`Method.invoke`, `trySetAccessible`), the package of the record must be
exported or opened **to the module of the code that calls**. Which module may read which record:

| Module of the caller | Records it can read |
|---|---|
| `io.powerj.core` | its own (`Job`), those of `io.powerj.cmdlets` (exported to it), those of every module loaded by `mod-load` (`ModuleLoader` opens their packages to it) |
| `io.powerj.cmdlets` | its own, those of `io.powerj.fs` and `io.powerj.api` |
| a module loaded by `mod-load` | its own, those of `io.powerj.fs` and `io.powerj.api` |

`io.powerj.cmdlets` had its own reflection (`RecordShape`, `Properties`), on the reasoning of 0010: a module makes accessible
what it can read. So `kill`, `to-json`, `to-csv` and `join` failed with an `IllegalAccessException` on the `Job` of `jobs` and on
every record of a third-party module:

    jobs | kill                      kill: not a process: Job[id=1, pid=…]
    jobs | to-json                   to-json: cannot read id: java.lang.IllegalAccessException: … cannot access class …Job
    greet --name Yves | to-csv       to-csv: cannot read name: java.lang.IllegalAccessException: … cannot access class …Greeting

The failure was hidden once a value had been displayed: `RecordComponent.getAccessor()` returns the **same** `Method` to every
caller (`RecordView` caches the components), and the table of the terminal, in the core, made it accessible with
`trySetAccessible()`. The flag is on the object, so every module then read the record, but only for the columns that had been
displayed. The isolation that 0010 assumed does not exist: the accessibility of a `Method` is shared by whoever holds it.

A third-party cmdlet, meanwhile, reads any object through `CmdletContext.property(path)`, which the engine evaluates, and it
worked for the records of every origin. The same object could be read by a third-party cmdlet and not by a built-in one.

## Decision
**The engine alone reads the value of a property by reflection; every other command asks it, through the API.**

- `CmdletContext.readProperty(Object target, String name)` and `tryReadProperty` (`powerj-api`, `default` methods): the value of
  one property, found as `$_.name` finds it (record component, getter, public field, key of a `Map` or `Row`), with the
  guard rails of the preview mode. The name is **one literal name**, not a path or an expression (`first name` and `a.b` are
  keys), and **its case matters** (for a getter, the first letter is free, as for a JavaBean). The engine implements it with
  `PropertyAccess.read`; the test context of `powerj-testing` reads the record itself.
- The **structure** of a record (names, types, `@Display`, `@Bytes`, `@Highlight`) stays in `RecordView`, which needs no access.
- The built-in cmdlets (`Properties` for `to-json`, `to-csv` and `join`; `Kill`) read through the context. `RecordShape` leaves
  `io.powerj.cmdlets`.
- The table of the terminal reads through the same `PropertyAccess.read`, given to `OutputFormatter` as a `ComponentReader`
  (the package of `PropertyAccess` depends on `core.format`, the other way would be a cycle). Nothing makes a `Method`
  accessible except the engine.
- **Enforced**: `ArchitectureTest.onlyTheEngineReadsAnObjectByReflection` forbids `Method.invoke`, `Field.get`/`set` and
  `setAccessible`/`trySetAccessible` outside `io.powerj.core` (and the test context of `powerj-testing`, which stands in for the
  engine). `ci/smoke/properties.sh` runs `jobs | to-json`, `jobs | kill` and `greet | to-json` on the packaged launcher, in fresh
  sessions: the tests of one module cannot see the boundary with another.

Not retained:
- **Exporting or opening the packages of every producer to every reader** (`exports io.powerj.core.runtime to io.powerj.cmdlets`,
  an `addOpens` to `io.powerj.cmdlets` for each module loaded): one grant for each pair of producer and reader, and a reader
  that is not known when the code is compiled (`powerj-fx`, loaded in a layer of its own) cannot be named.
- **A utility class in `powerj-text` or in `powerj-cmdlets`**: the access check is on the module of the code that calls
  `Method.invoke`, so the class that reflects is the one that needs the access, whatever the module that asks it to.
- **`property(path)` as the mechanism**: it compiles `$_.` + the path for every name (more than a read), and a name that is not a
  path (the key `first name` of a `Row`) is not an identifier.

## Consequences
- One way to read a property, in the engine; a built-in cmdlet reads what a third-party cmdlet produced and the other way round,
  without any export or `opens` to maintain.
- The API grows by two `default` methods, before the first release ([#47](https://github.com/yp44/powerj/issues/47)): the snapshot
  moves, nothing breaks. A cmdlet that has an object in hand and wants one of its properties has what `property(path)` gave it
  for a block, and a literal name.
- A read costs more in `to-csv` and `to-json` than the cached accessor did (about 35 ns more per column, measured in
  [docs/quality/performance.md](../quality/performance.md): a million rows of eight columns, 0.3 s more). Not optimised: the display
  and the writing of the file cost more. A reader resolved once per class would be the first thing to try.
- The unit tests of a cmdlet read with the test context, which does not prove the access between modules: the smoke test does,
  and the architecture rule keeps the reflection out of the cmdlets.
- `readProperty` is case-sensitive and `$_.name` is not: the first reads the name that `RecordView` lists, the second what a
  user types.
