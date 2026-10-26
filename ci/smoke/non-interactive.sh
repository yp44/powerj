#!/usr/bin/env bash
# Non-interactive mode: the exit code, standard input as String lines, a native pipeline.
. "$(dirname "$0")/common.sh"

set +e
"$PJ" -c 'sh -c "exit 3"'
code=$?
set -e
[ "$code" -eq 3 ] || { echo "expected exit code 3, got $code" >&2; exit 1; }

printf 'x.txt\ny.log\nz.txt\n' | "$PJ" -c 'where { $_.endsWith(".txt") }' > "$WORK/stdin.txt"
cat "$WORK/stdin.txt"
[ "$(cat "$WORK/stdin.txt")" = "$(printf 'x.txt\nz.txt')" ] || { echo "standard input not filtered" >&2; exit 1; }

"$PJ" -c 'grep -i "^ROOT:" /etc/passwd -p 2 | map { m -> "grep: " + m.line }' < /dev/null > "$WORK/grep.txt"
cat "$WORK/grep.txt"
[ "$(cat "$WORK/grep.txt")" = "grep: 1" ] || { echo "grep through the launcher failed" >&2; exit 1; }
# --version and --help answer without starting the shell (and without touching the home folder).
"$PJ" --version > "$WORK/version.txt"
expect "$WORK/version.txt" "^PowerJ " "(Java "
"$PJ" --help > "$WORK/help.txt"
expect "$WORK/help.txt" "usage: powerj" "--version" "--help"
echo "non-interactive: OK"
