#!/usr/bin/env bash
# The built-in cmdlets read the properties of the objects that come from other modules (#130): the Job of `jobs`, a record of the
# core, and the records of a third-party module (examples/greet) loaded from ~/.powerj/modules. A module can read by reflection
# only the records of packages exported or opened to it, so this cannot be seen in the tests of one module: each line is a fresh
# session, before any value has been displayed (the display of the core used to make the accessors readable for everyone).
. "$(dirname "$0")/common.sh"

export POWERJ_HOME="$WORK/pjhome"
mkdir -p "$POWERJ_HOME/modules"
# A job outlives the session that starts it: when a session fails before its own "jobs | kill", the jobs are stopped here (each
# has its own duration, 30 to 32 seconds, so that no other sleep is stopped).
trap 'pkill -f "sleep 3[0-2]\$" 2> /dev/null || true; rm -rf "$WORK"' EXIT
cp examples/greet/target/greet.jar "$POWERJ_HOME/modules/"

# session FILE SCRIPT: runs a session of -c, both streams go to FILE; a session that fails shows what it printed.
session() {
    "$PJ" -c "$2" > "$1" 2>&1 || { echo "the session failed: $2" >&2; grep -v 'Picked up' "$1" >&2; exit 1; }
}

# Nothing may say that a property could not be read.
refused() {
    ! grep -qE 'cannot read|not a process|IllegalAccess' "$1" || { echo "a property could not be read:" >&2; cat "$1" >&2; exit 1; }
}

# The record of the core: read by to-json, to-csv and kill. A job outlives the session that starts it: each line stops its own.
session "$WORK/jobs-json.txt" 'sleep 30 &; jobs | to-json; jobs | kill'
expect "$WORK/jobs-json.txt" '"pid": [0-9]' '"command": "sleep 30"' '"start": "' '"running": true'
refused "$WORK/jobs-json.txt"

session "$WORK/jobs-csv.txt" 'sleep 31 &; jobs | to-csv; jobs | kill'
expect "$WORK/jobs-csv.txt" '^id,pid,command,start,running,exitCode$' ',sleep 31,'
refused "$WORK/jobs-csv.txt"

session "$WORK/jobs-kill.txt" 'sleep 32 &; jobs | kill; jobs'
expect "$WORK/jobs-kill.txt" 'sleep 32' ' 143 '    # the job was stopped by kill: its exit code is 128 + SIGTERM
refused "$WORK/jobs-kill.txt"

# The records of a third-party module: read by to-json and to-csv.
session "$WORK/greet-json.txt" 'greet --name Yves | to-json'
expect "$WORK/greet-json.txt" '"name": "Yves"' '"message": "Hello Yves!"'
refused "$WORK/greet-json.txt"

session "$WORK/greet-csv.txt" 'greet --name Yves | to-csv'
expect "$WORK/greet-csv.txt" '^name,message,at$' '^Yves,Hello Yves!,'
refused "$WORK/greet-csv.txt"

echo "properties read across modules: OK"
