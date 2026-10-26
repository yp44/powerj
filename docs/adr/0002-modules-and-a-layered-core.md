# 0002. JPMS modules, and a core made of layered packages

**Status:** accepted (Phases 0 and 3 of the review)

## Context
Every Maven module is a JPMS module. `powerj-core` had two packages, one of which (`exec`) held 53 classes of six
unrelated kinds, all exported, with cycles between and inside packages (findings C-04, C-05). Nothing could be moved
without breaking a consumer that did not exist yet.

## Decision
- Split the core by *package* first: `lang`, `error`, `i18n` (base); `format`, `java`, `nativeexec` (services); `command`;
  `runtime`; `perf` and `assist`, which plug into `runtime` through interfaces. A package uses the ones below it only.
- Export the packages of the core to the shell only (`exports … to io.powerj.shell`); the shell exports nothing; the
  cmdlets module exports its package to the core only.
- State the rules as ArchUnit tests in `powerj-architecture`, with no cycle allowed, and freeze only what could not be fixed
  at once.
- Do **not** promote a package to a Maven module until a second consumer needs it. `lang` is the candidate (it depends only
  on the JDK and the API) and was left in the core on purpose (review §14, step 3.5).

## Consequences
The rules cannot erode quietly, and a package can be moved or rewritten knowing who can see it. The price is a long list
of qualified exports and a build module that holds only tests. `Language.current`, a static, is the one frozen violation.
