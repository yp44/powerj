#!/usr/bin/env bash
# The subject of each commit of a pull request follows `type(scope): subject` (#184, audit v2 finding C-09).
#
# CONTRIBUTING.md ("How we work") lists the types. Seven commits of main had a scope where the type goes (`shell:`,
# `select:`, `fx:`…): history is not rewritten, the check keeps the next ones right. A merge commit and a revert made by git
# pass as they are. No dependency: git and bash.
#
#   ci/check-commit-subjects.sh origin/main HEAD     # the commits of HEAD that origin/main does not have
#   printf '%s\n' 'fix(fx): x' 'shell: y' | ci/check-commit-subjects.sh -
set -euo pipefail

types='feat|fix|refactor|docs|test|ci|build|perf|chore|style'
# A scope is one or more lower-case words separated by commas: `refactor(api,cmdlets): …`.
scope='\([a-z0-9-]+(,[a-z0-9-]+)*\)'
convention="^($types)($scope)?!?: [^ ]"
# What git writes itself: `Merge pull request #12 from …`, `Merge branch 'main' into …`, `Revert "…"`.
generated='^(Merge (pull request|branch|remote-tracking branch) |Revert ")'

if [ "$#" -eq 1 ] && [ "$1" = "-" ]; then
    subjects="$(cat)"
elif [ "$#" -eq 2 ]; then
    subjects="$(git log --format=%s "$1..$2")"
else
    echo "usage: $0 <base> <head> | $0 -" >&2
    exit 2
fi

bad=0
while IFS= read -r subject; do
    [ -z "$subject" ] && continue
    if [[ ! $subject =~ $convention ]] && [[ ! $subject =~ $generated ]]; then
        echo "commit subject outside the convention: $subject" >&2
        bad=$((bad + 1))
    fi
done <<< "$subjects"

if [ "$bad" -gt 0 ]; then
    echo "Write it as type(scope): subject, with a type among ${types//|/, } (CONTRIBUTING.md, \"How we work\")." >&2
    echo "Rename the commit (git rebase -i, reword) and push again." >&2
    exit 1
fi
echo "commit subjects: OK"
