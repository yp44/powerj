#!/usr/bin/env bash
# English and French messages: the environment variable, then config.properties.
. "$(dirname "$0")/common.sh"

export POWERJ_HOME="$WORK/pjhome"
mkdir -p "$POWERJ_HOME/modules"
cp examples/greet/target/greet.jar "$POWERJ_HOME/modules/"
POWERJ_LANG=en "$PJ" -c 'nope' > "$WORK/lang.txt" 2>&1 || true
POWERJ_LANG=fr "$PJ" -c 'nope' >> "$WORK/lang.txt" 2>&1 || true
POWERJ_LANG=fr "$PJ" -c 'help ls' >> "$WORK/lang.txt" 2>&1
POWERJ_LANG=fr "$PJ" -c 'greet -n Yves' >> "$WORK/lang.txt" 2>&1
printf 'language=fr\n' > "$POWERJ_HOME/config.properties"
"$PJ" -c '1 +' >> "$WORK/lang.txt" 2>&1 || true
rm "$POWERJ_HOME/config.properties"
cat "$WORK/lang.txt"
expect "$WORK/lang.txt" 'unknown command: nope' 'commande inconnue : nope' 'ls — Liste les fichiers et dossiers' \
    'Bonjour Yves !' 'syntaxe : expression incomplète'
echo "languages: OK"
