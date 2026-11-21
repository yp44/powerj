# Example module `samples`

Third-party module for PowerJ (specification §4) that gathers small sample cmdlets, each showing one aspect of the
extension API. It is not shipped with PowerJ. Where [`greet`](../greet/README.md) shows a cmdlet that *produces* objects
in `begin`, this module shows the other parts of the contract: a cmdlet that *receives* the objects of the pipeline in
`process` (`passthrough`), and every kind of option a cmdlet can declare, with a block that the cmdlet evaluates itself
(`echo-options`).

```bash
./mvnw -pl examples/samples -am package     # → examples/samples/target/samples.jar
```

Load it without restarting PowerJ, then check that it is there:

```text
PJ> mod-load examples/samples/target/samples.jar
passthrough
echo-options
PJ> mod-list
```

Or copy `samples.jar` into `~/.powerj/modules/` to have it at every start.

- `pom.xml`: `powerj-api` is the only dependency of the jar; `powerj-testing`, JUnit and AssertJ are for the tests.
- `module-info.java`: `provides io.powerj.api.CmdletProvider with com.example.samples.SamplesProvider`.
- `SamplesProvider`: lists the cmdlets, and gives their translations through `messages(Locale)`
  (`src/main/resources/com/example/samples/messages_en.properties` and `messages_fr.properties`). The key of an option is
  `<cmdlet>.option.<long name>`: `passthrough.option.skip-null`, not `skipNull`. `MessagesTest` computes the keys from the
  annotations as the shell does, so a key the shell would not find fails the build.
- `PassthroughTest`, `EchoOptionsTest`: run the cmdlets as the shell does (`CmdletDriver.run`: `begin`, `process` for each
  object, `end`). `ci/smoke/third-party-modules.sh` loads the jar with `mod-load` in the packaged shell.

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

### `echo-options`

Emits **one object holding the value received for each of its options**: what the shell gives a cmdlet for what the user
typed. It reads no pipeline and changes nothing. Run alone, it shows the default of each type; no option is mandatory for
that reason (`greet` shows `mandatory = true`).

```text
PJ> echo-options -t hello -c 3 --size 10kb --timeout 1.5s --level high --tag a --tag b --block { 1 + 2 } one two
words      : [one, two]
text       : hello
count      : 3
number     :
size       : 10240
…
timeout    : 1.500 s
level      : HIGH
tags       : [a, b]
block      : { 1 + 2 }
blockValue : 3
```

| Option | Type | What it shows |
|---|---|---|
| `<words>` (position 0) | `List<String>` | The words that are not options. A positional list must be the last positional |
| `-t`, `--text` | `String` | Plain text |
| `-c`, `--count` | `int` | A primitive: `0` when not given |
| `--number` | `Integer` | The same type boxed: `null` when not given, which tells "absent" from `0` |
| `--size` | `long` | A size with a unit: `10kb` gives `10240` |
| `--ratio`, `--factor` | `double`, `float` | Decimal numbers |
| `--small`, `--tiny` | `short`, `byte` | Out of range is an error that names the option and the range |
| `--letter` | `char` | Exactly one character; `'\0'` when not given |
| `-v`, `--verbose` | `boolean` | A switch, without a value (`--verbose=false` also works) |
| `--timeout` | `Duration` | A duration with a unit: `250ms`, `1.5s`, `5m`, `2h`, `7d` |
| `--path` | `Path` | A path |
| `--level` | `Level` (enum) | Written in lower case (`--level high`); a wrong value lists the allowed ones |
| `--tag` | `List<String>` | Repeatable (`--tag a --tag b`); `longName = "tag"` names it, the component is `tags` |
| `--block` | `Object` | A block `{ … }` that **the cmdlet** evaluates, or any value |
| `--pattern` | `Pattern` | A type the shell does not convert: it takes an object, `--pattern (java.util.regex.Pattern.compile("a.*"))`; a word is refused (`unsupported option type: Pattern`) |

Only `-t`, `-c` and `-v` have a short form. `-v` keeps the meaning that FR-18b reserves for it (`--verbose`), and no other
reserved letter (`r n f v p i a l`) is used for something else.

The output record `OptionValues` has 18 columns. A single object is shown as a list, as above; to compare several, pick
the columns: `echo-options --tag a --tag b one two | select tags words`. (`to-json` is not a good view of a block: it
writes the internals of the shell's block object.)

**A block is evaluated by the cmdlet, not by the shell.** The shell turns `{ … }` into a `ScriptBlock` and passes it on;
the cmdlet decides when to evaluate it and with which `$_`. Every built-in cmdlet that takes a block does so: `where`
evaluates it once per object received. `echo-options` has no current object, so it evaluates the block once with `$_`
equal to `null` (`block.tryInvoke(null)`), as `join --right` does. Parentheses are the other way round: the shell
evaluates `( … )` before the call, and the cmdlet receives the value.

```text
PJ> echo-options --block { 1 + 2 } | select block blockValue
block      blockValue
-----      ----------
{ 1 + 2 }           3
PJ> echo-options --block (1 + 2) | select block blockValue
block  blockValue
-----  ----------
    3           3
PJ> echo-options --block { 1 / 0 } | count
echo-options: --block: calculation failed: division by zero
1
```

A block that fails is a non-blocking error (`ctx.error`, so `--on-error` applies) and leaves `blockValue` empty; the
object is still emitted. `tryInvoke` throws a cancellation (Ctrl+C) instead of returning it, so Ctrl+C still stops the
command.
