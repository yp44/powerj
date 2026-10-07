# Recette — Étape 1 : édition de ligne et historique

**Objectif :** vérifier l'édition de ligne (JLine), l'historique persistant, ↑/↓, Ctrl+R, Ctrl+C, Ctrl+D et la saisie multi-ligne.

## Récupérer les livrables

Comme pour l'étape 0 : onglet **Actions** du dépôt, dernière exécution du workflow **CI**, artefacts `powerj-windows-x64-installer` (installeur) ou `powerj-windows-x64-portable` (zip).

Pour repartir d'un historique vide, supprimer le fichier `%USERPROFILE%\.powerj\history` avant de commencer.

## Scénario

| # | Action | Résultat attendu |
|---|---|---|
| 1 | Lancer PowerJ. Taper `bonjour`, `test un`, `test deux` (Entrée après chacun). | `commande inconnue : …` après chaque ligne. |
| 2 | Appuyer trois fois sur ↑. | Les lignes reviennent dans l'ordre inverse : `test deux`, `test un`, `bonjour`. ↓ fait le chemin inverse. |
| 3 | Effacer la ligne, taper `te` puis ↑. | Seules les lignes commençant par `te` sont proposées : `test deux`, puis `test un`. |
| 4 | Ligne vide, Ctrl+R puis taper `un`. | `(reverse-i-search)` propose `test un` ; Entrée l'exécute. Ctrl+G annule la recherche. |
| 5 | Taper `abc` puis Ctrl+C. | La ligne est effacée, un nouveau prompt s'affiche, le shell ne se ferme pas. |
| 6 | Taper `history`. | Liste numérotée : `1  bonjour`, `2  test un`… (`abc` n'y figure pas). |
| 7 | Taper `!!`, puis `!1`, puis `!te`. | Ré-exécutent respectivement la dernière commande, l'entrée n°1 et la dernière commençant par `te`. |
| 8 | Taper `!zzz`. | `historique : aucune commande ne correspond à !zzz`. |
| 9 | Taper ` secret` (avec un espace au début), puis `history`. | `secret` n'apparaît pas dans l'historique. |
| 10 | Taper `ls |` puis Entrée. | Prompt de continuation `>> ` ; taper `where { $_.dir` puis Entrée : encore `>> ` ; taper `}` : la commande complète est exécutée (`commande inconnue : ls`). |
| 11 | Taper `cd C:\` puis Entrée. | Pas de continuation (l'antislash final ne prolonge pas la ligne) : `commande inconnue : cd`. |
| 12 | Édition : taper un texte, utiliser ←/→, Début/Fin, Ctrl+←/→ (mot par mot), Ctrl+W, Ctrl+K, Ctrl+U. | Comportement Emacs habituel. |
| 13 | Sur une ligne non vide, Ctrl+D. | Supprime le caractère sous le curseur. |
| 14 | Sur une ligne vide, Ctrl+D. | Le shell se ferme. |
| 15 | Relancer PowerJ, appuyer sur ↑. | L'historique de la session précédente est retrouvé. |
| 16 | `history --clear`, puis ↑. | Plus rien dans l'historique. |
| 17 | Configuration : créer `%USERPROFILE%\.powerj\config.properties` contenant `history.size=3`, relancer, taper 5 commandes, `history`. | Seules les 3 dernières entrées sont conservées. |

## Limites connues de l'étape 1

- Pas encore de coloration syntaxique ni d'autocomplétion Tab (étape 6).
- Les commandes autres que `exit` et `history` restent « inconnues » : commandes natives à l'étape 2.
- Le journal de diagnostic est dans `%USERPROFILE%\.powerj\logs\`.
