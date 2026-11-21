# greet.jar loaded from the modules folder of POWERJ_HOME, its messages in English and French; samples.jar loaded with
# mod-load (third-party-modules.sh).
. "$PSScriptRoot/common.ps1"

smoke third-party-modules {
    $env:POWERJ_HOME = "$WORK\pjhome"
    New-Item -ItemType Directory -Force "$env:POWERJ_HOME\modules" | Out-Null
    Copy-Item examples\greet\target\greet.jar "$env:POWERJ_HOME\modules\"
    $lines = & $PJ -c 'greet -n Yves -c 2 | map { g -> g.message }'
    $lines
    if (($lines -join ',') -ne 'Hello Yves!,Hello Yves!') { throw "greet is wrong: $lines" }
    $env:POWERJ_LANG = 'fr'
    $fr = & $PJ -c 'greet -n Yves'
    $fr
    if (-not ($fr -match 'Bonjour Yves !')) { throw "greet in French is wrong: $fr" }
    $fr = & $PJ -c 'nope' 2>&1 | Out-String
    $fr
    if (-not ($fr -match 'commande inconnue : nope')) { throw "French message missing: $fr" }
    Remove-Item Env:POWERJ_LANG
    $modules = & $PJ -c 'mod-list'
    $modules
    if (-not ($modules -match 'com.example.greet')) { throw 'mod-list does not list com.example.greet' }
    # The whole output, stderr included (2>&1): an option that did nothing, or an error, changes it.
    $samples = & $PJ -c 'mod-load examples/samples/target/samples.jar; $l = java.util.Arrays.asList(1, null, 2); $l | passthrough | count; $l | passthrough -s | count; $l | passthrough -o; $l | passthrough -o | count; $l | passthrough -so' 2>&1
    $samples
    if (($samples -join ',') -ne 'passthrough,echo-options,3,2,Optional[1],Optional.empty,Optional[2],2,Optional[1],Optional[2]') { throw "passthrough is wrong: $samples" }
    $echo = & $PJ -c 'mod-load examples/samples/target/samples.jar; echo-options -t hi -c 3 --number 7 --size 10kb --level high -v --timeout 1.5s --tag a --tag b --block { 1 + 2 } one two | map { o -> java.util.Arrays.asList(o.text, o.count, o.number, o.size, o.level, o.verbose, o.timeout, o.tags, o.block, o.blockValue, o.words).toString() }; echo-options | map { o -> java.util.Arrays.asList(o.count, o.number, o.verbose, o.tags, o.words, o.block, o.blockValue).toString() }; echo-options --block (1 + 2) | map { o -> java.util.Arrays.asList(o.block, o.blockValue).toString() }; echo-options --block { $_ == null } | map { o -> o.blockValue }' 2>&1
    $echo
    if (($echo -join ',') -ne 'passthrough,echo-options,[hi, 3, 7, 10240, HIGH, true, PT1.5S, [a, b], { 1 + 2 }, 3, [one, two]],[0, null, false, [], [], null, null],[3, 3],true') { throw "echo-options is wrong: $echo" }
    $errors = & $PJ -c 'mod-load examples/samples/target/samples.jar; echo-options --block { 1 / 0 } | count; echo-options --count abc' 2>&1 | Out-String
    $errors
    if (-not ($errors -match 'echo-options: --block: calculation failed: division by zero') -or
        -not ($errors -match 'echo-options: invalid value for --count')) { throw "echo-options errors are wrong: $errors" }
}
