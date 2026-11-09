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

A third-party cmdlet, meanwhile, read any object through `CmdletContext.property(path)`, which compiled `$_.` + the path and
let the engine evaluate it, and it worked for the records of every origin. The same object could be read by a third-party
cmdlet and not by a built-in one. And there were two ways to read a property in the API: that one, tolerant of the case of
the name and made of an expression, and the private reflection of the built-in cmdlets.

## Decision
**The engine alone reads the value of a property by reflection; every other command asks it, through the API.**

- `CmdletContext.readProperty(Object target, String name)` (`powerj-api`, abstract) and `tryReadProperty` (`default`, the failure
  as a value, as `ScriptBlock.tryInvoke`): the value of one property, found as `$_.name` finds it (record component, getter,
  public field, key of a `Map` or `Row`), with the guard rails of the preview mode. The name is **one literal name**, not a
  path or an expression (`first name` and `a.b` are keys). The engine implements it with `PropertyAccess.property`, the lookup
  of `$_.name`; the test context of `powerj-testing` reads the record itself.
- **`CmdletContext.property(path)` is removed.** It was `compile("$_." + path)`: an expression compiled for a name, case-insensitive,
  that the cmdlets called after their own check of the path. The names that a user types as an argument (`sort size`,
  `--property owner.name`) are read by `PropertyPath`, in the cmdlets, which calls `readProperty` for each name of the path.
- **The case of a name matters, everywhere**: `$_.size`, `where size > 1mb`, `sort size`, `--property path`, `grid --columns`. A
  name that differs from a property only by its case is answered with that property (`has no property 'Size' (did you mean
  'size'?)`). A getter is found by the name of its property, the one that the completion lists, as for a JavaBean (`getName()` is
  `name` and not `Name`, `getURL()` is `URL`). This breaks the scripts that wrote another case, which is allowed before the first release.
- The **structure** of a record (names, types, `@Display`, `@Bytes`, `@Highlight`) stays in `RecordView`, which needs no access.
- The built-in cmdlets (`Properties` for `to-json`, `to-csv` and `join`; `Kill`) read through the context. `RecordShape` leaves
  `io.powerj.cmdlets`.
- The table of the terminal reads through the same `PropertyAccess.property`, given to `OutputFormatter` as a `ComponentReader`
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
- **`property(path)` as the one mechanism**: it compiles `$_.` + the path for every name (more than a read), a name that is not a
  path (the key `first name` of a `Row`) is not an identifier, and an expression cannot be case-sensitive without changing the
  language: the case rule is the language's, so it is applied to the language.
- **Two methods in the API, an exact one and a tolerant one**: the cmdlets would have to say which they want, and the user would
  meet two rules for the same name (`sort Size` working where `$_.Size` does not, or the other way round).

## Consequences
- One way to read a property, in the engine; a built-in cmdlet reads what a third-party cmdlet produced and the other way round,
  without any export or `opens` to maintain.
- The API loses `property(path)` and gains `readProperty` and `tryReadProperty`, before the first release
  ([#47](https://github.com/yp44/powerj/issues/47)): a break, announced in the CHANGELOG. A module that read a path with
  `property` writes `context.compile("$_." + path)` (and keeps the language's rules, the case included) or reads one name after the
  other with `readProperty`. Every implementation of `CmdletContext` has to give `readProperty`.
- A read costs more in `to-csv` and `to-json` than the cached accessor did (about 20 ns more per column, measured in
  [docs/quality/performance.md](../quality/performance.md): a million rows of eight columns, 0.1 to 0.2 s more). Not optimised: the display
  and the writing of the file cost more. A reader resolved once per class would be the first thing to try. The expressions
  (`$_.name`) are faster: the lookup is by exact name in a hash table.
- The unit tests of a cmdlet read with the test context, which does not prove the access between modules: the smoke test does,
  and the architecture rule keeps the reflection out of the cmdlets.
- The option names of a cmdlet (`--Property`) and its enumerated values were first left case-insensitive, as not being the name of
  an attribute of an object (FR-18). [0012](0012-the-shell-is-case-sensitive.md) decided otherwise: the case matters
  for every name that a user writes.
