# 0005. Guard-rails against accidents, not a sandbox

**Status:** accepted (Phase 1.1 and 1.7)

## Context
The README promised that the shell "never crashes", and the language gives the user the whole Java API. Typing
`System.exit(0)` could end the shell; `rm -r` of the wrong folder could destroy a system. The first guard for `System.exit`
was written for the direct call only, and `[0] | map System::exit` went around it (finding C-12). The first protected-path
check compared paths as text, and an alias of a protected folder went around it (C-21).

## Decision
- Put each guard at the **choke point** the thing passes through, not at one way of writing it: `CallPolicy` is asked by
  `JavaInvoker` for every Java call (direct, method reference, lambda in a stream, `Method.invoke`); `RealPaths` resolves
  links before comparing.
- Say plainly what they are: protection against a *mistake*. They are not a sandbox and not an access control. The
  documentation of `CallPolicy` and `ProtectedPaths`, the README and [SECURITY.md](../../SECURITY.md) say so, with what they
  do not stop.
- Test the bypasses that were found, in a child JVM where the real `System.exit` is at stake.

## Consequences
Users and reviewers know what to expect, and a report that only shows that a guard-rail can be bypassed on purpose is out of
scope. A real sandbox would be a different product (a security manager no longer exists in the JDK; it would mean running
commands in another process).
