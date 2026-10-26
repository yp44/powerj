# 0007. A composition root, without a dependency-injection framework

**Status:** accepted (Phase 4)

## Context
`Main` resolved the home folder twice, built the `Session` twice, read `System.getenv()` in eight places and could not be
tested because it called `System.exit`. The constructor of `Repl` assembled the whole graph and tested the concrete class of
the line editor, silently switching features off with another one (finding C-09).

## Decision
`ShellBootstrap` is the one place that builds the graph: arguments, home, log, configuration, language, one `Session`, then
an `InteractiveRun` or a `ScriptRun`. `Environment`, a record, holds what the shell reads of the process (variables,
properties, streams, console), and only `Environment`, `SystemTerminals` and `Main` touch the process. `Main` is one call to
`System.exit`. No framework such as Guice or Spring.

## Consequences
A shell starts in a test with a folder and a few streams, and the start-up paths (broken configuration, `--version`, exit
codes) are tested. A framework would add a dependency and start-up time (NFR-01) to save a few hundred lines that fit in
one class. One static survives: `Language.current` (262 reads), set once by the bootstrap; replacing it by an injected
`Locale` is possible and was not worth the churn so far.
