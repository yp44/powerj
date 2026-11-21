# The runtime of the image holds the modules of PowerJ (modules.sh).
. "$PSScriptRoot/common.ps1"

smoke modules {
    $modules = & (runtime_java) --list-modules
    foreach ($module in 'io.powerj.api', 'io.powerj.fs', 'io.powerj.text', 'io.powerj.core', 'io.powerj.cmdlets',
                        'io.powerj.shell') {
        if (-not ($modules -match ('^' + [regex]::Escape($module) + '@'))) { throw "$module is not in the image" }
    }
}
