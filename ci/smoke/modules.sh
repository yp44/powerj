#!/usr/bin/env bash
# The runtime of the image holds the modules that a module loaded later from ~/.powerj/modules (powerj-fx, a third-party
# module) finds in the boot layer: the API, the file system library and powerj-text (ADR 0010). One that is not in the
# image would make such a module fail to load, whatever the machine.
. "$(dirname "$0")/common.sh"

# The runtime is at lib/runtime/bin/java in the image of Linux, and at Contents/runtime/Contents/Home/bin/java in the
# application bundle of macOS.
java="$(find "$APP" -path '*runtime*bin/java' | head -1)"
[ -n "$java" ] || { echo "no Java runtime found in $APP" >&2; exit 2; }
modules="$("$java" --list-modules)"
for module in io.powerj.api io.powerj.fs io.powerj.text io.powerj.core io.powerj.cmdlets io.powerj.shell; do
    echo "$modules" | grep -q "^$module@" || { echo "$module is not in the image" >&2; exit 1; }
done
echo "modules: OK"
