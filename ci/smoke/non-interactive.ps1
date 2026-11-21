# -c, a script file, the standard input, and the options of the launcher (non-interactive.sh).
. "$PSScriptRoot/common.ps1"

smoke non-interactive {
    & $PJ -c '^cmd /c exit 3'
    if ($LASTEXITCODE -ne 3) { throw "expected exit code 3, got $LASTEXITCODE" }

    'where { $_.endsWith(".txt") }' | Set-Content -LiteralPath "$WORK\filter.pj"
    $lines = 'x.txt', 'y.log', 'z.txt' | & $PJ "$WORK\filter.pj"
    $lines
    if (($lines -join ',') -ne 'x.txt,z.txt') { throw "standard input filtering is wrong: $lines" }

    # An unknown option of the launcher: exit code 2 and the usage (FR-04).
    $unknown = & $PJ --foo 2>&1 | Out-String
    $code = $LASTEXITCODE
    if ($code -ne 2) { throw "an unknown option should give 2, got $code" }
    if (-not ($unknown.Contains('unknown option: --foo') -and $unknown.Contains('usage: powerj'))) {
        throw "the unknown option is not reported: $unknown"
    }

    # --version and --help answer without starting the shell.
    $version = & $PJ --version
    if (-not ($version -match '^PowerJ .* \(Java ')) { throw "--version is wrong: $version" }
    $help = (& $PJ --help) -join ' '
    if (-not ($help -match 'usage: powerj' -and $help -match '--cd' -and $help -match '--version' -and $help -match '--help')) {
        throw "--help is wrong: $help"
    }
}
