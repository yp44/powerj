#!/usr/bin/env bash
# The wildcards of an unquoted word of a native command are replaced by the names they match (Linux and macOS), a
# quoted word is not, and case counts unless POWERJ_GLOB_IGNORE_CASE says otherwise (FR-40).
. "$(dirname "$0")/common.sh"

export POWERJ_LANG=en
mkdir -p "$WORK/glob/sub"
cd "$WORK/glob"
touch a.txt b.txt c.log Notes.TXT sub/d.txt

"$PJ" -c '^echo *.txt ; ^echo "*.txt" ; ^echo *.zip ; ^echo sub/*.txt ; ^echo *.TXT' > "$WORK/wildcards.txt"
cat "$WORK/wildcards.txt"
expect "$WORK/wildcards.txt" '^a.txt b.txt$' '^\*.txt$' '^\*.zip$' '^sub/d.txt$' '^Notes.TXT$'

POWERJ_GLOB_IGNORE_CASE=true "$PJ" -c '^echo *.txt' > "$WORK/ignore-case.txt"
cat "$WORK/ignore-case.txt"
expect "$WORK/ignore-case.txt" '^a.txt b.txt Notes.TXT$'

# The variable is read at each use: env --set applies from the next command.
"$PJ" -c 'env --set POWERJ_GLOB_IGNORE_CASE=1 ; ^echo notes*' > "$WORK/env-set.txt"
cat "$WORK/env-set.txt"
expect "$WORK/env-set.txt" '^Notes.TXT$'

# A value that is not a rule is an error where a wildcard is expanded, and nowhere else.
POWERJ_GLOB_IGNORE_CASE=maybe "$PJ" -c '^echo plain ; ^echo *.txt' > "$WORK/wrong.txt" 2>&1 || true
cat "$WORK/wrong.txt"
expect "$WORK/wrong.txt" '^plain$' "POWERJ_GLOB_IGNORE_CASE.*'maybe'"
# Compound words (FR-32c): a quoted text glued to a wildcard is one word, and never reaches the root folder.
mkdir -p "$WORK/glob/my docs"
echo x > "$WORK/glob/my docs/n.md"
"$PJ" -c '^printf "[%s]\n" "my docs"/* ; ^echo "nowhere"/* ; ^printf "[%s]\n" --message="a b" ; ^echo x"y"z ; ls "my docs"/*.md | map FileEntry::name' > "$WORK/compound.txt"
cat "$WORK/compound.txt"
expect "$WORK/compound.txt" '^\[my docs/n.md\]$' '^nowhere/[*]$' '^\[--message=a b\]$' '^xyz$' '^n.md$'
if grep -q '^/bin' "$WORK/compound.txt"; then
    echo "a quoted folder followed by a wildcard listed the root folder" >&2
    exit 1
fi
echo "wildcards: OK"
