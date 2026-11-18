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

# A file of our own: /etc/passwd has root on its first line on Linux and on its twelfth on macOS.
printf 'daemon:x:1\nroot:x:0\n' > "$WORK/passwd.txt"
(cd "$WORK" && "$PJ" -c 'grep -i "^ROOT:" passwd.txt -p 2 | map { m -> "grep: " + m.line }' < /dev/null > grep.txt)
cat "$WORK/grep.txt"
[ "$(cat "$WORK/grep.txt")" = "grep: 2" ] || { echo "grep through the launcher failed" >&2; exit 1; }
# --cd chooses the directory the session starts in (FR-04d), whatever the directory powerj was started in.
(cd / && "$PJ" --cd "$WORK" -c pwd < /dev/null > "$WORK/cd.txt")
expect "$WORK/cd.txt" "$(basename "$WORK")\$"
# What follows a script is its $args (FR-04d); a file written by Notepad (BOM) runs its first command; an unknown option is named.
printf '\xef\xbb\xbf$args[1]\n' > "$WORK/args.pj"
"$PJ" "$WORK/args.pj" a b < /dev/null > "$WORK/args.txt"
[ "$(cat "$WORK/args.txt")" = "b" ] || { echo "\$args[1] should be b" >&2; exit 1; }
# The words after the script are its own, even --help: not the usage of the shell.
"$PJ" "$WORK/args.pj" --help --version < /dev/null > "$WORK/args-flags.txt"
[ "$(cat "$WORK/args-flags.txt")" = "--version" ] || { echo "script arguments were taken for options" >&2; exit 1; }
set +e
"$PJ" --foo < /dev/null > /dev/null 2> "$WORK/unknown.txt"
code=$?
set -e
[ "$code" -eq 2 ] || { echo "an unknown option should give 2, got $code" >&2; exit 1; }
expect "$WORK/unknown.txt" "unknown option: --foo" "usage: powerj"
# --version and --help answer without starting the shell (and without touching the home folder).
"$PJ" --version > "$WORK/version.txt"
expect "$WORK/version.txt" "^PowerJ " "(Java "
"$PJ" --help > "$WORK/help.txt"
expect "$WORK/help.txt" "usage: powerj" "--cd" "--version" "--help"
echo "non-interactive: OK"
