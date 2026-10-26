# 0001. Record architecture decisions

**Status:** accepted

## Context
The reasons for the structure of PowerJ lived in the heads of its authors, in commit messages, and in the "Decisions taken
with the user" sections of the plans in `docs/plans/`. The architecture review found the specification had drifted from the
code (finding C-35) because nothing said which document was the authority on what.

## Decision
- The [specification](../SPECIFICATION.md) says what the shell must do (requirements `FR-nn`).
- [ARCHITECTURE.md](../ARCHITECTURE.md) describes the code as it is, and each of its rules is a test.
- These records say why a structural choice was made and what it costs.
- The plans in `docs/plans/` stay the notes of one iteration.

## Consequences
A structural change comes with a record, or with the superseding of one. Reading them in order is a short history of the
project's constraints.
