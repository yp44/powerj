#!/usr/bin/env bash
# The session of a user, through the standard input of the launcher (the session examples of FR-55): the exit code of
# the last native command is the exit code of the shell.
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

# A start-up directory that does not exist: an error naming it, exit code 1, and no shell started (FR-04d).
set +e
"$PJ" --cd "$WORK/absent" -c 'pwd' < /dev/null > "$WORK/cd-out.txt" 2> "$WORK/cd-err.txt"
code=$?
set -e
[ "$code" -eq 1 ] || { echo "expected exit code 1 for a missing --cd directory, got $code" >&2; exit 1; }
expect "$WORK/cd-err.txt" "--cd" "absent"
[ ! -s "$WORK/cd-out.txt" ] || { echo "the command ran although --cd failed" >&2; exit 1; }
echo "launcher: OK"
