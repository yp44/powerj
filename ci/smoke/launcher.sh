#!/usr/bin/env bash
# The session of a user, through the standard input of the launcher: the exit code of the last native command is the
# exit code of the shell.
. "$(dirname "$0")/common.sh"

cat > "$WORK/smoke.txt" <<'SCRIPT'
cd /usr
pwd
$l = sh -c "printf 'un\ndeux\n'"
"line: $l[1]"
sh -c "exit 1" || "fallback"
which ls
(ls / --dirs)*.name
$u = (ls / | where name == usr).name
"filter: $u"
$t = printf "b\na\nc\n" | sort | where { $_ != "c" }
"sort: $t"
"java: $(Math.max(3, 7))"
$n = List.of("a", "bb", "ccc").stream().filter(s -> s.length() > 1).count()
"lambda: $n"
$r = List.of("b", "a") | map String::toUpperCase | where { s -> s != "B" }
"reference: $r"
$c = (ls / | where name == usr | collect).size()
"collect: $c"
sh -c "exit 3"
exit $exit
SCRIPT

set +e
"$PJ" < "$WORK/smoke.txt" > "$WORK/out.txt" 2>&1
code=$?
set -e
cat "$WORK/out.txt"
[ "$code" -eq 3 ] || { echo "expected exit code 3, got $code" >&2; exit 1; }
expect "$WORK/out.txt" '/usr$' 'line: deux' 'fallback' 'ls → cmdlet (io.powerj.cmdlets)' 'usr$' 'filter: usr' \
    'sort: \[a, b\]' 'java: 7' 'lambda: 2' 'reference: A' 'collect: 1'
echo "launcher: OK"
