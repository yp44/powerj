# The session of a user, through the standard input of powerj.exe (the session examples of FR-55): the exit code of the
# last native command is the exit code of the shell. The counterpart of launcher.sh: change both when the session changes.
. "$PSScriptRoot/common.ps1"

smoke launcher {
    @'
cd C:\Windows
pwd
$l = ^cmd /c "echo un& echo deux"
"line: $l[1]"
^cmd /c "exit 1" || "fallback"
which ls
(ls C:\Windows --dirs)*.name
$u = (ls C:\ | where name == Windows).name
"filter: $u"
$t = ^cmd /c "echo b& echo a& echo c" | ^sort | where { $_ != "c" }
"sort: $t"
"java: $(Math.max(3, 7))"
$n = List.of("a", "bb", "ccc").stream().filter(s -> s.length() > 1).count()
"lambda: $n"
$r = List.of("b", "a") | map String::toUpperCase | where { s -> s != "B" }
"reference: $r"
$c = (ls C:\ | where name == Windows | collect).size()
"collect: $c"
^cmd /c "exit 3"
exit $exit
'@ | Set-Content -LiteralPath "$WORK\smoke.txt"
    # Start-Process rather than `cmd /c "… < smoke.txt"`: no quoting rule of cmd to get right for a path with spaces.
    $run = Start-Process -FilePath $PJ -RedirectStandardInput "$WORK\smoke.txt" -RedirectStandardOutput "$WORK\out.txt" `
        -RedirectStandardError "$WORK\err.txt" -NoNewWindow -Wait -PassThru
    Get-Content -LiteralPath "$WORK\out.txt", "$WORK\err.txt" | Set-Content -LiteralPath "$WORK\all.txt"
    Get-Content -LiteralPath "$WORK\all.txt"
    if ($run.ExitCode -ne 3) { throw "expected exit code 3, got $($run.ExitCode)" }
    expect "$WORK\all.txt" 'C:\Windows' 'line: deux' 'fallback' 'cmdlet (io.powerj.cmdlets)' 'System32' 'filter: Windows' `
        'sort: [a, b]' 'java: 7' 'lambda: 2' 'reference: A' 'collect: 1'

    # A start-up directory that does not exist: an error naming it, exit code 1, and no shell started (FR-04d).
    $cd = & $PJ --cd "$WORK\absent" -c 'pwd' 2>&1 | Out-String
    $code = $LASTEXITCODE
    $cd
    if ($code -ne 1) { throw "expected exit code 1 for a missing --cd directory, got $code" }
    if (-not ($cd.Contains('--cd') -and $cd.Contains('absent'))) { throw "the error does not name --cd and the folder: $cd" }
}
