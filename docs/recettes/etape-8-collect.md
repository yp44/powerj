# Recette — Mini-itération 8 : cmdlet `collect`

**Objectif :** vérifier que `collect` rassemble les objets d'un pipeline en **une seule liste**, toujours une liste (même d'un seul élément ou vide), et que l'étape suivante reçoit cette liste entière.

## Récupérer les livrables

Comme aux étapes précédentes : onglet **Actions**, dernière exécution du workflow **CI**, artefact `powerj-windows-x64-installer` ou `powerj-windows-x64-portable`.

## Quand utiliser `collect`

Après un `|`, chaque commande reçoit les objets **un par un** (`where`, `map`). Pour travailler sur **la liste entière** (compter, trier, `stream()`…) :

| Besoin | Écriture |
|---|---|
| Nombre de résultats, même 0 ou 1 | `(ls -r \| where { f -> !f.dir } \| collect).size()` |
| Garder la liste dans une variable | `$l = ls -r \| collect` puis `$l.stream()…` |
| Continuer le pipeline avec la liste | `ls -r \| collect \| map { l -> … }` |

Sans `collect`, `( … )` et `$l = …` donnent l'objet **seul** quand il n'y a qu'un résultat : `(ls C:\ | where name == Windows).size()` renvoie la propriété `size` du dossier (`0`), pas le nombre de résultats.

## Scénario

| # | Action | Résultat attendu |
|---|---|---|
| 1 | `(ls C:\ \| where name == Windows \| collect).size()` | `1` (comparer avec la même commande sans `\| collect` : `0`, la taille du dossier). |
| 2 | `(ls C:\ \| where name == absent \| collect).size()` | `0`. |
| 3 | `$l = ls C:\Windows \| collect` puis `$l.size()` | Le nombre d'entrées de `C:\Windows`. |
| 4 | `$l.getClass().getSimpleName()` | `Collected` (une `List` non modifiable). |
| 5 | `ls C:\Windows \| collect \| map { l -> l.size() }` | **Un seul** nombre (la liste arrive entière dans `map`). |
| 6 | `ls -r --files \| collect \| map { l -> l.stream().sorted((a, b) -> Long.compare(b.size, a.size)).limit(5).toList() }` | Tableau des 5 plus gros fichiers (dossier courant et sous-dossiers). |
| 6b | `$l = ls C:\Windows --dirs \| collect` puis `$l \| map { f -> f.name }` | Un nom par ligne : en tête de pipeline, la variable est parcourue élément par élément, comme toute liste. Autres façons : `$l*.name`, `$l.stream() \| map { f -> f.name }`, `$l.forEach(f -> System.out.println(f.name))`. |
| 7 | `ls C:\Windows --dirs \| collect` | Affiché comme `ls C:\Windows --dirs` (une liste s'affiche par ses éléments). |
| 8 | `ls \| collect \| where { l -> l.size() > 3 } \| map { l -> "plus de 3 : " + l.size() }` | Une ligne si le dossier courant a plus de 3 entrées, rien sinon. |
| 9 | `ls \| collect \| map { l -> l.` puis Tab Tab | Méthodes de `List` : `size()`, `stream()`, `get(`… |
| 10 | `help collect` | Aide : résumé, `Sortie : Collected`, exemples. |
| 11 | `ls \| collect -x` | Erreur `collect : option inconnue -x`. |

## Limites connues

- `collect` attend la fin du pipeline amont avant d'émettre : sur un flux infini, il ne rend jamais la main (Ctrl+C l'arrête).
- Pas encore de `sort` ni de `first` pour les cas courants (`ls -r | sort { f -> f.size } | first 5`) : passer par `collect | map { l -> l.stream()… }`.
