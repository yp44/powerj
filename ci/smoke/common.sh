# Shared by the smoke tests of the packaged launcher (run from the root of the repository, after
# `./mvnw -Pdist verify`). PJ can name another launcher.
#
#   ci/smoke/launcher.sh                 the session of a user: cmdlets, Java, native commands, exit code
#   ci/smoke/profiler.sh                 the runtime of the image holds the profiler of the perf mode
#   ci/smoke/modules.sh                  the runtime of the image holds the modules that a module loaded later needs
#   ci/smoke/non-interactive.sh          -c, a script file, standard input
#   ci/smoke/third-party-modules.sh      greet.jar loaded from ~/.powerj/modules
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
# because the return value of "GraphicsPipeline.getPipeline()" is null`, with the stack of the picking of the scene, and
# nothing of PowerJ or of the JDK is in it. It happened twice in the first runs of the CI that were looked at, and in 6 runs
# out of 40 under xvfb on a development machine (#190); no check of the smoke test depends on it. That one line is
# ignored; any other exception, a restricted-method warning or a failure to start the toolkit is still reported.
unexpected_window_messages() {
    local known='NullPointerException: Cannot invoke "com.sun.prism.GraphicsPipeline.is3DSupported()" because the return value of "com.sun.prism.GraphicsPipeline.getPipeline()" is null'
    grep -Ei 'restricted method|cannot start|exception' "$1" | grep -vF "$known" || true
}
