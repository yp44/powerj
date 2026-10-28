# Shared by the smoke tests of the packaged launcher (run from the root of the repository, after
# `./mvnw -Pdist verify`). PJ can name another launcher.
#
#   ci/smoke/launcher.sh                 the session of a user: cmdlets, Java, native commands, exit code
#   ci/smoke/profiler.sh                 the runtime of the image holds the profiler of the perf mode
#   ci/smoke/non-interactive.sh          -c, a script file, standard input
#   ci/smoke/third-party-modules.sh      greet.jar loaded from ~/.powerj/modules
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
