# 0010. The shared output helpers live in a new module, `powerj-text`

**Status:** accepted (decision of [#78](https://github.com/yp44/powerj/issues/78), from the audit of 2026-10-30; the module is
created by [#107](https://github.com/yp44/powerj/issues/107), empty until the helpers move in). It amends [0003](0003-built-in-cmdlets-are-independent-of-the-engine.md)
once the module exists: `powerj-cmdlets` and `powerj-fx` may then depend on `powerj-text` as well as on `powerj-api`.

## Context
The output contract of the API (`@Display`, `@Bytes`, `@Highlight`, `Row`) is read again by every module that renders it,
and each copy has already drifted from the others:
- `@Display` is interpreted twice (`core.format.OutputFormatter`, `fx.model.ObjectReader`), `@Bytes` three times (those two
  and `cmdlets.RecordShape`), with two near-identical `RecordShape` classes;
- `humanBytes` is copied between `core` and `fx`, and a `Duration` is formatted in three places that give three texts;
- the units `kb` / `mb` / `gb` are parsed in `core.lang.Units` and in `fx.model.FilterParser`;
- two `CharSequence` wrappers guard a regular expression, one on the interrupt flag, one on a budget.

`powerj-cmdlets` and `powerj-fx` may depend on `powerj-api` (and `powerj-fs`) only (ADR 0003), so the only ways to share were
to add the helpers to the API, or to create a module under the modules that render.

## Decision
A new module `powerj-text` (Java module `io.powerj.text`) holds the helpers that the modules already duplicate:
- `RecordView`, one cached reading of a record (components, default `@Display` columns, `@Bytes`, `@Highlight`);
- the formatting of a size, a date and a duration;
- the parser of the units of a size;
- `GuardedText`, a `CharSequence` that lets a regular expression be stopped.

It depends on `powerj-api` (the annotations are there) and on the JDK only, and `powerj-core`, `powerj-cmdlets` and
`powerj-fx` may depend on it. **`powerj-api` does not grow.** Each helper moves in its own pull request, after the
module is created.

Not retained: adding the helpers to `powerj-api` (the recommendation of the audit: nothing more to ship, but the public
surface would grow, `ApiSurfaceTest` and the CHANGELOG with it, and every third-party author would see it); and leaving
the copies (smallest change, and the copies keep drifting).

## Consequences
The API stays small and its snapshot does not move for this, so this work is not tied to the first release ([#47](https://github.com/yp44/powerj/issues/47)):
a module can be added afterwards. The cost is one more jar: in the jlink image, in the build, in the coverage, and in what
`powerj-fx` needs at run time, since its zip does not ship `powerj-api` either and relies on the image. The texts that the
formatters need (the durations) come with their own bundle in the module, like the seven `Messages` classes, which stay
(CHANGELOG, "Examined, not changed").

The ArchUnit rules say what the module may depend on (`textDependsOnlyOnTheJdkAndTheApi`) and that `powerj-fx` may depend
on it (`fxDependsOnlyOnTheApiAndText`); `powerj-cmdlets` was already only forbidden to reach the engine.

The helpers moved in: the formatting of sizes, dates and durations, `io.powerj.text.Formats`
([#109](https://github.com/yp44/powerj/issues/109)), the reading of the output contract of a record,
`io.powerj.text.RecordView` ([#108](https://github.com/yp44/powerj/issues/108)), and the units of a size,
`io.powerj.text.SizeUnits` ([#110](https://github.com/yp44/powerj/issues/110)), and the text that lets a regular expression be
stopped, `io.powerj.text.GuardedText` ([#111](https://github.com/yp44/powerj/issues/111): `cmdlets` stops it on the interrupt flag,
`fx` on a budget of reads, one class for both); `core`, `cmdlets` and `fx` now `require io.powerj.text`. `RecordView` holds structure (components, `@Display`, `@Bytes`, `@Highlight`), not accessors: making an
accessor accessible is allowed to the module that asks, and a record of a third-party module is opened to `io.powerj.core`
only, so each module keeps its own accessors.

Settled when the module was created ([#107](https://github.com/yp44/powerj/issues/107)):
- **It is in the image, not in the zip of `powerj-fx`.** `powerj-dist` adds `io.powerj.text` to the modules of the runtime
  (nothing in the shell requires it, as for `io.powerj.cmdlets`). The zip of `powerj-fx` already leaves out every artifact
  of the group `io.powerj` (`excludeGroupIds`), so it will not carry `powerj-text` when `powerj-fx` starts to use it. A
  module loaded from `~/.powerj/modules` finds it in the boot layer, as it finds `powerj-api`. `ci/smoke/modules.sh`
  fails if the image does not hold it, on Linux, macOS and Windows.
- **Its package is exported to every module, and it is not API.** A qualified export to the built-in modules, as for
  `io.powerj.api.internal` ([#79](https://github.com/yp44/powerj/issues/79)), would not reach `powerj-fx`, which is loaded in
  a layer of its own (a module of a child layer cannot even load a class from a package that its parent layer exports to
  it alone). So a third-party module can read it; the module descriptor and the package documentation say that nothing
  in it is promised to stay and that it changes without deprecation. What an author needs is in `powerj-api`.
