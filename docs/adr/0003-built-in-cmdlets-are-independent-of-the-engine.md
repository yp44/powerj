# 0003. Built-in cmdlets are independent of the engine

**Status:** accepted (from the first version; enforced since Phase 0)

## Context
A cmdlet is the unit of extension. If the built-in ones could reach into the engine, third-party authors would find the
contract incomplete, and the engine could not change without breaking the built-in ones.

## Decision
`powerj-cmdlets` depends on `powerj-api` and `powerj-fs` only, never on `powerj-core`, and is published as a plain
`CmdletProvider` through `ServiceLoader`, exactly as a third-party module is. `powerj-fx` follows the same rule. What a cmdlet
needs from the engine goes through `CmdletContext` (compile a block, read a property, keep the state of a run, ask for a
service) and what the engine needs from a cmdlet through `CmdletProvider` (for instance `initialize(Path)`, which lets the
built-in cmdlets create their default files, so that the shell does not have to know the class that does it).

## Consequences
The API had to grow the capabilities the built-in cmdlets used (`tryInvoke`, `state`, `property`, the `Previewable` and
`ColumnProvider` interfaces), which is what makes it complete for others. The cost is an API that has to be kept compatible
(`ApiSurfaceTest`; once a version is published, deprecation before removal; until then it may still be broken). The
ArchUnit rule `cmdletsAreIndependentOfTheEngine` keeps it so.
