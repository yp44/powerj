#!/usr/bin/env bash
# Test of unexpected_window_messages (ci/smoke/common.sh), the filter that decides which messages of a JavaFX window run
# fail the smoke test of the graphical module (#190). It needs no launcher and no display:
#
#   ci/smoke/window-filter-test.sh
#
# The filter was wrong twice, in both directions: a line of text alone hid a failure that had the same words, and the stack
# of the exception was missing once (the JVM leaves it out of an exception it throws very often), so a stricter filter
# failed a build for noise. Each case below is one of those, or what has to stay reported.
set -euo pipefail
cd "$(dirname "$0")/../.."
PJ=/bin/true . ci/smoke/common.sh   # the functions of common.sh; a launcher is not needed to test one of them

npe='Exception in thread "JavaFX Application Thread" java.lang.NullPointerException: Cannot invoke "com.sun.prism.GraphicsPipeline.is3DSupported()" because the return value of "com.sun.prism.GraphicsPipeline.getPipeline()" is null'
frame_support=$'\tat javafx.graphics@25.0.5/com.sun.javafx.tk.quantum.QuantumToolkit.isSupported(QuantumToolkit.java:1234)'
frame_picking=$'\tat javafx.graphics@25.0.5/com.sun.javafx.scene.input.PickResultChooser.processOffer(PickResultChooser.java:182)'
frame_ours=$'\tat io.powerj.fx@0.2.0-SNAPSHOT/io.powerj.fx.view.GridView.show(GridView.java:1)'
frame_loop=$'\tat java.base/java.lang.Thread.run(Unknown Source)'

failures=0
# expect NAME ignored|reported CONTENT: the filter prints nothing for what it ignores
expect() {
    local name="$1" outcome="$2" file="$WORK/case.txt" shown
    printf '%s\n' "$3" > "$file"
    shown="$(unexpected_window_messages "$file")"
    if { [ "$outcome" = ignored ] && [ -z "$shown" ]; } || { [ "$outcome" = reported ] && [ -n "$shown" ]; }; then
        echo "ok: $name"
    else
        echo "FAILED: $name: expected the filter to say $outcome, it printed: [$shown]" >&2
        failures=$((failures + 1))
    fi
}

expect "the exception with the stack of the picking" ignored "$npe"$'\n'"$frame_support"$'\n'"$frame_picking"$'\n'"$frame_loop"
expect "the same exception with no stack at all" ignored "$npe"
expect "the same exception with no stack, then other output" ignored "$npe"$'\n'"window opened"
expect "the header of the exception cut by the exit of the JVM" ignored 'Exception in thread "JavaFX Application Thread" '
expect "a header cut in the middle of the output" reported 'Exception in thread "JavaFX Application Thread" '$'\n'"window opened"
expect "a header of another thread, cut" reported 'Exception in thread "main" '
expect "a stack that is not the picking" reported "$npe"$'\n'"$frame_support"$'\n'"$frame_loop"
expect "a stack with a frame of PowerJ" reported "$npe"$'\n'"$frame_support"$'\n'"$frame_picking"$'\n'"$frame_ours"
expect "another exception" reported 'Exception in thread "main" java.lang.IllegalStateException: boom'
expect "a restricted-method warning" reported 'WARNING: A restricted method in java.lang.System has been called'
expect "a toolkit that cannot start" reported 'grid: the graphical toolkit cannot start (no graphical display?)'
expect "a clean output" ignored 'ls | grid: opened'

[ "$failures" -eq 0 ] || { echo "$failures case(s) of the filter failed" >&2; exit 1; }
echo "window filter: OK"
