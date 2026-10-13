# Acceptance test — Mini-iteration 12: expression stage using `$_`

**Goal:** check that after a `|`, an expression using `$_` is evaluated for each object, like `map { … }` (FR-36e).

Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## Scenario (in the PowerJ repository folder)

| # | Action | Expected result |
|---|---|---|
| 1 | `ls -r \| where {$_.size > 1mb} \| collect \| $_.size()` | One number: the count of files over 1 MB. |
| 2 | `ls \| $_.name` | The names, one per line (same as `ls \| map { $_.name }`). |
| 3 | `ls --files \| "$($_.name) : $($_.size)"` | One line per file: `name : size`. |
| 4 | `ls \| $_.name.length() \| collect \| $_.stream().mapToInt(n -> n).sum()` | One number: the sum of the name lengths. |
| 5 | `ls \| $_.nope()` | One red line per object: `map: FileEntry has no method nope() (object skipped: …)`; the prompt comes back. |
| 6 | `ls \| $x` | `syntax: a value can only be the first stage of a pipeline: use $_ for each object (ls \| $_.name) or map { … }` |
| 7 | `ls \| { $_.name }` | `syntax: a block alone is not a pipeline stage: write map { … } …` (unchanged). |

## Known limitations

- An error in such a stage is reported by `map` (`map: …`), the cmdlet that runs it.
- Only `$_` makes an expression a stage: `ls | $x.foo($_)` works, `ls | $x` does not.
