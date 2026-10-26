# Acceptance test — Mini-iteration 18: `count` and `measure` cmdlets

**Goal:** verify that `count` counts the pipeline objects, and that `measure` gives the count, sum, average, minimum and maximum of the objects or of a property, as one `Row` that can be displayed or read (`(… | measure size).sum`). Sizes must be shown in readable form.

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## Preparation

In a test folder (e.g. `C:\tmp\m`), create `big.txt` (about 2048 characters), `notes.txt` (7 characters) and a folder `docs`. Then `cd C:\tmp\m`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `ls \| count` | `3`. |
| 2 | `ls -r \| where { !$_.dir } \| count` | The number of files. |
| 3 | `count` (alone) | `0`. |
| 4 | `ls --files \| measure size` | One line `count 2`, `sum 2.0 KB`, `average 1.0 KB`, `min 7 B`, `max 2.0 KB`. |
| 5 | `(ls --files \| measure size).sum` | `2055` (bytes). |
| 6 | `ls \| measure modified` | Empty `sum` and `average`; `min` and `max` are the oldest and newest dates. |
| 7 | `ls \| measure name` | `min` and `max` are the first and last names in alphabetical order. |
| 8 | `List.of(1, 2, 3) \| measure` | `count 3`, `sum 6`, `average 2.0`, `min 1`, `max 3`. |
| 9 | `List.of(1, 2.5) \| measure` | `sum 3.5`. |
| 10 | `env \| measure { $_.value.length() }` | Statistics of the lengths of the variable values. |
| 11 | `ls \| measure { $_.name.substring(5) }` | One error per name shorter than 5 characters (`object skipped`); the others are measured. |
| 12 | `ls \| measure 3` | Error `measure: invalid property name: 3`. |
| 13 | `List.of("b", 1) \| measure` | `count 2`, empty `sum`, one error `measure: cannot compare … values of different types are sorted by type …`. |
| 14 | `help count`, then `help measure` | Their help, with the examples. |
| 15 | `POWERJ_LANG=fr`, then `ls \| measure 3` | `measure : nom de propriété invalide : 3`. |

## Known limitations

- A computed key (`measure { $_.size / 1kb }`) is not shown as a size, even when it holds a number of bytes; use `measure size`.
- `count` and `measure` answer when the input ends: on an endless input, Ctrl+C stops them.
