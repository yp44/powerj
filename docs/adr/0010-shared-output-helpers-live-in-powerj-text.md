# 0010. The shared output helpers live in a new module, `powerj-text`

**Status:** accepted (decision of [#78](https://github.com/yp44/powerj/issues/78), from the audit of 2026-10-30; the module is
created by [#107](https://github.com/yp44/powerj/issues/107)). It amends [0003](0003-built-in-cmdlets-are-independent-of-the-engine.md)
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

The ArchUnit rules that say what the built-in modules may depend on are updated when the module is created, and a rule says
what `powerj-text` may depend on. Whether a third-party module may use `powerj-text` (it is in the image, so any module can
`require` it) is settled in [#107](https://github.com/yp44/powerj/issues/107) and written here.
