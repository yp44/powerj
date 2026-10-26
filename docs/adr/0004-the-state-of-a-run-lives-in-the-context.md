# 0004. The state of one run of a cmdlet lives in the context

**Status:** accepted (Phase 1.3)

## Context
A cmdlet is one object, shared by every command that uses it, even by two stages of the same pipeline. Yet most cmdlets need
state for one run: the list to sort, the count so far. `begin`, `process` and `end` receive only the parameters and the
context, so 23 cmdlets kept their own table, in three incompatible ways (a `WeakHashMap` keyed by context, an
`IdentityHashMap` keyed by parameters, a `ConcurrentHashMap` keyed by a record compared by value). The runner did not call
`end` after an error or Ctrl+C, so the tables leaked (finding C-01).

## Decision
`CmdletContext.state(Class<S> type, Supplier<? extends S> initial)` returns the object of this run, the same one whichever
method asks. If it is `AutoCloseable`, the runner closes it however the run ends, in the reverse order of creation. The 23 tables
were removed. `end` is **not** called after a failure: it is where `sort`, `count` or `group` emit their result, and a result
emitted by the command that just failed would be a partial one. Releasing is what always runs; emitting is what runs on success.

## Alternatives not taken
- A factory `Cmdlet.newExecution(Params)` returning an object with `begin/process/end/close`: cleaner, but a much larger
  break of the contract. Still possible later; `state` does not prevent it.
- A typed key instead of a class (`StateKey<List<Object>>`), which would save the one-field wrapper classes such as
  `Collect.Received`: an API change judged not worth making for that alone.

## Consequences
No lock on the hot path, no leak, no cross-talk between two stages that have equal parameters. A context written for a test
must implement `state` if the cmdlet under test uses it (`TestContext` does).
