# Recette — Étape 4 : pipeline et `where`

**Objectif :** vérifier le pipeline `|` (cmdlets et commandes natives mélangés), les blocs `{ }` à la syntaxe Java, le cmdlet `where`, la redirection `2>&1`, Ctrl+C sur un pipeline et le mode non interactif (`powerj -c`, fichier `.pj`).

## Récupérer les livrables

Comme aux étapes précédentes : onglet **Actions**, dernière exécution du workflow **CI**, artefact `powerj-windows-x64-installer` ou `powerj-windows-x64-portable`.

## Scénario

| # | Action | Résultat attendu |
|---|---|---|
| 1 | `cd ~` puis `ls -r \| where { $_.size > 1mb }` | Les fichiers de plus de 1 Mo de l'arborescence, en tableau, affichés au fil de l'eau. |
| 2 | `ls \| where { $_.name.endsWith(".txt") && !$_.dir }` | Les fichiers `.txt` du dossier (méthodes Java sur les propriétés, opérateurs `&&` et `!`). |
| 3 | `ls \| where size > 10kb`, puis `ls \| where ext == txt`, puis `ls \| where dir == true` | Forme courte, équivalente à `where { $_.size > 10kb }` ; le `>` est ici l'opérateur, pas une redirection. |
| 4 | Dans un dépôt git : `git status --porcelain \| where { $_.startsWith(" M ") }` | Les lignes des fichiers modifiés seulement (la sortie d'une commande native devient des lignes `String`). |
| 5 | `ipconfig \| where { $_.contains("IPv4") }` | Les lignes contenant `IPv4`, accents corrects. |
| 6 | `ls -r C:\Windows\System32 \| ^more` puis Espace, puis `q` | Le tableau défile page par page ; `q` rend la main immédiatement (le `ls` amont s'arrête). |
| 7 | `ls -r C:\ \| where { $_.ext == "log" }` puis Ctrl+C | Arrêt immédiat, `^C` en rouge, nouveau prompt ; le shell reste utilisable. |
| 8 | `git commandeinconnue 2> err.txt` puis `^cmd /c type err.txt` | `err.txt` contient le message d'erreur de git. |
| 9 | `env \| where { $_.name.startsWith("JAVA") }` | Les variables dont le nom commence par `JAVA`. |
| 10 | `^cmd /c "echo dehors& echo erreur 1>&2" 2>&1 \| where { $_.contains("erreur") }` | `erreur` : avec `2>&1`, le flux d'erreur rejoint la sortie et passe dans le pipeline. |
| 11 | `^cmd /c "echo b& echo a" \| ^sort` | `a` puis `b` : natif → natif, octets transmis directement. |
| 12 | `$gros = ls -r \| where size > 1mb` puis `$gros.name` puis `(ls \| where { $_.dir }).name` | Le résultat d'un pipeline s'affecte et s'utilise entre parenthèses. |
| 13 | `ls \| where { $_.size > now }` | Pour chaque objet, erreur non bloquante en rouge (`where : « > » impossible entre Long … et Instant …`), puis prompt ; ajouter `--on-error silent` les masque, `--on-error stop` arrête au premier. |
| 14 | `ls \| where { $_.size = 3 }` | `syntaxe : « = » dans un bloc : pour comparer, utiliser ==` : rien n'est exécuté. |
| 15 | `ls \| ls` | `« ls » ne lit pas les objets du pipeline`. |
| 16 | `ls \| where { $_.modified > now - 7d }` | Les fichiers modifiés depuis moins de 7 jours (`now`, durées `7d`, `2h`, `30m`…). |
| 17 | Depuis `cmd.exe`, dans le dossier de `powerj.exe` : `powerj -c "ls C:\Windows \| where { $_.size > 1mb }"` | Le tableau, puis retour à `cmd.exe`. |
| 18 | `powerj -c "^cmd /c exit 3"` puis `echo %ERRORLEVEL%` | `3`. |
| 19 | `dir /b C:\Windows \| powerj -c "where { $_.endsWith(\".exe\") }"` | Les `.exe` de `C:\Windows` : les lignes de l'entrée standard alimentent `where`. |
| 20 | Créer `test.pj` contenant les deux lignes `ls C:\Windows \| where dir == true` et `exit 5`, puis `powerj test.pj` et `echo %ERRORLEVEL%` | Les dossiers de `C:\Windows`, puis `5`. |
| 21 | `powerj -c "commandeinconnue"` puis `echo %ERRORLEVEL%` | `commande inconnue : commandeinconnue`, puis `1`. |

Variante Linux / macOS (archive `powerj-linux-x64`) : remplacer `ipconfig` par `ip addr`, `^cmd /c …` par `sh -c "…"`, `dir /b` par `ls -1`, et lancer `bin/powerj`.

## Points de syntaxe à connaître

- Dans un bloc `{ }`, `$_` est l'objet courant et la syntaxe est celle de Java : `==` compare les valeurs, les chaînes sont sensibles à la casse (`$_.toLowerCase().contains("readme")` pour l'inverse), et les besoins courants passent par les méthodes Java (`contains`, `startsWith`, `matches`…).
- Hors bloc, une chaîne entre guillemets interpole les variables : `"$_.size"` est évalué avant l'appel. Écrire la condition dans un bloc `{ … }`.
- La forme courte de `where` accepte `==`, `!=`, `<`, `<=`, `>`, `>=` ; la valeur est un nombre, une taille, une durée, `true`, `false`, `null` ou un texte (sans guillemets s'il ne contient pas d'espace).
- En mode non interactif, les lignes reçues sur l'entrée standard sont décodées comme la sortie des commandes natives (`POWERJ_NATIVE_ENCODING`, ou `POWERJ_NATIVE_ENCODING_STDIN` pour un réglage propre à l'entrée).

## Limites connues de l'étape 4

- Pas encore d'appels de classes Java (`List.of(…)`, `Math.max(…)`, `new …`) : seulement les méthodes des objets (`$_.contains(…)`). Étape 5.
- Pas encore de `$(expr)` dans les chaînes, ni de blocs de texte `"""…"""`.
- Le code retour d'un pipeline est celui de sa dernière étape (une erreur bloquante dans une étape amont le rend aussi en échec).
- Pas encore d'autocomplétion Tab (étape 6).
