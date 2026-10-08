# Recette — Étape 6 : autocomplétion Tab et coloration

**Objectif :** vérifier que Tab complète les commandes, options, chemins, variables, propriétés et l'API Java, et que la saisie est colorée selon la nature des mots.

## Récupérer les livrables

Comme aux étapes précédentes : onglet **Actions**, dernière exécution du workflow **CI**, artefact `powerj-windows-x64-installer` ou `powerj-windows-x64-portable`.

## Comment fonctionne Tab

- **Premier Tab** : complète le préfixe commun à toutes les propositions ; s'il n'y en a qu'une, la complète entièrement.
- **Second Tab** : affiche le menu des propositions avec leur description ; Tab / Maj+Tab (ou les flèches) pour s'y déplacer, Entrée pour choisir.
- Le menu indique la nature : `ls [pj]` (cmdlet), `cd [interne]`, `notepad [natif]` ; la signature pour Java : `of(Object...) : List`.

## Scénario

| # | Action | Résultat attendu |
|---|---|---|
| 1 | `l` puis Tab Tab | Menu : `ls [pj]` et les programmes du `PATH` commençant par `l`. |
| 2 | `ls --` puis Tab Tab ; puis `ls -r --` puis Tab Tab | Options de `ls` avec leur description (`--recurse, -r  Parcourt les sous-dossiers`…) ; la seconde fois, `--recurse` n'est plus proposé. |
| 3 | `cd C:\Pro` puis Tab | Complète en `"C:\\Program Files\\` (entre guillemets, à cause de l'espace) ; un nouveau Tab propose le contenu du dossier. |
| 4 | `ls C:\Win` puis Tab, puis `Sys` Tab | `C:\Windows\`, puis `C:\Windows\System32\`. |
| 5 | `ls \| where { $_.` puis Tab Tab | `name size modified path dir ext` (propriétés de `FileEntry`, sortie de `ls`) et les méthodes. |
| 6 | `ls \| where { f -> f.na` puis Tab, puis `.sta` Tab | `f.name`, puis `startsWith(` : la lambda connaît le type de `f`. |
| 7 | `$f = ls` puis `$f[0].` Tab Tab | Propriétés du premier fichier. Puis `$f*.na` Tab → `$f*.name`. |
| 8 | `$` puis Tab Tab | Variables définies (`$f`…) et automatiques (`$exit`, `$last`, `$pwd`…), avec leur type. |
| 9 | `^no` puis Tab | `notepad` (seulement les programmes natifs après `^`). |
| 10 | `java.util.Li` Tab Tab | `List`, `LinkedList`, `LinkedHashMap`… |
| 11 | `List.` Tab Tab | Méthodes statiques avec leur signature : `of(Object...) : List`, `copyOf(Collection) : List`… |
| 12 | `$l = List.of(1, 2)` puis `$l.` Tab Tab, puis `$l.stream().fi` Tab | `size() : int`, `get(int) : Object`, `stream() : Stream`… ; puis `filter(`. |
| 13 | `new java.io.F` Tab Tab | `File`, `FileReader`, `FileWriter`… |
| 14 | `import java.sec` Tab, puis `Mess` Tab | `java.security.`, puis `java.security.MessageDigest`. |
| 15 | `ls \| map String::len` Tab | `String::length`. |
| 16 | `Ma` Tab Tab | `Math`, `Map`, `MatchResult`… (classes importées par défaut). |
| 17 | Couleurs : taper lentement `ls -r \| nope "x" $y ; git status` | `ls` vert, `-r` gris, `nope` rouge (commande inconnue), `"x"` jaune, `$y` magenta, `git` cyan (programme natif). |
| 18 | Taper `Math.max(1, 2)` | Aucune couleur rouge : c'est une expression Java, pas une commande inconnue. |
| 19 | Taper `cd ..` puis Entrée | `cd` en vert (commande interne). |

## Limites connues de l'étape 6

- Pas de complétion à l'intérieur d'une chaîne `"…"` (sauf un chemin entre guillemets en argument), ni dans un `$( … )` placé dans une chaîne.
- Pas encore d'annotation `@Completion` pour qu'un cmdlet fournisse ses propres propositions (FR-23) : les options de type enum et chemin sont complétées automatiquement.
- Le type des paramètres d'une lambda passée à une méthode Java (`$l.stream().filter(s -> s.`) n'est pas déduit : pas de proposition après `s.`.
- Le tout premier Tab peut prendre quelques dizaines de millisecondes (lecture de l'index des classes Java) ; ensuite moins d'une milliseconde.
