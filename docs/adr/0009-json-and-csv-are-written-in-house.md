# 0009. JSON and CSV are written in-house, and checked against the standard suites

**Status:** accepted (Phase 5.4; the first decision dates from the plan `docs/plans/json-csv.md`)

## Context
`to-json`, `from-json`, `to-csv` and `from-csv` need what a library for objects does not give: values handed on **as soon as
they are complete** (a stream of JSON Lines, a CSV read line by line), errors with a **line and a column**, a JSON object
that becomes a `Row` in key order, a record that becomes an object by its components without calling a getter. The only
runtime dependency of the product is JLine, and a new dependency has to be agreed (see [CONTRIBUTING](../../CONTRIBUTING.md)).
The review (finding C-25) feared parsers of that size would drift from the standards without anyone noticing.

## Decision
Keep the parsers and writers in `powerj-cmdlets` (about 1 200 lines), and make their conformance a test:
- **JSON** is run through the [JSONTestSuite](https://github.com/nst/JSONTestSuite) of Nicolas Seriot (MIT; the files are
  in `powerj-cmdlets/src/test/resources/jsontestsuite/`): all the `y_` files must be accepted, all the `n_` files rejected with
  a positioned error and never a crash, and what the suite calls *implementation-defined* (`i_`) is pinned file by file, so
  a change of behavior is a decision, not an accident. Two files are not copied because they are 100 kB and 250 kB of
  nothing but brackets: the test builds them.
- **CSV** is checked section by section against RFC 4180, and what the writer writes is read back as the same records, for
  records made at random from a fixed seed. The same is done for JSON values.
- The choices that the standards leave open are written in the specification (FR-73): a repeated key keeps its last
  value, a number that does not fit a double is an error and not `Infinity`, an escaped lone surrogate is read as the
  character and written back as an escape, a byte order mark at the start is skipped, and a single value of more than
  64 Mi characters stops the reading instead of absorbing the stream.

## Consequences
No dependency, and a stream that starts to produce results at once. The price is that PowerJ owns these parsers: a bug is
ours, and the suite is what says whether a change made them better. If conformance ever regresses, or if a JSON library
is approved as a dependency for another reason, replacing the parser behind `JsonParser` is a local change that the same
suite checks.

`to-json` without `--lines` still builds the whole array before it emits it, as a single `String`; `--lines` is how to
write a very large stream.
