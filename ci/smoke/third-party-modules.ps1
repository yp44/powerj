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
    $samples = & $PJ -c 'mod-load examples/samples/target/samples.jar; $l = java.util.Arrays.asList(1, null, 2); $l | passthrough | count; $l | passthrough -n | map { x -> x * 10 }; $l | passthrough -o; $l | passthrough -o | count'
    $samples
    if (($samples -join ',') -ne 'passthrough,3,10,20,Optional[1],Optional.empty,Optional[2],2') { throw "passthrough is wrong: $samples" }
}
