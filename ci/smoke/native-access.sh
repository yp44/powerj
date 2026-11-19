#!/usr/bin/env bash
# The consent to native access is a gate (ADR 0006, #170): a third-party module that does not declare
# Enable-Native-Access cannot load a library or call the FFM API, because the launcher passes --illegal-native-access=deny.
# Without the option the JDK 25 default is a warning on the error stream and the call goes through.
. "$(dirname "$0")/common.sh"

API="$(ls powerj-api/target/powerj-api-*.jar | grep -v -e sources -e javadoc | head -1)"
[ -n "$API" ] || { echo "powerj-api jar not found (./mvnw -Pdist verify builds it)" >&2; exit 2; }

SRC="$WORK/src"
CLASSES="$WORK/classes"
mkdir -p "$SRC/nonative" "$CLASSES/META-INF/services"
cat > "$SRC/nonative/Probe.java" <<'JAVA'
package nonative;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;

/** Loads a library (a restricted method) without having declared Enable-Native-Access. */
@CmdletInfo(name = "probe", summary = "Calls the FFM API")
public final class Probe implements Cmdlet<Probe.Params, Void, String> {

    public record Params() { }

    @Override
    public void begin(Params params, CmdletContext<String> context) {
        try {
            System.loadLibrary("powerj-no-such-library");
        } catch (UnsatisfiedLinkError e) {
            context.emit("reached the loader"); // the access check passed: only the library is missing
        }
    }
}
JAVA
cat > "$SRC/nonative/Provider.java" <<'JAVA'
package nonative;

import java.util.List;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletProvider;

public final class Provider implements CmdletProvider {
    @Override
    public List<Cmdlet<?, ?, ?>> cmdlets() {
        return List.of(new Probe());
    }
}
JAVA
javac -nowarn -d "$CLASSES" -cp "$API" "$SRC"/nonative/*.java
echo "nonative.Provider" > "$CLASSES/META-INF/services/io.powerj.api.CmdletProvider"

export POWERJ_HOME="$WORK/pjhome"
mkdir -p "$POWERJ_HOME/modules"
jar --create --file "$POWERJ_HOME/modules/nonative.jar" -C "$CLASSES" .

"$PJ" -c 'probe' > "$WORK/probe.txt" 2>&1 || true
cat "$WORK/probe.txt"
if grep -q 'reached the loader' "$WORK/probe.txt"; then
    echo "a module without Enable-Native-Access loaded a library: --illegal-native-access=deny is missing" >&2
    exit 1
fi
grep -qi "native" "$WORK/probe.txt" || { echo "the refusal does not say native access" >&2; exit 1; }

# The other road stays open: a module that declares Enable-Native-Access gets its access once the user has said yes
# for this content (ADR 0006). The refusal at the start says which line of config.properties answers.
rm -f "$POWERJ_HOME/modules/nonative.jar"
printf 'Enable-Native-Access: true\n' > "$WORK/manifest.txt"
jar --create --file "$POWERJ_HOME/modules/declared.jar" --manifest "$WORK/manifest.txt" -C "$CLASSES" .
"$PJ" -c 'probe' > "$WORK/asked.txt" 2>&1 || true
cat "$WORK/asked.txt"
LINE="$(grep -o 'modules\.nativeAccess\.declared=sha256:[0-9a-f]*' "$WORK/asked.txt" | head -1)"
[ -n "$LINE" ] || { echo "the module that declares native access was not asked about" >&2; exit 1; }
echo "$LINE" >> "$POWERJ_HOME/config.properties"
"$PJ" -c 'probe' > "$WORK/granted.txt" 2>&1 || true
cat "$WORK/granted.txt"
expect "$WORK/granted.txt" 'reached the loader'
echo "native access: OK"
