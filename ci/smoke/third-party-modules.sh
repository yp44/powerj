#!/usr/bin/env bash
# A third-party module (examples/greet) dropped into ~/.powerj/modules is loaded; its classes stay out of Java expressions.
# Another one (examples/samples) is loaded with mod-load: passthrough reads the pipeline, echo-options shows its options.
. "$(dirname "$0")/common.sh"

export POWERJ_HOME="$WORK/pjhome"
mkdir -p "$POWERJ_HOME/modules"
cp examples/greet/target/greet.jar "$POWERJ_HOME/modules/"
"$PJ" -c 'greet --name Yves -c 2 | where { g -> g.message.contains("Yves") } | map { g -> g.message }' > "$WORK/greet.txt"
"$PJ" -c 'mod-list' >> "$WORK/greet.txt"
"$PJ" -c 'new com.example.greet.Greeting("a", "b", null)' >> "$WORK/greet.txt" 2>&1 || true
cat "$WORK/greet.txt"
[ "$(grep -c 'Hello Yves!' "$WORK/greet.txt")" -eq 2 ] || { echo "greet did not run twice" >&2; exit 1; }
expect "$WORK/greet.txt" 'com.example.greet .*\[greet\]' 'class not found: com.example.greet.Greeting'

# passthrough: a null reaches the cmdlet (count 3), -s drops it (count 2), -o is kept at the end of a line and unrolled
# before the next stage (FR-30b), -so gives no Optional.empty. The whole output is compared, and stderr must stay empty: an
# option that did nothing, or a null that reached a cmdlet that cannot take it, would change one or the other.
"$PJ" -c 'mod-load examples/samples/target/samples.jar; $l = java.util.Arrays.asList(1, null, 2); $l | passthrough | count; $l | passthrough -s | count; $l | passthrough -o; $l | passthrough -o | count; $l | passthrough -so' > "$WORK/samples.txt" 2> "$WORK/samples.err"
cat "$WORK/samples.txt" "$WORK/samples.err"
[ "$(paste -sd, "$WORK/samples.txt")" = 'passthrough,echo-options,3,2,Optional[1],Optional.empty,Optional[2],2,Optional[1],Optional[2]' ] || { echo "passthrough is wrong" >&2; exit 1; }
[ ! -s "$WORK/samples.err" ] || { echo "passthrough wrote errors" >&2; exit 1; }
"$PJ" -c 'mod-load examples/samples/target/samples.jar; mod-list' > "$WORK/samples-list.txt"
expect "$WORK/samples-list.txt" 'com.example.samples .*\[passthrough, echo-options\]'

# echo-options: every option bound as the shell converts it, the defaults, and a block evaluated by the cmdlet ({ … }) against
# a value given by the shell (( … )). A block that fails is a non-blocking error: the object is still emitted (count 1).
"$PJ" -c 'mod-load examples/samples/target/samples.jar; echo-options -t hi -c 3 --number 7 --size 10kb --level high -v --timeout 1.5s --tag a --tag b --block { 1 + 2 } one two | map { o -> java.util.Arrays.asList(o.text, o.count, o.number, o.size, o.level, o.verbose, o.timeout, o.tags, o.block, o.blockValue, o.words).toString() }; echo-options | map { o -> java.util.Arrays.asList(o.count, o.number, o.verbose, o.tags, o.words, o.block, o.blockValue).toString() }; echo-options --block (1 + 2) | map { o -> java.util.Arrays.asList(o.block, o.blockValue).toString() }; echo-options --block { $_ == null } | map { o -> o.blockValue }' > "$WORK/echo.txt" 2> "$WORK/echo.err"
cat "$WORK/echo.txt" "$WORK/echo.err"
[ "$(paste -sd, "$WORK/echo.txt")" = 'passthrough,echo-options,[hi, 3, 7, 10240, HIGH, true, PT1.5S, [a, b], { 1 + 2 }, 3, [one, two]],[0, null, false, [], [], null, null],[3, 3],true' ] || { echo "echo-options is wrong" >&2; exit 1; }
[ ! -s "$WORK/echo.err" ] || { echo "echo-options wrote errors" >&2; exit 1; }
"$PJ" -c 'mod-load examples/samples/target/samples.jar; echo-options --block { 1 / 0 } | count; echo-options --count abc' > "$WORK/echo-errors.txt" 2>&1 || true
cat "$WORK/echo-errors.txt"
expect "$WORK/echo-errors.txt" '^echo-options: --block: calculation failed: division by zero$' '^1$' \
    '^echo-options: invalid value for --count: "abc" (an integer expected)$'
echo "third-party modules: OK"
