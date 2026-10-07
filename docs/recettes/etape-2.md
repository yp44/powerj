# Recette — Étape 2 : commandes natives

**Objectif :** lancer les programmes du système depuis PowerJ, naviguer dans les dossiers, enchaîner des commandes, utiliser les variables et les redirections.

## Récupérer les livrables

Comme aux étapes précédentes : onglet **Actions**, dernière exécution du workflow **CI**, artefact `powerj-windows-x64-installer` ou `powerj-windows-x64-portable`.

## Scénario

| # | Action | Résultat attendu |
|---|---|---|
| 1 | `git --version` (si git est installé) ou `ipconfig` | La sortie du programme s'affiche normalement. |
| 2 | `git log` dans un dépôt git | Couleurs et pagination de git fonctionnent ; `q` pour quitter la pagination. |
| 3 | `pwd`, puis `cd C:\Windows`, puis `pwd` | Le prompt devient `PJ C:\Windows> ` ; `pwd` affiche `C:\Windows`. |
| 4 | `cd ..`, `cd -`, `cd ~`, `cd` | Dossier parent ; retour au dossier précédent ; dossier utilisateur (deux fois). |
| 5 | `cd D:` (si un lecteur D: existe) | Le prompt passe sur `D:\`. |
| 6 | `cd "C:\\Program Files"` | Entre guillemets, l'antislash se double (échappement Java) ; sans guillemets il se tape normalement (`cd C:\Users`). |
| 7 | `cd C:\nulle\part` | `cd : dossier introuvable : C:\nulle\part` en rouge. |
| 8 | `$l = ipconfig` puis `$l[0]`, `$l[-1]` | Première et dernière ligne de la sortie d'`ipconfig`. Les accents sont corrects (page de code de la console). |
| 9 | `^cmd /c "exit 3"` puis `$exit` | Affiche `3`. `$last` affiche les détails (commande, pid, code retour, durée) ; `$last.duration` la durée seule. |
| 10 | `^cmd /c "exit 1" \|\| "échec"` puis `git --version && "ok"` | Affiche `échec`, puis la version de git suivie de `ok`. `^cmd /c "exit 1" && "jamais"` n'affiche rien. |
| 11 | `commandeinexistante; "suite"` | Message `commande inconnue : commandeinexistante` en rouge, puis `suite`. |
| 12 | `git commandeinconnue` | Le message d'erreur de git s'affiche ; `$?` vaut `false`. |
| 13 | `git log -n 3 > log.txt`, puis `^cmd /c type log.txt` | Le fichier `log.txt` du dossier courant contient la sortie. `>>` ajoute à la fin. |
| 14 | `git commandeinconnue 2> err.txt` | Rien ne s'affiche ; `err.txt` contient le message d'erreur. |
| 15 | `$l = git commandeinconnue` | Le message d'erreur s'affiche en rouge (il n'est jamais mis dans la variable). |
| 16 | `notepad` | Le Bloc-notes s'ouvre et le prompt revient immédiatement ; `$last.pid` donne son pid, `$exit` est vide. |
| 17 | `ping -t localhost`, puis Ctrl+C | `ping` s'arrête, `^C` s'affiche, le shell reste ouvert ; `$?` vaut `false`. |
| 18 | `which git cd notepad` | `git → natif C:\…\git.exe`, `cd → commande interne`, `notepad → natif C:\Windows\…`. |
| 19 | `$nom = "Yves"` puis `"Bonjour $nom"` | Affiche `Bonjour Yves`. |
| 20 | Encodage forcé : définir la variable d'environnement Windows `POWERJ_NATIVE_ENCODING=windows-1252`, relancer PowerJ, puis `$l = ipconfig` et `$l`. | Les accents d'`ipconfig` sont désormais faux (encodage volontairement inadapté) : preuve que le réglage est pris en compte. Supprimer la variable pour revenir au réglage automatique. `POWERJ_NATIVE_ENCODING_GIT=UTF-8` ne concernerait que git. |
| 21 | `exit 7`, puis dans `cmd.exe` : `echo %ERRORLEVEL%` | Affiche `7`. |

## Limites connues de l'étape 2

- Pas encore de pipeline `|` (étape 4) ni de redirection `2>&1`.
- Les commandes internes de `cmd.exe` (`dir`, `echo`, `type`…) ne sont pas des programmes : les lancer via `^cmd /c dir`.
- `env` (variables d'environnement de la session) arrive à l'étape 3 ; d'ici là, l'encodage se règle par une variable d'environnement Windows.
- `true` et `false` sont des valeurs (comme en Java), pas des commandes.
