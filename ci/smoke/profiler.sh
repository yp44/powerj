#!/usr/bin/env bash
# The profiler of the perf mode (Java Flight Recorder) is only resolved when asked: the image must hold the modules and
# the launcher configuration must ask for it, otherwise perf mode has no recording.
. "$(dirname "$0")/common.sh"

java="$(find "$APP" -path '*runtime/bin/java' | head -1)"
"$java" --list-modules | grep -q '^jdk.jfr@'
"$java" --list-modules | grep -q '^jdk.management@'
grep -q -- '--add-modules=jdk.jfr' "$(find "$APP" -name powerj.cfg | head -1)"
echo "profiler: OK"
