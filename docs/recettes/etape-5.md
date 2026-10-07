# Recette — Étape 5 : interopérabilité Java

**Objectif :** vérifier qu'on appelle directement l'API Java du JRE depuis le shell (méthodes statiques, champs, `new`, méthodes d'instance, blocs passés comme lambdas), que les erreurs Java sont lisibles et que Ctrl+C reste maître des appels Java.

## Récupérer les livrables

Comme aux étapes précédentes : onglet **Actions**, dernière exécution du workflow **CI**, artefact `powerj-windows-x64-installer` ou `powerj-windows-x64-portable`.

## Scénario

| # | Action | Résultat attendu |
|---|---|---|
| 1 | `java.util.List.of("apple", "banana", "orange") \| where { $_.contains("b") }` | `banana`. |
| 2 | `$l = java.util.List.of("apple", "banana")` puis `$l.size()` | `2` : la liste est conservée telle quelle dans la variable. |
| 3 | `Math.max(3, 7)` puis `java.lang.Math.PI` puis `DayOfWeek.MONDAY` | `7`, `3.141592653589793`, `MONDAY`. |
| 4 | `LocalDate.now().plusDays(10).dayOfWeek` | Le jour de la semaine dans 10 jours (`java.time` est importé par défaut). |
| 5 | `MessageDigest.getInstance("SHA-256")`, puis `import java.security.*` et de nouveau `MessageDigest.getInstance("SHA-256").algorithm`, puis `import` | D'abord `« MessageDigest » inconnu…`, puis `SHA-256` ; `import` seul liste les imports actifs. |
| 6 | `new java.io.File("C:\\Windows").listFiles() \| where { $_.directory && $_.name.startsWith("S") }` | Les dossiers de `C:\Windows` commençant par `S` (`System32`…). |
| 7 | `$l.stream().map({ $_.toUpperCase() }).toList()` | `APPLE` puis `BANANA` : le bloc devient une `Function`. |
| 8 | `$m = new java.util.ArrayList($l); $m.sort({ $b.length() - $a.length() }); $m` | `banana` puis `apple` : un bloc à deux paramètres (`$a`, `$b`) devient un `Comparator`. |
| 9 | `String.format("%s-%05d", "id", 42)` | `id-00042` (varargs et conversions). |
| 10 | `Integer.parseInt("x")` | Erreur rouge `java.lang.NumberFormatException : For input string: "x"`. Puis `$errors[0].class.name` : `java.lang.NumberFormatException`. |
| 11 | `$debug = true` puis `Integer.parseInt("x")`, puis `$debug = false` | Le même message, suivi de la pile Java complète. |
| 12 | `java -version` | Lance toujours le `java` natif s'il est installé (sinon `commande inconnue : java`). |
| 13 | `help members $l` puis `help java.util.List` puis `help Math` | Méthodes et propriétés de la liste ; API de `List` (méthodes statiques, méthodes) ; champs de `Math` (`static PI : double`). |
| 14 | `ls \| where { List.of("txt", "md").contains($_.ext) }` | Les fichiers `.txt` et `.md` du dossier. |
| 15 | `Files.size(Path.of("C:\\Windows\\notepad.exe")) / 1kb` | Taille de `notepad.exe` en Ko. |
| 16 | `"Il est $(LocalTime.now().hour) h, max = $(Math.max(4, 9))"` | Les valeurs `$( … )` sont insérées dans la chaîne. |
| 17 | `[long] 5`, puis `[int] 3.9`, puis `[java.util.ArrayList] $l` | `5`, `3`, puis l'erreur `conversion impossible : … n'est pas un ArrayList`. |
| 18 | `Stream.iterate(0, { $_ + 1 }).forEach({ $_ })` puis Ctrl+C | Arrêt immédiat (`^C`), le shell reste utilisable. |
| 19 | `BigInteger.valueOf(3).pow(300000000).bitLength()` puis Ctrl+C, puis de nouveau Ctrl+C | Le calcul du JDK ignore la première demande ; la seconde rend la main avec `commande abandonnée, elle continue en arrière-plan : …`. |
| 20 | `System.out.println("bonjour " + Math.max(1, 2))` | `bonjour 2`, sans abîmer la ligne de saisie. |
| 21 | `System.setOut(null)` | Refusé : `System.setOut est refusé : il casserait l'affichage du shell`. |
| 22 | `System.exit(6)` puis, dans `cmd.exe`, `echo %ERRORLEVEL%` | PowerJ se ferme proprement (historique sauvegardé) ; `6`. |

## Points de syntaxe à connaître

- En tête de ligne, un nom qualifié **collé** à `(` est un appel Java (`Math.max(1, 2)`) ; sans parenthèses, il l'est s'il désigne une classe ou un champ statique (`Math.PI`). Sinon c'est une commande : `java -version`, `notepad.exe fichier.txt`. En argument d'une commande, seule la forme appel est une expression : `cat Path.of("a.txt")`.
- Hors parenthèses, une expression en tête de ligne s'arrête là où reprend la syntaxe des commandes : `>` redirige, `&&` et `||` enchaînent, `|` passe au pipeline. Pour comparer ou combiner, utiliser des parenthèses ou un bloc : `($a > 1 && $b)`.
- Les accès s'écrivent collés : `$l.size()`, pas `$l .size()` (l'espace sépare les arguments d'une commande).
- Les packages par défaut (`java.lang`, `java.util`, `java.io`, `java.nio.file`, `java.time`, `java.net`…) n'ont pas besoin d'`import` ; les autres classes du JDK s'utilisent par leur nom complet ou après `import`.
- Seule la bibliothèque standard Java est accessible. Pour une bibliothèque tierce, on écrit un cmdlet (étape 7).

## Limites connues de l'étape 5

- Pas de `Classe.class` ni de références de méthode (`String::length`) : utiliser `$x.getClass()` et des blocs `{ … }`.
- Une commande abandonnée (scénario 19) continue de consommer du processeur jusqu'à sa fin ou la fermeture du shell.
- Pas encore d'autocomplétion Tab, y compris pour les classes et méthodes Java (étape 6).
