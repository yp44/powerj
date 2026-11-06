#!/usr/bin/env python3
"""Issue #128 (Linux, run by hand): Ctrl+C and the terminal, on a real pseudo-terminal.

The terminal is a pty. Its session leader plays the user's shell: it starts PtyProbe (see its Javadoc) in a new process
group and makes that group the foreground group of the terminal (setpgid + tcsetpgrp, as bash does for a foreground job).
The script then writes the Ctrl+C character to the master, as a keyboard does, and reports which of B (the background
command) and C (a companion in the foreground group) are alive. It also looks at what a background process group gets
from the terminal: SIGTTOU when it writes with `stty tostop`, SIGTTIN when it reads.

    ./mvnw -pl powerj-core -am test-compile          # compiles PtyProbe
    JAVA=<jdk 25>/bin/java python3 powerj-core/src/test/prototype/pty-check.py

Reads: B started as `plain` (ProcessBuilder, as a console program on Windows today), `shield` (sh + trap '' INT QUIT, as
on Unix today), `group` (posix_spawn with its own process group, the prototype, standard input inherited) or `launcher`
(BackgroundLauncher, what the shell runs for a background command; standard input is the null device).
"""
import os
import pty
import re
import select
import signal
import sys
import termios
import time

JAVA = os.environ.get("JAVA", "java")
CLASSES = os.environ.get("CLASSES", "powerj-core/target/test-classes")
PROBE = "io.powerj.core.nativeexec.PtyProbe"


def state(pid):
    """alive / STOPPED / dead (a zombie is dead; its parent is gone and nothing reaps it here)."""
    try:
        with open(f"/proc/{pid}/stat") as f:
            fields = f.read().rsplit(")", 1)[1].split()
    except FileNotFoundError:
        return "dead"
    return {"Z": "dead", "X": "dead", "T": "STOPPED", "t": "STOPPED"}.get(fields[0], "alive")


def drain(master, seconds):
    """Everything the terminal prints during `seconds`."""
    end = time.time() + seconds
    data = b""
    while time.time() < end:
        ready, _, _ = select.select([master], [], [], 0.1)
        if ready:
            try:
                chunk = os.read(master, 65536)
            except OSError:
                break
            if not chunk:
                break
            data += chunk
    return data


def session_leader(mode, stdio, script):
    """In the child of pty.fork(): the user's shell. It starts the probe as a foreground job, then stays."""
    signal.signal(signal.SIGTTOU, signal.SIG_IGN)
    job = os.fork()
    if job == 0:
        os.setpgid(0, 0)
        os.execvp(JAVA, [JAVA, "--enable-native-access=ALL-UNNAMED", "-cp", CLASSES, PROBE, mode, stdio, script])
    os.setpgid(job, job)
    os.tcsetpgrp(0, job)
    time.sleep(120)
    os._exit(0)


def scenario(mode, stdio, script, tostop=False, ctrl_c=True, settle=1.5):
    leader, master = pty.fork()
    if leader == 0:
        session_leader(mode, stdio, script)
    output = b""
    deadline = time.time() + 30
    pids = None
    while time.time() < deadline and not pids:
        output += drain(master, 0.3)
        pids = re.search(rb"B=(\d+) C=(\d+)", output)
    if not pids:
        raise SystemExit("the probe did not start: " + output.decode(errors="replace"))
    b, c = int(pids.group(1)), int(pids.group(2))
    if tostop:
        attrs = termios.tcgetattr(master)
        attrs[3] |= termios.TOSTOP
        termios.tcsetattr(master, termios.TCSANOW, attrs)
    time.sleep(0.3)
    if ctrl_c:
        os.write(master, b"\x03")
    output += drain(master, settle)
    result = (state(b), state(c), b"hi-from-B" in output)
    for victim in (b, c):
        try:
            os.kill(victim, signal.SIGKILL)
        except ProcessLookupError:
            pass
    os.kill(leader, signal.SIGKILL)
    os.waitpid(leader, 0)
    os.close(master)
    return result


def main():
    rows = []
    for mode in ("plain", "shield", "group", "launcher"):
        b, c, _ = scenario(mode, "null", "exec sleep 60")
        rows.append(("Ctrl+C, then who is alive", mode, b, c))
    for mode in ("shield", "group"):
        b, c, saw = scenario(mode, "inherit", "sleep 1; echo hi-from-B; exec sleep 60", ctrl_c=False, settle=3)
        rows.append(("B writes to the terminal (tostop off)", mode, b, f"B's line printed: {saw}"))
    for mode in ("shield", "group", "launcher"):
        b, c, saw = scenario(mode, "inherit", "sleep 1; echo hi-from-B; exec sleep 60", tostop=True, ctrl_c=False,
                             settle=3)
        rows.append(("B writes to the terminal (stty tostop)", mode, b, f"B's line printed: {saw}"))
    for mode in ("shield", "group"):
        b, c, _ = scenario(mode, "inherit", "sleep 1; read line; exec sleep 60", ctrl_c=False, settle=3)
        rows.append(("B reads the terminal", mode, b, c))
    print(f"{'scenario':<40} {'B started as':<13} {'B':<8} {'C (foreground companion)'}")
    for scenario_name, mode, b, c in rows:
        print(f"{scenario_name:<40} {mode:<13} {b:<8} {c}")


if __name__ == "__main__":
    sys.exit(main())
