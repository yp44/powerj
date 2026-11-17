#!/usr/bin/env bash
# A test must not write to the native stream of the forked JVM (#145, audit v2 finding B-06).
#
# Surefire talks to its forked JVM through its standard output. A test (or a child process that inherits the descriptor)
# that writes there directly corrupts that channel: Surefire prints "Corrupted channel by directly writing to native
# stream" and keeps the lost bytes in a *.dumpstream file of target/surefire-reports. The warning is easy to miss in a log
# of thousands of lines, and it hides the day a test really loses a report, so the CI looks for the files.
#
#   ./mvnw -B verify
#   ci/check-surefire-channel.sh
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
files="$(find "$root" -path '*/target/surefire-reports/*.dumpstream' | sort)"

if [ -n "$files" ]; then
    echo "a test wrote to the native stream of the forked JVM (what was lost is in these files):" >&2
    while IFS= read -r file; do
        echo "  ${file#"$root"/}: $(tr -d '\n' < "$file" | cut -c1-200)" >&2
    done <<< "$files"
    echo "Redirect the output of the child process that is not under test (see ConsoleInputTest), or capture the stream." >&2
    exit 1
fi
echo "surefire channel: OK"
