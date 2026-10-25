#!/usr/bin/env bash
# ~ is the HOME folder in any unquoted argument, redirections included.
. "$(dirname "$0")/common.sh"

export HOME="$WORK/tildehome"
mkdir -p "$HOME/docs"
touch "$HOME/docs/note.txt"
"$PJ" -c 'cd ~ ; pwd ; (ls ~/docs)*.name ; "x" > ~/out.txt' > "$WORK/tilde.txt"
cat "$WORK/tilde.txt"
grep -qx "$HOME" "$WORK/tilde.txt"
grep -qx 'note.txt' "$WORK/tilde.txt"
[ -f "$HOME/out.txt" ] || { echo "~/out.txt was not written" >&2; exit 1; }
echo "tilde: OK"
