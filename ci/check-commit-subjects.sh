#!/usr/bin/env bash
# The subject of each commit of a pull request, and its title, follow `type(scope): subject` (#184, audit v2 finding C-09).
#
# CONTRIBUTING.md ("How we work") lists the types. Seven commits of main had a scope where the type goes (`shell:`,
# `select:`, `fx:`…): history is not rewritten, the check keeps the next ones right. A pull request is merged with "Squash
# and merge", whose commit takes the title of the pull request as its subject: the title is checked as a subject too. A
# merge commit and a revert made by git pass as they are. No dependency: git and bash.
#
#   ci/check-commit-subjects.sh origin/main HEAD     # the commits of HEAD that origin/main does not have
#   ci/check-commit-subjects.sh --title "fix(fx): x" # the title of a pull request
#   printf '%s\n' 'fix(fx): x' 'shell: y' | ci/check-commit-subjects.sh -
set -euo pipefail

types='feat|fix|refactor|docs|test|ci|build|perf|chore|style'
# A scope is one or more lower-case words separated by commas: `refactor(api,cmdlets): …`.
scope='\([a-z0-9-]+(,[a-z0-9-]+)*\)'
convention="^($types)($scope)?!?: [^ ]"
# What git writes itself: `Merge pull request #12 from …`, `Merge branch 'main' into …`, `Revert "…"`, and
# `Reapply "…"` for the revert of a revert (git 2.43 and later).
generated='^(Merge (pull request|branch|remote-tracking branch) |Revert "|Reapply ")'

what="commit subject"
advice="Rename the commit (git rebase -i, reword) and push again."
if [ "$#" -eq 2 ] && [ "$1" = "--title" ]; then
    subjects="$2"
    what="pull request title"
    advice="Edit the title of the pull request: \"Squash and merge\" makes it the subject of the commit on main."
elif [ "$#" -eq 1 ] && [ "$1" = "-" ]; then
    subjects="$(cat)"
elif [ "$#" -eq 2 ]; then
    subjects="$(git log --format=%s "$1..$2")"
else
    echo "usage: $0 <base> <head> | $0 --title <title> | $0 -" >&2
    exit 2
fi
if [ "$what" = "pull request title" ] && [ -z "$subjects" ]; then
    echo "pull request title outside the convention: (empty)" >&2
    exit 1
fi

bad=0
while IFS= read -r subject; do
    [ -z "$subject" ] && continue
    if [[ ! $subject =~ $convention ]] && [[ ! $subject =~ $generated ]]; then
        echo "$what outside the convention: $subject" >&2
        bad=$((bad + 1))
    fi
done <<< "$subjects"

if [ "$bad" -gt 0 ]; then
    echo "Write it as type(scope): subject, with a type among ${types//|/, } (CONTRIBUTING.md, \"How we work\")." >&2
    echo "$advice" >&2
    exit 1
fi
echo "${what}s: OK"
