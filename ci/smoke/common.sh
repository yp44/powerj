# Shared by the smoke tests of the packaged launcher (run from the root of the repository, after
# `./mvnw -Pdist verify`). PJ can name another launcher.
#
#   ci/smoke/launcher.sh                 the session of a user: cmdlets, Java, native commands, exit code
#   ci/smoke/profiler.sh                 the runtime of the image holds the profiler of the perf mode
#   ci/smoke/modules.sh                  the runtime of the image holds the modules that a module loaded later needs
#   ci/smoke/non-interactive.sh          -c, a script file, standard input
#   ci/smoke/third-party-modules.sh      greet.jar loaded from ~/.powerj/modules
#   ci/smoke/native-access.sh            the consent to native access is a gate: a module that did not declare it is refused
#   ci/smoke/properties.sh               the built-in cmdlets read the objects of the core and of a third-party module
#   ci/smoke/languages.sh                English and French messages
#   ci/smoke/tilde.sh                    ~ is the home folder
#   ci/smoke/wildcards.sh                wildcards in the arguments of a native command, and the case rule
#   ci/smoke/fx.sh                       the optional JavaFX module, under a virtual display (xvfb)
set -euo pipefail

PJ="${PJ:-$PWD/powerj-dist/target/jpackage/powerj/bin/powerj}"
APP="$(dirname "$(dirname "$PJ")")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

[ -x "$PJ" ] || { echo "launcher not found: $PJ (build it with ./mvnw -Pdist verify)" >&2; exit 2; }

# expect FILE PATTERN...: every pattern (grep -q) must be found in FILE.
expect() {
    local file="$1"; shift
    for pattern in "$@"; do
        grep -q -- "$pattern" "$file" || { echo "missing in the output: $pattern" >&2; exit 1; }
    done
}

# unexpected_window_messages FILE: what the output of a JavaFX window run holds that is neither normal nor known noise.
#
# `-c` ends the shell, and the JVM, as soon as the window is shown. JavaFX can then be torn down while its own thread is
# still handling an event of the pointer: it prints `NullPointerException: Cannot invoke "GraphicsPipeline.is3DSupported()"
# because the return value of "GraphicsPipeline.getPipeline()" is null`, with the stack of the picking of the scene
# (`PickResultChooser.processOffer`). It happened twice in the first runs of the CI that were looked at, and in 6 runs out
# of 40 under xvfb on a development machine (#190); no check of the smoke test depends on it.
#
# That exception is ignored in two cases: its stack is that of the picking and holds no frame of PowerJ (`io.powerj`); or it
# has no stack at all, which is what the JVM prints for an exception that it throws very often (it leaves the stack out, a
# run of the CI on main printed that line alone). The message with another stack, or with a frame of PowerJ, is a real
# failure and is reported, since the same words from another place are not this noise. The third case is the same race one
# step earlier: the JVM exits while the thread is printing, and the output ends with the header `Exception in thread
# "JavaFX Application Thread"` and nothing after it (1 run in 30 with JavaFX 27.0.1). It is ignored only as the very last
# line of the output, and only with that thread name. Any other exception, a
# restricted-method warning or a failure to start the toolkit is reported too, and so is this one when the JDK words it
# differently (the job then fails and somebody looks, which is the safe way to be wrong). ci/smoke/window-filter-test.sh
# tests every case.
unexpected_window_messages() {
    local known='NullPointerException: Cannot invoke "com.sun.prism.GraphicsPipeline.is3DSupported()" because the return value of "com.sun.prism.GraphicsPipeline.getPipeline()" is null'
    awk -v known="$known" '
        { line[NR] = $0 }
        END {
            for (i = 1; i <= NR; i++) {
                if (tolower(line[i]) !~ /restricted method|cannot start|exception/) continue
                if (i == NR && line[i] ~ /^Exception in thread "JavaFX Application Thread"[ \t]*$/) continue
                if (index(line[i], known) > 0) {
                    frames = 0; picking = 0; ours = 0
                    for (j = i + 1; j <= NR && line[j] ~ /^[ \t]+at /; j++) {
                        frames++
                        if (index(line[j], "PickResultChooser.processOffer") > 0) picking = 1
                        if (index(line[j], "io.powerj") > 0) ours = 1
                    }
                    if (frames == 0) continue
                    if (picking && !ours) continue
                }
                print line[i]
            }
        }' "$1"
}
