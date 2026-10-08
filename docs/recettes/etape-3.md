# Recette — Étape 3 : modèle objet, `ls`, `env`, `help`

**Objectif :** vérifier que les cmdlets renvoient des objets Java dont on lit les propriétés, l'affichage en tableau, la liaison des options façon Unix, `env` et l'aide.

## Récupérer les livrables

Comme aux étapes précédentes : onglet **Actions**, dernière exécution du workflow **CI**, artefact `powerj-windows-x64-installer` ou `powerj-windows-x64-portable`.

## Scénario

| # | Action | Résultat attendu |
|---|---|---|
| 1 | `cd ~` puis `ls` | Tableau `name size modified dir` : dossiers d'abord, puis fichiers, par ordre alphabétique ; tailles lisibles (`14,2 KB`), dates locales. |
| 2 | `ls -a` | Les fichiers cachés apparaissent en plus. |
| 3 | `ls -r --filter *.txt` (dans un dossier contenant des `.txt`) | Tous les `.txt` des sous-dossiers ; le motif ignore la casse (`*.TXT` aussi). |
| 4 | `ls -d`, puis `ls --files`, puis `ls C:\Windows *.ini` | Dossiers seuls ; fichiers seuls ; contenu de `C:\Windows` suivi des `.ini` du dossier courant. |
| 5 | `(ls)*.name` | Les noms seuls, un par ligne (`*.` : la propriété de chaque élément). |
| 6 | `$f = ls --files` puis `$f[0].size`, `$f[-1].name`, `$f[0].path.parent`, `$f[0].ext` | Taille en octets, nom du dernier, dossier parent, extension sans le point. |
| 7 | `$f*.name`, puis `$f.size()` et `$f.name` | Les noms de tous les fichiers ; le nombre de fichiers ; une erreur indiquant d'écrire `*.name` (sans `*`, `.` s'applique à la liste elle-même). |
| 8 | `$f[0].siz` | Erreur en rouge : `FileEntry n'a pas de propriété 'siz' (propriétés : name, size, …)`. |
| 9 | `ls --recurce` | `ls : option inconnue --recurce, vouliez-vous dire --recurse ?` |
| 10 | `ls --rec`, `ls -ra`, `ls --filter=*.txt`, `ls -f*.txt` | Abréviation, options groupées, `=` et valeur collée : tous acceptés. |
| 11 | `ls C:\nulle\part; "suite"` | `ls : introuvable : C:\nulle\part` en rouge, puis `suite`. |
| 12 | `ls C:\nulle\part --on-error silent` | Aucun message ; `$?` vaut `false`. |
| 13 | `help` | Commandes internes, puis cmdlets par catégorie (`Fichiers` : `ls`, `Système` : `env`). |
| 14 | `help ls` et `ls --help` | Synopsis, options avec descriptions, type de sortie `FileEntry (name, size, modified, path, dir, ext)`, exemples. |
| 15 | `help members $f[0]` | Propriétés (`name`, `size`…) et méthodes du `FileEntry`, avec leurs types. |
| 16 | `which ls env ^ls cd` | `ls → cmdlet (io.powerj.cmdlets)`, `env → cmdlet (…)`, `^ls → natif …` (si un `ls.exe` existe, ex. Git Bash), `cd → commande interne`. |
| 17 | `env` puis `env PATH` puis `(env PATH).value` | Toutes les variables ; la ligne `PATH` ; la valeur seule. |
| 18 | `env --set MAVEN_OPTS=-Xmx2g` puis `env MAVEN_OPTS` puis `env --unset MAVEN_OPTS` | Variable créée, affichée, supprimée (pour cette session seulement). |
| 19 | `env --append PATH C:\outils` puis `which monoutil` (outil placé dans `C:\outils`) | L'outil devient exécutable dans la session et `which` le trouve. |
| 20 | `env --set POWERJ_NATIVE_ENCODING=windows-1252`, puis `$l = ipconfig` et `$l` ; `env --unset POWERJ_NATIVE_ENCODING` | Accents d'`ipconfig` faux (réglage pris en compte immédiatement), puis rétablis. |
| 21 | `ls > liste.txt` puis `^cmd /c type liste.txt` | Le tableau est écrit dans le fichier, sans troncature. |
| 22 | Réduire la largeur de la fenêtre, puis `ls` dans un dossier aux noms longs | Les colonnes se réduisent et les textes trop longs finissent par `…`. |

## Limites connues de l'étape 3

- Pas encore de pipeline `|` ni de `where` (étape 4).
- Pas encore d'appels de méthodes Java (`$f[0].path.toFile()`) : seulement les propriétés (étape 5).
- Pas encore d'autocomplétion Tab (étape 6).
