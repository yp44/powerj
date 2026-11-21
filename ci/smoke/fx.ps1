# The optional JavaFX module (powerj-fx): consent for native code and loading. Opening a window is checked by hand
# (docs/acceptance/fx-grid.md); fx.sh does it under xvfb on Linux.
. "$PSScriptRoot/common.ps1"

smoke fx {
    $env:POWERJ_HOME = "$WORK\fxhome"
    New-Item -ItemType Directory -Force "$env:POWERJ_HOME\modules" | Out-Null
    Expand-Archive (Get-ChildItem powerj-fx\target\powerj-fx-*-win.zip).FullName "$env:POWERJ_HOME\modules"
    $refusal = & $PJ -c 'mod-list' 2>&1 | Out-String
    $refusal
    # The message gives the line to put in config.properties: the answer is tied to the content of the module.
    $allow = [regex]::Match($refusal, 'modules\.nativeAccess\.io\.powerj\.fx=sha256:[0-9a-f]{64}').Value
    if (-not $allow) { throw "consent is missing: $refusal" }
    Set-Content "$env:POWERJ_HOME\config.properties" $allow
    $modules = & $PJ -c 'mod-list' | Out-String
    $modules
    if (-not ($modules -match 'io.powerj.fx')) { throw 'mod-list does not list io.powerj.fx' }
    $help = & $PJ -c 'help grid' | Out-String
    $help
    if (-not ($help -match '--max-rows')) { throw "help grid has no --max-rows: $help" }
}
