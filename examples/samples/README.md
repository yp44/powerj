# Example module `samples`

Third-party module for PowerJ (specification §4) that gathers small sample cmdlets, each showing one aspect of the
extension API. It is not shipped with PowerJ. Where [`greet`](../greet/README.md) shows a cmdlet that *produces* objects
in `begin`, this module starts with the other side of the contract: a cmdlet that *receives* the objects of the pipeline in
`process`.

```bash
./mvnw -pl examples/samples -am package     # → examples/samples/target/samples.jar
```

Load it without restarting PowerJ, then check that it is there:

```text
PJ> mod-load examples/samples/target/samples.jar
passthrough
PJ> mod-list
```

Or copy `samples.jar` into `~/.powerj/modules/` to have it at every start.

- `pom.xml`: `powerj-api` is the only dependency of the jar; `powerj-testing`, JUnit and AssertJ are for the tests.
- `module-info.java`: `provides io.powerj.api.CmdletProvider with com.example.samples.SamplesProvider`.
- `SamplesProvider`: lists the cmdlets, and gives their translations through `messages(Locale)`
  (`src/main/resources/com/example/samples/messages_en.properties` and `messages_fr.properties`).
- `PassthroughTest`: runs the cmdlet as the shell does (`CmdletDriver.run`: `begin`, `process` for each object, `end`).
  `ci/smoke/third-party-modules.sh` loads the jar with `mod-load` in the packaged shell.

## The cmdlets

### `passthrough`

The simplest cmdlet that reads the pipeline: `Passthrough` implements `process` only, and emits each object it receives.
It has no state, and nothing happens in `begin` or `end`.

| Option | Effect |
|---|---|
| `-s`, `--skip-null` | Drops the `null` objects. |
| `-o`, `--optional` | Emits `Optional.ofNullable(object)` instead of the object: `Optional.empty()` for a `null`. |

Without options, each object is emitted unchanged, `null` included. With both options, the nulls are dropped first, so no
`Optional.empty()` comes out.

```text
PJ> $l = java.util.Arrays.asList("a", null, "b")
PJ> $l | passthrough | count
3
PJ> $l | passthrough -s
a
b
PJ> $l | passthrough -o
Optional[a]
Optional.empty
Optional[b]
PJ> $l | passthrough -so
Optional[a]
Optional[b]
```

Three rules of the shell show through this cmdlet:

- **An `Optional` is unrolled between two stages** (specification FR-30b, like a list or a stream): the next cmdlet receives
  the value, and nothing for `Optional.empty()`. So `$l | passthrough -o | count` gives `2`, as `-s` does. The `Optional`
  stays whole at the end of the line (shown as `Optional[a]`) and in a variable (`$r = ($l | passthrough -o)`).
- **`passThrough` is left `false`** in `@CmdletInfo`. That attribute tells Tab completion that a cmdlet emits its input
  unchanged, so that it looks through it to find the type of `$_` in the next stage (`ls | sort name | map { $_.` offers the
  properties of `FileEntry`). With `--optional` the output is not the input, and the annotation cannot depend on the options:
  declaring it would offer `name`, `size`… after `ls | passthrough -o |`. The price: completion does not look through
  `passthrough`, even without options.
- **`-n` is not `--skip-null`**: the letter is reserved for `--dry-run` in every built-in cmdlet (specification FR-18b, checked
  by `CmdletOptionLettersTest`). A module is free to use it, but a user who knows `rm -n` would expect nothing to happen, so
  this example keeps the reserved letters for their meaning and takes `-s`.
