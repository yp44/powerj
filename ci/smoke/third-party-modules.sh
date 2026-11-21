#!/usr/bin/env bash
# A third-party module (examples/greet) dropped into ~/.powerj/modules is loaded; its classes stay out of Java expressions.
# Another one (examples/samples) is loaded with mod-load, and its cmdlet reads the pipeline.
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

# passthrough: a null reaches the cmdlet; --optional is kept at the end of a line, unrolled before the next stage (FR-30b)
"$PJ" -c 'mod-load examples/samples/target/samples.jar; $l = java.util.Arrays.asList(1, null, 2); $l | passthrough | count; $l | passthrough -n | map { x -> x * 10 }; $l | passthrough -o; $l | passthrough -o | count; mod-list' > "$WORK/samples.txt"
cat "$WORK/samples.txt"
expect "$WORK/samples.txt" '^3$' '^10$' '^20$' 'Optional\[1\]' 'Optional.empty' '^2$' 'com.example.samples .*\[passthrough\]'
echo "third-party modules: OK"
