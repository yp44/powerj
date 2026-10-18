# Plan — cmdlet `group` (mini-iteration 19, FR-71)

## Context
Phase 5 of the cmdlet roadmap (`docs/plans/cmdlet-roadmap.md`). The backlog (§12.3) lists `group`, "Grouping", with output `Group<T>`.

Today, grouping needs `collect` and `Collectors.groupingBy`, and the result is a `Map` that the next cmdlets cannot sort or filter easily:

```text
(ls -r | collect).stream().collect(Collectors.groupingBy(f -> f.ext, Collectors.counting()))
```

`group` gives one object per group, which the rest of the pipeline can use:

```text
ls -r | group ext | sort count -d | first 5
```

Decisions taken with the user:
1. Each group is a **fixed record `Group(key, count, items)`**: `$_.key`, `$_.count`, `$_.items`. Tab completion knows these properties, as it does for `ls`. The column is always named `key`.
2. **Several keys** are allowed (`group ext dir`); the key of a group is then the list of the values (`[java, false]`).
3. One option, **`-n`/`--no-items`**, keeps only the count, without the objects (constant memory per group on large inputs). No `-i` option, and no sorted output: groups come out in the order their key first appears, and `| sort key` sorts them.

Branch: `claude/cmdlet-group`, from `main` @ 00f81aa.

## Syntax
```text
group [<key>...] [-n|--no-items]
```

| Option | Short | Type | Description |
|---|---|---|---|
| `keys` (position 0, multiple) | — | property, property path or block | Grouping keys. None: the objects themselves (`cat a.log \| group`, like `sort \| uniq -c`). |
| `--no-items` | `-n` | boolean | The groups hold no objects (`items` is empty), only their count. |

Keys are read with `Keys`, as for `sort` (FR-67): `group ext`, `group path.parent`, `group { $_.size > 1mb }`, `group FileEntry::dir`. An invalid property name, or a key of another kind, is a blocking error.

## Output
```java
@Display(columns = {"key", "count"})
public record Group(Object key, long count, List<Object> items) { }
```
- **`key`**: the value of the key. With several keys, it is an unmodifiable list of the values, in the order of the keys. `null` is allowed, so an object without a value forms the group `null`.
- **`count`**: the number of objects in the group.
- **`items`**: the objects, in their input order. It is an unmodifiable list, empty with `-n`.
- **Display**: a table `key count`, with `items` hidden. `(… | group ext)[0].items` gives the objects.
- **Unrolling:** a `Group` is a record, so the pipeline never unrolls it. `items` is a `List`, and `| map { $_.items }` unrolls it (FR-30b).

## Behaviour
- **Key equality:**
  - groups are kept in a `LinkedHashMap`, so keys use `equals`;
  - integral numbers are normalized to `Long` before comparison, so `1` (`Integer`) and `1L` fall in the same group. Other values are compared as they are; strings are case-sensitive.
- **Order:** groups are emitted, when the input ends, in the order their key first appeared. Objects in a group keep their input order.
- **Memory:** all objects are kept until the end, except with `-n`, where only one counter per group is kept.
- **Key evaluation error:** non-blocking (`pipeline.object.skipped`); the object is in no group.
- **Empty input:** nothing is emitted.
- **Preview:** `preview = true`, because `group` changes nothing.
- **Cancellation:** standard (`emit` throws on Ctrl+C).

## Implementation
- `powerj-cmdlets/.../Group.java`: the record, with `Objects.requireNonNull(items)` in the compact constructor. It is public and exported with the package, like `FileEntry`.
- `powerj-cmdlets/.../GroupCmdlet.java`:
  - named like `MapCmdlet`, because the record takes the name `Group`;
  - `@CmdletInfo(name = "group", category = "Filters", preview = true)`, `Cmdlet<GroupCmdlet.Params, Object, Group>`;
  - `Params(@Option(position = 0) List<Object> keys, @Option(shortName = 'n') boolean noItems)`;
  - per-execution state, as in `Sort`: the key blocks from `Keys.blocks(...)`, and a `LinkedHashMap<Object, Bucket>` where `Bucket(long count, List<Object> items)`;
  - `end` emits one `Group` per bucket.
- Messages (`messages_en`/`_fr`): `group.summary`, `group.option.keys`, `group.option.no-items`, `group.key.invalid`.
- `BuiltinCmdlets`: register `new GroupCmdlet()`.
- No change in `powerj-core`. A `Group` is an ordinary record: table display, `@Display`, property access and completion already handle it.

## Tests
- `GroupTest`:
  - group by one property, with keys in first-seen order and items in input order;
  - no key: the objects themselves;
  - several keys: the key is a list;
  - `Integer` and `Long` keys in the same group;
  - `null` key;
  - `-n`: counts kept, `items` empty;
  - key that throws: error, object in no group;
  - invalid key: exception;
  - empty input: nothing emitted.
- `BuiltinCmdletsTest`:
  - `ls | group dir` shows a `key count` table;
  - `ls --files | group ext | sort count -d | first | map { $_.key }`;
  - `(ls | group dir)[0].items` gives the objects.
- `CompletionsTest`: `ls | group ext | where { $_.` proposes `key`, `count` and `items`.

## Verification
- `./mvnw verify` (locally `-Djava.release=25`; CI uses JDK 27).
- Manual:
  - `ls -r | group ext | sort count -d | first 5`
  - `ls -r | group ext dir`
  - `ls -r | group ext -n`
  - `cat app.log | group`
  - `ls -r | group { $_.size > 1kb }`
  - `(ls -r | group ext)[0].items`
  - `ls -r | group ext | select key count size={ $_.items.stream().mapToLong(f -> f.size).sum() }`
  - `help group`
