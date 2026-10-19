# Acceptance test — End of the v1 cmdlet roadmap: the target pipeline

**Goal:** verify that the cmdlets of the roadmap (`docs/plans/cmdlet-roadmap.md`) work together on the target pipeline of §12.3 of the specification. `find` was cancelled, so `ls -r --filter` and `where` take its place.

Target of §12.3:
```text
find src --name *.java --since 7d | where { $_.size > 2kb } | group { $_.path.parent }
    | sort count --desc | first 5 | to-json rapport.json
```

With the cmdlets now available:
```text
ls src -r --filter *.java | where { $_.modified > Instant.now().minus(Duration.ofDays(7)) } | where { $_.size > 2kb } | group { $_.path.parent } -n | sort count --desc | first 5 | to-json > rapport.json
```

## Preparation

A source tree with `.java` files, for example a clone of PowerJ (`git clone https://github.com/yp44/powerj`), then `cd powerj`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `ls . -r --filter *.java \| count` | The number of Java files. |
| 2 | `ls . -r --filter *.java \| where { $_.size > 2kb } \| measure size` | Count, sum, average, min and max of their sizes (`measure`). |
| 3 | `ls . -r --filter *.java \| group { $_.path.parent } -n \| sort count --desc \| first 5` | The five folders that hold the most Java files (`group`, `sort`, `first`). |
| 4 | The pipeline above, with `.` for `src` | `rapport.json` is written; nothing is displayed. |
| 5 | `cat rapport.json` | An indented JSON array of at most 5 objects `{"key": "…", "count": …, "items": []}`. |
| 6 | `cat rapport.json \| from-json \| select key count` | The same folders and counts, as a table (`from-json`, `select`). |
| 7 | `cat rapport.json \| from-json \| select key count \| to-csv -d ";" > rapport.csv`, then `cat rapport.csv \| from-csv -d ";"` | The same table, values as strings (`to-csv`, `from-csv`). |
| 8 | `ls . -r --filter *.java \| range --from 2 --to 4 \| map { $_.name }` | The second to fourth names (`range`). |
| 9 | `mkdir out; cp rapport.json rapport.csv out/; ls out \| rm -v; rm out` | The two files are copied, then removed and listed; then the empty folder is removed (file operations). |
| 10 | `ps java* \| where { $_.pid == ProcessHandle.current().pid() } \| select pid name cpu \| to-json -c` | PowerJ itself, as one line of JSON (`ps`). |

## Known limitations

- `--since 7d` of the cancelled `find` is written with `where` and `java.time`.
- `to-json` writes no file itself: the redirection `>` does.
