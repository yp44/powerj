# The runtime of the image holds the profiler of the perf mode, and the launcher asks for it (profiler.sh).
. "$PSScriptRoot/common.ps1"

smoke profiler {
    $modules = & (runtime_java) --list-modules
    if (-not ($modules -match '^jdk\.jfr@')) { throw 'jdk.jfr is not in the image' }
    if (-not ($modules -match '^jdk\.management@')) { throw 'jdk.management is not in the image' }
    # The profiler is only resolved when asked: without this option the perf mode has no recording.
    $cfg = Get-ChildItem -LiteralPath $APP -Recurse -Filter powerj.cfg | Select-Object -First 1
    if (-not (Select-String -LiteralPath $cfg.FullName -SimpleMatch '--add-modules=jdk.jfr' -Quiet)) {
        throw 'option --add-modules=jdk.jfr is not in powerj.cfg'
    }
}
