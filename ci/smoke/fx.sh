#!/usr/bin/env bash
# The optional JavaFX module (powerj-fx): consent for native code, loading, a real window under xvfb, no display.
# Needs xvfb, libgtk-3-0, libxtst6, libxxf86vm1 and libgl1 (the CI installs them).
. "$(dirname "$0")/common.sh"

command -v xvfb-run > /dev/null || { echo "xvfb-run is needed (apt-get install xvfb)" >&2; exit 2; }
export POWERJ_HOME="$WORK/fxhome"
mkdir -p "$POWERJ_HOME/modules"
unzip -q powerj-fx/target/powerj-fx-*-linux.zip -d "$POWERJ_HOME/modules"
# Without an answer on record and without a terminal, a module that uses native code is not loaded (specification §4.4).
"$PJ" -c 'mod-list' > "$WORK/fx.txt" 2>&1
printf 'modules.nativeAccess.io.powerj.fx=false\n' > "$POWERJ_HOME/config.properties"
"$PJ" -c 'mod-list' >> "$WORK/fx.txt" 2>&1
printf 'modules.nativeAccess.io.powerj.fx=true\n' > "$POWERJ_HOME/config.properties"
xvfb-run -a "$PJ" -c 'mod-list' >> "$WORK/fx.txt" 2>&1
xvfb-run -a "$PJ" -c 'help grid' >> "$WORK/fx.txt" 2>&1
# A real JavaFX window opens in the module's layer, with no JDK error or warning about native code.
# NO_AT_BRIDGE: the runner has no accessibility bus and GTK complains otherwise (a message unrelated to PowerJ).
NO_AT_BRIDGE=1 xvfb-run -a "$PJ" -c 'ls | grid --title ci --columns name,size' > "$WORK/fxwin.txt" 2>&1
# Without a display: a clear message, and the shell goes on.
env -u DISPLAY "$PJ" -c 'ls | grid' >> "$WORK/fx.txt" 2>&1 || true
cat "$WORK/fx.txt" "$WORK/fxwin.txt"
expect "$WORK/fx.txt" 'no answer is recorded (set modules.nativeAccess.io.powerj.fx=true' \
    'native access refused for module io.powerj.fx' 'io.powerj.fx .*\[grid\]' '--max-rows' \
    'the graphical toolkit cannot start'
if grep -Eiq 'restricted method|cannot start|exception' "$WORK/fxwin.txt"; then
    echo "window: unexpected message" >&2
    exit 1
fi
echo "fx: OK"
