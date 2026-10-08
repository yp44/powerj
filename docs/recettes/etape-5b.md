# Recette — Étape 5b : alignement sur Java

**Objectif :** vérifier que les blocs s'écrivent comme en Java — lambdas nommées, lambdas sans accolades dans les appels Java, références de méthode —, que `$_` reste disponible pour les filtres courts, que les conditions sont strictement booléennes, et le nouveau cmdlet `map`.

> L'étape 5b est livrée avec l'étape 5, dans le même exe : les deux recettes se passent sur la même version.

## Récupérer les livrables

Comme aux étapes précédentes : onglet **Actions**, dernière exécution du workflow **CI**, artefact `powerj-windows-x64-installer` ou `powerj-windows-x64-portable`.

## `$_` ou lambda : laquelle utiliser ?

Les deux formes font la même chose ; elles se lisent différemment.

| Je veux… | J'écris | Pourquoi |
|---|---|---|
| un filtre ou une transformation **d'une ligne**, tapé au clavier | `where { $_.dir }`, `map { $_.name }` | `$_` est le plus court ; sans ambiguïté quand l'objet n'apparaît qu'une ou deux fois. |
| la propriété de **chaque élément** d'une liste | `$f*.name`, `$f*.size` | `.` s'applique à la liste elle-même (`$f.size()` = nombre d'éléments) ; `*.` à chaque élément. |
| une condition **longue**, qui cite l'objet plusieurs fois | `where { f -> f.size > 1mb && !f.dir && f.ext == "log" }` | Un nom (`f`, `fichier`, `ligne`) se relit mieux qu'une suite de `$_`. |
| un bloc **dans** un autre bloc | `where { f -> List.of("md", "txt").stream().anyMatch(e -> f.name.endsWith("." + e)) }` | Dans le bloc intérieur, `$_` désignerait `e` : l'objet extérieur doit avoir un nom. |
| **deux paramètres** (tri, réduction) | `$m.sort((a, b) -> a.length() - b.length())` | Seule une lambda déclare plusieurs paramètres (`$a` / `$b` n'existent plus). |
| juste **appeler une méthode** | `map FileEntry::name`, `map(String::toUpperCase)` | La référence de méthode dit tout, sans paramètre à nommer. |
| passer une fonction à une **méthode Java** | `$l.stream().filter(s -> s.length() > 4)` | Écriture Java exacte ; les accolades sont facultatives entre les parenthèses d'un appel. |
| passer une fonction à un **cmdlet** | `where { f -> … }`, `map { f -> … }` | Accolades obligatoires : sans elles, le `>` de `->` serait une redirection. |

En résumé : **`$_` pour les filtres courts tapés au clavier ; une lambda nommée dès que l'expression grandit, s'imbrique ou prend deux paramètres ; une référence de méthode quand elle suffit.**

Rappels :
- Les paramètres de lambda s'écrivent **sans `$`** (comme en Java) ; les variables du shell gardent leur `$` : `$min = 1kb; ls | where { f -> f.size > $min }`.
- Une condition doit renvoyer un **booléen** (`true` / `false`), comme un `Predicate` Java : `where { f -> f.name }` est une erreur ; écrire `where { f -> !f.name.isEmpty() }`.

## Scénario

| # | Action | Résultat attendu |
|---|---|---|
| 1 | `ls -r \| where { $_.size > 1mb }` puis `ls -r \| where { f -> f.size > 1mb }` | Même résultat : `$_` et lambda nommée sont équivalents. |
| 2 | `ls -r \| where { f -> f.size > 1mb && !f.dir && f.modified > now - 7d }` | Fichiers de plus de 1 Mo modifiés depuis 7 jours. |
| 3 | `ls -r \| map { f -> f.name + " : " + f.name.length() }` | Une ligne par fichier (`notes.txt : 9`) ; fonctionne aussi quand un seul fichier est trouvé. |
| 4 | `ls \| map FileEntry::name` puis `env \| map EnvVar::name` | Noms des fichiers ; noms des variables d'environnement. |
| 5 | `ls -r \| where { f -> List.of("md", "txt").stream().anyMatch(e -> f.name.endsWith("." + e)) }` | Fichiers `.md` et `.txt` (lambda imbriquée : `f` reste l'objet extérieur). |
| 6 | `$l = List.of("apple", "banana", "kiwi")` puis `$l.stream().filter(s -> s.length() > 4).map(String::toUpperCase).toList()` | `APPLE` puis `BANANA`. |
| 7 | `$m = new ArrayList($l); $m.sort((a, b) -> a.length() - b.length()); $m` | `kiwi`, `apple`, `banana`. |
| 8 | `$l.stream().map(Path::of).toList()` puis `Stream.of("a", "b").map(StringBuilder::new).toList()` | Références de méthode statique et de constructeur. |
| 9 | `ls \| where { f -> f.name }` | Pour chaque objet, erreur non bloquante `le bloc doit renvoyer un booléen` ; le shell continue. |
| 10 | `$m.sort({ $a.length() - $b.length() })` | Erreur claire indiquant d'écrire `(a, b) -> …`. |
| 11 | `$min = 1kb; ls \| where { f -> f.size > $min }` | Variable du shell utilisée dans une lambda. |
| 12 | `ls \| where f -> f.dir` | Erreur de syntaxe expliquant que les accolades sont obligatoires en argument d'un cmdlet. |
| 13 | `$f = ls -r` puis `$f.size()`, `$f*.size`, `$f*.name*.toUpperCase()`, `$f*.name.size()` | Nombre de fichiers ; taille de chaque fichier ; noms en majuscules ; nombre de noms. `.` s'applique à la liste, `*.` à chaque élément. |
| 14 | `$f.size` | Erreur : `… n'a pas de propriété 'size' (pour chaque élément : *.size ; méthode : size())`. |
| 15 | Saisir `$t = """` puis deux lignes de texte puis `"""`, puis `$t.lines().count()` | `2` : bloc de texte multi-ligne. |
