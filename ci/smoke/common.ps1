# Shared by the smoke tests of powerj.exe on Windows (run from the root of the repository, after
# `mvnw.cmd -Pdist verify`): the counterpart of common.sh, with the same sessions written for PowerShell and the programs
# of Windows (#183). PJ can name another launcher. ci.yml and release.yml run them.
#
#   ci/smoke/launcher.ps1             the session of a user (launcher.sh): cmdlets, Java, native commands, exit code
#   ci/smoke/profiler.ps1             the runtime of the image holds the profiler of the perf mode (profiler.sh)
#   ci/smoke/modules.ps1              the runtime of the image holds the modules of PowerJ (modules.sh)
#   ci/smoke/non-interactive.ps1      -c, a script file, standard input, the options of the launcher (non-interactive.sh)
#   ci/smoke/third-party-modules.ps1  greet.jar loaded from the modules folder, in English and French; samples.jar loaded
#                                     with mod-load (third-party-modules.sh)
#   ci/smoke/fx.ps1                   the JavaFX module: consent and loading; a window is checked by hand (fx.sh)
#
# A script ends with `exit 0` once its checks passed (the function `smoke` does it): a pwsh step of GitHub Actions takes
# the exit code of the last program it ran, and the launcher exits with 3 on purpose.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$PJ = [Environment]::GetEnvironmentVariable('PJ')
if (-not $PJ) { $PJ = Join-Path (Get-Location) 'powerj-dist\target\jpackage\powerj\powerj.exe' }
$APP = Split-Path -Parent $PJ
if (-not (Test-Path -LiteralPath $PJ)) {
    [Console]::Error.WriteLine("launcher not found: $PJ (build it with mvnw.cmd -Pdist verify)")
    exit 2
}
$WORK = Join-Path ([IO.Path]::GetTempPath()) ('powerj-smoke-' + [IO.Path]::GetRandomFileName())
New-Item -ItemType Directory -Path $WORK | Out-Null

# expect FILE TEXT...: every text must be found in FILE, as it is (not a pattern: a Windows path holds backslashes).
function expect {
    param([Parameter(Mandatory)] [string] $File, [Parameter(ValueFromRemainingArguments)] [string[]] $Texts)
    foreach ($text in $Texts) {
        if (-not (Select-String -LiteralPath $File -SimpleMatch -Pattern $text -Quiet)) {
            throw "missing in the output: $text"
        }
    }
}

# runtime_java: the java.exe of the runtime of the image.
function runtime_java {
    $java = Get-ChildItem -LiteralPath $APP -Recurse -Filter java.exe |
            Where-Object { $_.FullName -like '*runtime*bin*' } | Select-Object -First 1
    if (-not $java) { throw "no Java runtime found in $APP" }
    $java.FullName
}

# smoke NAME BODY: runs the checks, then puts back the variables of PowerJ that they set (several scripts can run in one
# PowerShell) and removes the work folder, however they end; ends the script with 0 if they passed.
function smoke {
    param([Parameter(Mandatory)] [string] $Name, [Parameter(Mandatory)] [scriptblock] $Body)
    $saved = @{}
    foreach ($variable in 'POWERJ_HOME', 'POWERJ_LANG') {
        $saved[$variable] = [Environment]::GetEnvironmentVariable($variable)
    }
    try {
        & $Body
    } finally {
        foreach ($variable in $saved.Keys) {
            [Environment]::SetEnvironmentVariable($variable, $saved[$variable])
        }
        Remove-Item -LiteralPath $WORK -Recurse -Force -ErrorAction SilentlyContinue
    }
    Write-Output "${Name}: OK"
    exit 0
}
