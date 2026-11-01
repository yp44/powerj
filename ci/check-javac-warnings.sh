#!/usr/bin/env bash
# The javac warnings (-Xlint:all) of a build must not go up (#84, review finding C-32).
#
# Reads the log of a CLEAN Maven build, counts the warnings by module and by source set (main / test) and compares them
# with docs/quality/javac-warnings.txt, the number that each is allowed. A count above its line fails; a count below
# it is good news that the file should record, so that it cannot come back unnoticed.
#
#   ./mvnw -B clean verify 2>&1 | tee build.log
#   ci/check-javac-warnings.sh build.log
#
# A build that did not compile (incremental: nothing changed) prints no warning and proves nothing: the script says so.
set -euo pipefail

log="${1:?usage: ci/check-javac-warnings.sh <log of a clean maven build>}"
baseline="$(cd "$(dirname "$0")/.." && pwd)/docs/quality/javac-warnings.txt"

[ -r "$log" ] || { echo "cannot read the log: $log" >&2; exit 2; }
[ -r "$baseline" ] || { echo "cannot read the baseline: $baseline" >&2; exit 2; }

# "[WARNING] /path/powerj-core/src/main/java/io/powerj/core/X.java:[12,8] message"  ->  "powerj-core main"
counted="$(grep -E '^\[WARNING\] .*\.java:\[[0-9]+,[0-9]+\] ' "$log" | awk '
    {
        if (match($0, /(powerj-[a-z]+|examples\/[a-z]+)\/src\/(main|test)\/java\//)) {
            split(substr($0, RSTART, RLENGTH), part, "/src/")
            module = part[1]
            sub(/^examples\//, "", module)
            scope = (part[2] ~ /^main/) ? "main" : "test"
            count[module " " scope]++
        } else {
            count["unknown unknown"]++
        }
    }
    END { for (key in count) print key, count[key] }
' | sort || true)"

allowed="$(grep -Ev '^[[:space:]]*(#|$)' "$baseline" | sort)"

total_allowed="$(awk '{ sum += $3 } END { print sum + 0 }' <<<"$allowed")"
total_counted="$(awk '{ sum += $3 } END { print sum + 0 }' <<<"$counted")"

if [ "$total_counted" -eq 0 ] && [ "$total_allowed" -gt 0 ]; then
    echo "No javac warning at all in $log, while $total_allowed are allowed: was it a clean build (mvnw clean ...)?" >&2
    exit 2
fi

status=0
while read -r module scope n; do
    [ -n "$module" ] || continue
    limit="$(awk -v m="$module" -v s="$scope" '$1 == m && $2 == s { print $3 }' <<<"$allowed")"
    limit="${limit:-0}"
    if [ "$n" -gt "$limit" ]; then
        echo "::error::javac warnings of $module ($scope): $n, no more than $limit allowed (docs/quality/javac-warnings.txt)" >&2
        grep -E '^\[WARNING\] .*\.java:\[[0-9]+,[0-9]+\] ' "$log" | grep -F "/$module/src/$scope/java/" \
            | sed -E 's#^\[WARNING\] [^ ]*/(src/[a-z]+/java/)#  \1#' | cut -c1-200 >&2
        status=1
    fi
done <<<"$counted"

# A line of the baseline that the build no longer reaches: the warnings are gone, the file should say so.
while read -r module scope limit; do
    [ -n "$module" ] || continue
    n="$(awk -v m="$module" -v s="$scope" '$1 == m && $2 == s { print $3 }' <<<"$counted")"
    n="${n:-0}"
    if [ "$n" -lt "$limit" ]; then
        echo "javac warnings of $module ($scope): $n, fewer than the $limit allowed: lower the line in docs/quality/javac-warnings.txt to $n"
    fi
done <<<"$allowed"

if [ "$status" -eq 0 ]; then
    echo "javac warnings: $total_counted (no more than the $total_allowed allowed)"
fi
exit "$status"
