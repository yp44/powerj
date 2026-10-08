# PowerJ — Spécification fonctionnelle et technique

| | |
|---|---|
| **Version du document** | 0.6 (alignement Java : lambdas, références de méthode, `map`, booléens stricts) |
| **Statut** | À valider |
| **Plateforme cible** | Windows 10/11 x64 (`powerj.exe`), Linux/macOS en bonus |
| **Socle technique** | Java 27, Maven 3.9, JLine 3 |

---

## Sommaire

1. [Vision et objectifs](#1-vision-et-objectifs)
2. [Glossaire](#2-glossaire)
3. [Exigences fonctionnelles](#3-exigences-fonctionnelles)
4. [API d'extension (cmdlets tiers)](#4-api-dextension-cmdlets-tiers)
5. [Architecture technique](#5-architecture-technique)
6. [Utilisation des fonctionnalités Java modernes](#6-utilisation-des-fonctionnalités-java-modernes)
7. [Build et distribution](#7-build-et-distribution)
8. [Configuration](#8-configuration)
9. [Exigences non fonctionnelles](#9-exigences-non-fonctionnelles)
10. [Stratégie de test](#10-stratégie-de-test)
11. [Plan de développement itératif](#11-plan-de-développement-itératif)
12. [Annexes](#12-annexes)

---

## 1. Vision et objectifs

PowerJ est un **shell interactif orienté objet** écrit en Java. Comme PowerShell, ses commandes internes (*cmdlets*) ne produisent pas du texte mais des **objets Java** — de préférence des `record` — dont on peut extraire, filtrer et combiner les attributs dans un pipeline. **Toute l'API Java du JRE est directement appelable** depuis la ligne de commande (`java.util.List.of("apple", "banana")`, `Math.max(3, 7)`). Contrairement à PowerShell :

- les commandes portent des **noms courts, familiers aux utilisateurs Unix** (`ls`, `where`…) plutôt que des noms Verbe-Nom verbeux (`Get-ChildItem`, `Where-Object`) ;
- les options suivent la **convention Unix** (`-r`, `--recurse`) ;
- les **commandes natives** (`git`, `cat`, `notepad`…) s'utilisent librement et se comportent comme dans un shell classique : leur sortie standard et leur sortie d'erreur restent des flux.

```text
PJ C:\dev\powerj> ls -r --filter *.java | where { $_.size > 10kb && $_.modified > now - 7d }

name               size      modified              dir
----               ----      --------              ---
Parser.java        14,2 KB   2026-10-05 18:12      false
Evaluator.java     11,8 KB   2026-10-06 09:40      false

PJ C:\dev\powerj> git status --porcelain | where { $_.startsWith(" M ") }
 M src/core/Parser.java
 M src/core/Evaluator.java

PJ C:\dev\powerj> java.util.List.of("apple", "banana", "orange") | where { $_.contains("b") }
banana

PJ C:\dev\powerj> LocalDate.now().plusDays(10).dayOfWeek
FRIDAY
```

### 1.1 Objectifs de la v1

| ID | Objectif |
|---|---|
| OBJ-1 | Shell interactif livré sous forme de `powerj.exe` installable sous Windows. |
| OBJ-2 | Édition de ligne confortable : historique persistant, ↑/↓, **Ctrl+R**, autocomplétion **Tab**. |
| OBJ-3 | Pipeline d'objets Java typés (records de préférence, mais tout objet) avec accès aux attributs (`$_.size`). |
| OBJ-4 | Mélange transparent cmdlets ↔ commandes natives. |
| OBJ-5 | Extensibilité : un développeur tiers ajoute des cmdlets en déposant un `.jar`. |
| OBJ-6 | Base de code exemplaire en Java moderne (Java 27). |
| OBJ-7 | Accès direct à toute l'API Java publique du JRE (méthodes statiques, constructeurs, méthodes d'instance). |

### 1.2 Non-objectifs de la v1

- Pas de langage de script complet (`if`, `foreach`, fonctions, fichiers de script) — prévu en v2.
- Pas de compatibilité syntaxique avec PowerShell ou bash.
- Pas d'exécution distante (*remoting*).
- Pas de *providers* (registre, certificats…) façon PowerShell.
- **Périmètre cmdlets volontairement réduit à 3 cmdlets (`ls`, `where`, `env`)** ; le reste du catalogue est au backlog (§12.3).

---

## 2. Glossaire

| Terme | Définition |
|---|---|
| **Cmdlet** | Commande implémentée en Java dans PowerJ (ou dans un module tiers). Elle consomme et/ou produit des objets. |
| **Commande native** | Programme externe trouvé dans le `PATH` (`git.exe`, `notepad.exe`…). Il produit du texte sur stdout/stderr. |
| **Préfixe `^`** | Force l'exécution de la commande native même si un cmdlet porte le même nom (`^ls`). |
| **Pipeline** | Chaîne d'étapes séparées par `|` ; chaque étape reçoit le flux de sortie de la précédente. |
| **Flux de sortie** | Suite d'objets Java produits par une étape (records, objets quelconques, ou lignes `String` pour une commande native). |
| **Expression Java** | Appel d'une méthode, d'un constructeur ou d'un champ Java directement dans la ligne (`Math.max(3, 7)`, `new java.io.File("x")`). |
| **Déroulage** | Émission un par un des éléments d'une collection dans un pipeline. |
| **Flux d'erreur** | Suite de messages d'erreur, affichés séparément (en rouge), jamais mélangés au flux de sortie. |
| **Record de sortie** | Type `record` Java décrivant les objets produits par un cmdlet (ex. `FileEntry`) ; recommandé mais non obligatoire. |
| **Filtre** | Cmdlet qui transforme ou sélectionne les objets reçus (ex. `where`). |
| **Bloc d'expression** | Expression entre accolades `{ … }` évaluée pour chaque objet, `$_` désignant l'objet courant. |
| **Module** | Archive `.jar` apportant un ou plusieurs cmdlets tiers. |
| **Recette** | Scénario de test manuel exécuté par le PM pour valider une étape de développement. |

---

## 3. Exigences fonctionnelles

Chaque exigence porte un identifiant `FR-xx` et un ou plusieurs **critères d'acceptation** (CA) vérifiables.

### 3.1 REPL

**FR-01 — Prompt.** Au démarrage, PowerJ affiche une bannière (`PowerJ 0.x — Java 27`) puis le prompt `PJ <répertoire courant>> `.
- CA : lancer `powerj.exe` depuis `C:\Users\yves` affiche `PJ C:\Users\yves> `.

**FR-02 — Saisie multi-ligne.** Si une ligne se termine par `|`, `&&` ou `||`, ou si une accolade, une parenthèse ou un guillemet reste ouvert (pas de continuation par `\`, pour que `cd C:\` reste valide), le shell affiche un prompt de continuation `>> ` et attend la suite.
- CA : `ls |` + Entrée affiche `>> ` ; `where { $_.dir }` + Entrée exécute le pipeline complet.

**FR-03 — Interruption (Ctrl+C).** Ctrl+C **tue la commande en cours** et rend la main sans jamais quitter le shell :
- pendant la saisie : efface la ligne courante ;
- pendant l'exécution : annule tout le pipeline — cmdlets, expressions Java et process natifs (y compris leurs process enfants) — selon le mécanisme décrit en §3.14 ;
- second Ctrl+C si la commande ne s'est pas arrêtée : abandon forcé (§3.14).
- CA : `ls -r C:\` puis Ctrl+C rend le prompt ; `Stream.iterate(0, { $_ + 1 }).forEach({ $_ })` puis Ctrl+C rend le prompt ; `ping -t localhost` puis Ctrl+C arrête `ping`.

**FR-04 — Sortie (Ctrl+D).** **Ctrl+D sur une ligne vide** ou `exit` quitte le shell en sauvegardant l'historique ; sur une ligne non vide, Ctrl+D supprime le caractère sous le curseur. `exit <code>` quitte avec ce code retour. Si des commandes abandonnées tournent encore (§3.14), le shell les termine avant de quitter.

**FR-04b — Navigation dans les dossiers.** Commandes internes au REPL (pas des cmdlets) :

| Commande | Effet |
|---|---|
| `cd <chemin>` | Change le répertoire courant (chemin absolu, relatif, `..`, `~` = dossier utilisateur, lecteur `D:`). |
| `cd` | Retour au dossier utilisateur. |
| `cd -` | Retour au dossier précédent. |
| `pwd` | Affiche le répertoire courant (objet `Path`). |

Le répertoire courant est propre au shell : il sert de base aux chemins relatifs des cmdlets, des appels Java passant par PowerJ et des commandes natives lancées (répertoire de travail du process). `$pwd` contient le `Path` courant.
- CA : `cd ~`, `cd ..`, `cd -`, `cd D:`, `cd "C:\\Program Files"` ; le prompt suit ; `git status` s'exécute dans le bon dossier.

**FR-04c — Enchaînement de commandes.** Au niveau de la ligne, plusieurs commandes s'enchaînent comme des instructions Java :

| Syntaxe | Effet |
|---|---|
| `a ; b` | Exécute `a` puis `b`, quel que soit le résultat. |
| `a && b` | Exécute `b` seulement si `a` a **réussi**. |
| `a \|\| b` | Exécute `b` seulement si `a` a **échoué**. |

« Réussi » se définit ainsi : commande native → code retour 0 ; cmdlet ou expression Java → aucune erreur bloquante ; expression de valeur `Boolean` → sa valeur. Cette règle donne la même lecture qu'en Java (`&&`/`||` court-circuitent, une commande « vaut » son succès) : `mvn package && java -jar target/app.jar`, `Files.exists(Path.of("build")) || mkdir build`. `$?` reflète le succès de la dernière commande exécutée.

**FR-04d — Mode non interactif.**

| Invocation | Effet |
|---|---|
| `powerj -c "<ligne>"` | Exécute la ligne puis quitte. |
| `powerj fichier.pj` | Exécute le fichier ligne par ligne (en v1 : suite de lignes, sans structures de contrôle) puis quitte. |
| `… \| powerj -c "where { $_.contains(\"x\") }"` | Si stdin n'est pas un terminal, ses lignes alimentent la première étape (lignes `String`). |

Dans ce mode : pas de prompt, pas d'historique, pas de couleurs si la sortie n'est pas un terminal, et une erreur bloquante arrête l'exécution. **Code retour** du process : `exit <n>` si appelé ; sinon 0 si la dernière commande a réussi, le code de la dernière commande native si elle a échoué, 1 pour une erreur bloquante PowerJ.
- CA : `powerj -c "ls | where { $_.size > 1mb }"` depuis `cmd.exe` ; `powerj -c "^cmd /c exit 3"` puis `echo %ERRORLEVEL%` affiche 3 ; `dir /b | powerj -c "where { $_.endsWith(\".txt\") }"`.

### 3.2 Édition de ligne

**FR-05 — Édition.** Édition Emacs par défaut (←/→, Home/End, Ctrl+←/→ par mot, Ctrl+W, Ctrl+K, Ctrl+U), basée sur JLine 3.

**FR-06 — Navigation dans l'historique.** ↑/↓ parcourent les commandes précédentes. Si un début de ligne est saisi, ↑/↓ ne proposent que les entrées qui commencent par ce préfixe.
- CA : après `ls -r` et `git status`, taper `gi` puis ↑ affiche `git status`.

**FR-07 — Recherche inverse (Ctrl+R).** Ctrl+R ouvre une recherche incrémentale `(reverse-i-search)'…':` dans l'historique ; Ctrl+R répété remonte à l'occurrence précédente, Ctrl+S avance, Entrée exécute, Échap/→ récupère la ligne pour édition, Ctrl+G annule.
- CA : après 3 commandes dont `ls --filter *.txt`, Ctrl+R puis `txt` affiche cette commande.

**FR-08 — Coloration syntaxique.** Pendant la saisie : cmdlet (vert), commande native (cyan), commande inconnue (rouge), options (gris), chaînes (jaune), variables (magenta).

### 3.3 Historique

**FR-09 — Persistance.** L'historique est enregistré dans `~/.powerj/history` (UTF-8) après chaque commande et rechargé au démarrage.
- CA : taper 3 commandes, quitter, relancer : ↑ les retrouve dans l'ordre.

**FR-10 — Règles.** Taille max configurable (défaut 10 000 entrées) ; doublons consécutifs ignorés ; une ligne commençant par un espace n'est pas enregistrée (commandes sensibles).

**FR-11 — Commandes d'historique.** `history` liste les entrées numérotées ; `history --clear` vide l'historique ; `!!` ré-exécute la dernière commande ; `!n` ré-exécute l'entrée *n* ; `!texte` la dernière commande commençant par `texte`. La commande développée est affichée avant exécution.

### 3.4 Nommage et résolution des commandes

**FR-12 — Règle de nommage.** Chaque cmdlet a **un seul nom**, court, en minuscules :
- repris du programme Unix équivalent quand il existe (`ls`, `cat`, `ps`, `find`…) ;
- sinon un mot court (`where`, `select`, `sort`) ou un mot composé avec tiret (`to-json`).

Il n'existe **pas** de forme longue (`pj-ls`, `Get-ChildItem`…).

**FR-13 — Ordre de résolution.** Pour le premier mot d'une étape :
1. mot-clé interne du REPL (`exit`, `history`, `help`, `which`, `cd`, `pwd`, `import`) ;
2. alias défini par l'utilisateur ;
3. **cmdlet** (intégré ou fourni par un module) ;
4. **commande native** trouvée dans le `PATH` (avec `PATHEXT` sous Windows) ;
5. sinon : erreur `commande inconnue : xxx` avec suggestions (distance d'édition).

**FR-14 — Préfixe `^`.** `^nom` saute les étapes 1 à 3 et lance toujours la commande native : `^ls`, `^find "foo" a.txt`, `^sort data.txt`.
- CA : sous Windows, `^find "x" a.txt` exécute `C:\Windows\System32\find.exe`.

**FR-15 — `which`.** `which nom` indique ce qui sera exécuté : `ls → cmdlet (powerj-cmdlets)`, `git → natif C:\Program Files\Git\cmd\git.exe`.

**FR-16 — Préférence configurable.** La clé `native.prefer` de `config.properties` liste les noms pour lesquels la commande native passe avant le cmdlet (ex. `native.prefer=find,sort`).

**FR-17 — Collisions entre modules.** Si un module déclare un cmdlet dont le nom existe déjà, un avertissement est affiché au chargement ; le premier chargé garde le nom court, l'autre reste accessible par `module:nom` (ex. `docker:ps`).

### 3.5 Paramètres

**FR-18 — Syntaxe des options (style Unix).**

| Forme | Exemple |
|---|---|
| Option courte | `-r` |
| Options courtes groupées | `-ra` (= `-r -a`) |
| Option longue | `--recurse` |
| Valeur séparée ou avec `=` | `--filter *.java`, `--filter=*.java` |
| Positionnel | `ls C:\temp` |
| Fin des options | `ls -- -fichier-commencant-par-tiret` |

Noms d'options insensibles à la casse ; une option longue peut être abrégée tant qu'elle n'est pas ambiguë (`--rec`).

**FR-19 — Littéraux d'unités.** Tailles `512b 2kb 500mb 1gb` (multiples de 1024) et durées `30s 5m 2h 7d` sont des littéraux du langage, utilisables en option comme en expression.

**FR-20 — Conversion et validation.** Les valeurs sont converties vers le type déclaré du paramètre (`Path`, `int`, `long`, `Duration`, `Instant`, enum, `boolean`…). Option obligatoire manquante, valeur inconvertible ou option inconnue → erreur explicite avant exécution, avec suggestion (`option inconnue --recurce, vouliez-vous dire --recurse ?`).

### 3.6 Autocomplétion (Tab)

**FR-21 — Complétion des commandes.** En position de commande, Tab propose : mots-clés internes, alias, cmdlets, puis exécutables du `PATH` (cache rafraîchi en tâche de fond). Le menu indique la nature : `ls [pj]`, `less [natif]`. Après `^`, seuls les exécutables natifs sont proposés.
- CA : `l<Tab>` propose `ls [pj]` puis les natifs commençant par `l`.

**FR-22 — Complétion des options.** Après `-` ou `--`, Tab propose les options de la commande courante avec leur description, **en excluant celles déjà saisies**. Les options sont lues dans les métadonnées du cmdlet : un cmdlet tiers est donc complété sans code supplémentaire.
- CA : `ls --<Tab>` propose `--all --filter --recurse`.

**FR-23 — Complétion des valeurs.** Selon le type du paramètre : enum → constantes ; `Path` → chemins de fichiers ; `boolean` → rien. Un cmdlet peut fournir sa propre complétion via `@Completion(MonCompleteur.class)`.

**FR-24 — Complétion des propriétés et variables.** Après `$` → variables définies. Après `$_.` dans un bloc `{ }` → composants du record produit par l'étape précédente (type de sortie statique du cmdlet amont). Après `$var.` → composants du type de la valeur de `$var`.
- CA : `ls | where { $_.<Tab>` propose `name size modified path dir ext`.

**FR-24b — Complétion Java.** Dans une expression Java (§3.13) :
- après `java.` / `javax.` / un nom de package → sous-packages et classes publiques (index des packages exportés du runtime et des modules chargés) ;
- après `Classe.` → méthodes et champs **statiques** ;
- après `$x.` ou `expr().` → méthodes publiques et propriétés (getters, champs) du type réel de la valeur, ou du type de retour statique connu ;
- après `new ` → classes instanciables ; après `import ` → packages et classes.

Le menu affiche la signature (`of(E...) : List<E>`). Les classes importées (§FR-47) sont proposées par leur nom simple.
- CA : `java.util.Li<Tab>` propose `List LinkedList …` ; `List.<Tab>` propose `of copyOf` ; `$l = List.of(1); $l.<Tab>` propose `size() get(int) stream() …`.

**FR-25 — Arguments des commandes natives.** Tab complète les chemins de fichiers.

**FR-26 — Ergonomie.** Premier Tab : complète le préfixe commun ; Tab suivant : menu des candidats, Tab/Shift+Tab pour s'y déplacer ; chaque candidat affiche une courte description (synopsis du cmdlet, type de l'option). Temps de réponse < 50 ms.

### 3.7 Modèle objet

**FR-27 — Tout objet Java.** N'importe quel objet Java peut circuler dans un pipeline ou être stocké dans une variable : records, `String`, nombres, `List`, `Map`, `java.io.File`, `LocalDate`, objets d'un module tiers…

Les **records restent le format recommandé** pour les sorties des cmdlets (affichage en tableau, complétion des attributs, documentation automatique), mais ils ne sont pas obligatoires : un cmdlet peut produire n'importe quel type.

**FR-28 — Accès aux attributs et méthodes.**
- `$x.nom` (sans parenthèses) est une **propriété**, résolue dans cet ordre, insensible à la casse :
  1. composant de record `nom()` ;
  2. getter `getNom()` ou `isNom()` (booléen) ;
  3. champ public `nom` ;
  4. sur une `Map` : valeur associée à la clé `"nom"`.
- `$x.nom(args)` (avec parenthèses) est toujours un **appel de méthode** (§3.13).
- L'accès se chaîne : `$x.path.parent`, `$f.toPath().fileName`.
- **`.` s'applique toujours à l'objet lui-même**, comme en Java : sur une liste, `$f.size()` est le nombre d'éléments et `$f.empty` appelle `isEmpty()`.
- **`*.` (opérateur « spread », comme en Groovy) s'applique à chaque élément** et renvoie la liste des résultats : `$f*.name` (noms de tous les fichiers), `$f*.size`, `$f*.name*.toUpperCase()`, `$f*.name.size()` (nombre de noms). Une valeur seule compte pour un élément, `null` pour aucun : `(ls -r)*.name` donne toujours une liste, même avec un seul fichier.
- `$f.name` sur une liste est une erreur explicite : `List n'a pas de propriété 'name' (pour chaque élément : *.name)`. En pipeline, l'équivalent de `*.` est `map` : `ls | map FileEntry::name`.
- Indexation : `$f[0]`, `$f[-1]` sur `List`, tableau ou `String` ; `$m['clé']` sur `Map`.
- Propriété inexistante → erreur `FileEntry n'a pas de propriété 'siz' (propriétés : name, size, …)` ; si une méthode de ce nom existe, le message l'indique : `String n'a pas de propriété 'length' (méthode : length())`.

**FR-29 — Introspection.** `help members` sur une valeur (`$f | help members` ou `help members FileEntry`) liste les composants : nom, type, description (Javadoc / annotation `@Doc`).

**FR-30 — Affichage par défaut.** En fin de pipeline, chaque objet est affiché selon son type :
- **record** : en tableau si ≤ 5 composants affichables (colonnes alignées, largeur adaptée au terminal), sinon en liste `nom : valeur` ; un record peut déclarer ses colonnes via `@Display(columns = {"name", "size", "modified"})` ;
- **`Map`** : tableau `clé / valeur` ;
- **scalaires** (`String`, nombres, booléens, dates, `Path`, enums) : une ligne, forme lisible ;
- **autres objets** : `toString()` ; `help members` permet d'explorer leurs propriétés.

Des objets successifs du même type record sont regroupés dans un même tableau.

**FR-30b — Déroulage des collections.** Lorsqu'une étape de pipeline produit un `Iterable` (`List`, `Set`…), un tableau, un `Stream`, un `Iterator` ou un `Optional`, ses éléments sont **émis un par un** dans le flux (`Optional` vide → rien). `String` et `Map` ne sont **jamais** déroulés.
En **affectation**, l'objet est conservé tel quel :

```text
PJ> java.util.List.of("apple", "banana", "orange") | where { $_.contains("b") }
banana
PJ> $l = java.util.List.of("apple", "banana")
PJ> $l.size()
2
PJ> $l | where { $_.length() > 5 }
banana
```

Le déroulage est paresseux pour `Stream` et `Iterator` (pas de matérialisation en mémoire).

Tailles et durées en format lisible (`14,2 KB`, `2 h 05 min`), dates en heure locale.

### 3.8 Langage d'expression

**FR-31 — Variables.** `$nom = <pipeline>` affecte le résultat (un objet → l'objet ; plusieurs → liste ; aucun → `null`). Variables automatiques : `$_` (objet courant), `$last` (métadonnées de la dernière commande native), `$exit` (son code retour), `$?` (succès de la dernière commande), `$errors` (erreurs récentes), `$home`, `$pwd`.

**FR-32 — Littéraux.** Comme en Java, avec quelques ajouts du shell :
- **chaînes** entre guillemets doubles, avec les **échappements Java** : `\\` pour un antislash, `\"`, `\n`, `\t`, `\uXXXX` → `"C:\\Users\\yves"` ; interpolation `$var` et `$(expr)` (`\$` pour un `$` littéral) ; blocs de texte `"""…"""` ;
- **caractères** entre apostrophes : `'a'`, `'\n'` (type `char`, comme en Java) ;
- entiers, décimaux, `true`/`false`/`null`, tailles et durées (FR-19), `now`, listes `[1, 2, 3]`.

**FR-32b — Arguments de commande non quotés.** Un argument de commande écrit **sans guillemets** (`cd C:\Users`, `ls D:\photos`, `git log -n 5`) est pris **tel quel** : l'antislash n'y est pas un caractère d'échappement, ce qui permet de taper les chemins Windows naturellement. Les échappements Java ne s'appliquent qu'**à l'intérieur des guillemets**. Un argument contenant des espaces se met entre guillemets en doublant les antislashs : `cd "C:\\Program Files"`.

**FR-33 — Opérateurs dans `{ }` : syntaxe Java.** Les blocs utilisent les opérateurs de Java ; pour tout le reste (motifs, expressions régulières, appartenance…), on appelle **les méthodes Java** des objets (§3.13). Il n'y a pas d'opérateur propre au shell comme `like` ou `-match`.

| Catégorie | Opérateurs | Sémantique |
|---|---|---|
| Égalité | `==  !=` | Égalité de **valeur** (`Objects.equals`), pas de référence ; nombres comparés par valeur (`1 == 1L`). |
| Ordre | `<  <=  >  >=` | Nombres par valeur ; autres types via `Comparable.compareTo` (dates, `Duration`, chaînes…). |
| Logique | `&&  \|\|  !` | Court-circuit, comme en Java. |
| Arithmétique | `+  -  *  /  %` | Règles Java ; `+` concatène si l'un des opérandes est une `String` ; `Instant - Duration`, `Instant + Duration` supportés. |
| Ternaire | `cond ? a : b` | Comme en Java. |

Les comparaisons de chaînes sont **sensibles à la casse**, comme en Java (`equalsIgnoreCase`, `toLowerCase()` pour l'inverse).

Équivalences pour les besoins courants :

| Besoin | Écriture PowerJ |
|---|---|
| Contient | `$_.contains("b")` |
| Commence / finit par | `$_.name.startsWith("Pa")`, `$_.name.endsWith(".java")` |
| Expression régulière | `$_.matches("^[a-m].*")` (ligne entière) ou `Pattern.compile("IPv4").matcher($_).find()` |
| Insensible à la casse | `$_.toLowerCase().contains("readme")`, `$_.equalsIgnoreCase("ok")` |
| Appartenance | `List.of("png", "jpg").contains($_.ext)` |
| Joker de fichier | `FileSystems.getDefault().getPathMatcher("glob:*.java").matches($_.path.fileName)` (ou option `--filter` de `ls`) |

Note : `!` en début de ligne reste l'expansion d'historique (FR-11) ; à l'intérieur d'une expression, c'est la négation. Dans un bloc `{ }`, `&&` et `||` sont les opérateurs logiques ; hors bloc, ils enchaînent des commandes (FR-04c).

**FR-33b — Blocs et lambdas : la syntaxe Java d'abord.** PowerJ privilégie la syntaxe Java chaque fois qu'elle existe ; les emprunts aux shells sont réservés à ce que Java n'exprime pas (variables `$x`, interpolation `"$x"`, `$?`, redirections, littéraux `10kb` / `7d`).

| Forme | Exemple | Sens |
|---|---|---|
| Lambda à un paramètre | `{ f -> f.size > 1mb }` | `f` est l'objet reçu. Paramètres sans `$`, comme en Java ; les variables du shell gardent leur `$` : `{ f -> f.size > $min }`. |
| Lambda à plusieurs paramètres | `{ (a, b) -> a.length() - b.length() }` | Pour `Comparator`, `BiFunction`, `reduce`… Aucun paramètre : `{ () -> "x" }`. |
| Raccourci `$_` | `{ $_.dir }` | Bloc sans paramètre déclaré : `$_` est l'objet reçu (un seul paramètre). Pratique pour les filtres courts. |
| Lambda sans accolades | `$l.stream().map(s -> s.length())` | Uniquement **entre les parenthèses d'un appel Java** ; en argument de cmdlet, les accolades restent obligatoires (le `>` de `->` serait sinon une redirection). |
| Référence de méthode | `String::length`, `Path::of`, `ArrayList::new`, `$x::equals` | Comme en Java : statique, d'instance non liée, liée à un objet, constructeur. |

**Quand utiliser `$_`, une lambda ou une référence de méthode.** Les trois formes font la même chose ; on choisit la plus lisible :

| Situation | Forme conseillée | Exemple |
|---|---|---|
| Condition ou transformation **courte**, qui ne cite l'objet qu'une ou deux fois | `$_` | `ls \| where { $_.dir }`, `ipconfig \| where { $_.contains("IPv4") }`, `ls \| map { $_.name }` |
| Expression **longue**, ou qui cite l'objet plusieurs fois : un nom parlant aide à relire | lambda nommée | `ls -r \| where { f -> f.size > 1mb && f.modified > now - 7d && !f.name.startsWith(".") }` |
| Bloc **imbriqué** dans un autre bloc : `$_` désignerait l'objet du bloc intérieur, pas celui de l'extérieur | lambda nommée (obligatoire pour l'extérieur) | `ls -r \| where { f -> List.of("md", "txt").stream().anyMatch(e -> f.name.endsWith("." + e)) }` |
| **Deux paramètres ou plus** (`Comparator`, `reduce`, `BiFunction`) | lambda | `$m.sort((a, b) -> a.length() - b.length())` |
| Aucun paramètre (`Supplier`, `Runnable`) | lambda `() ->` | `Optional.empty().orElseGet(() -> "vide")` |
| Le bloc se contente d'**appeler une méthode** sur l'objet, ou de le passer à une méthode | référence de méthode | `ls \| map FileEntry::name`, `$l.stream().map(String::toUpperCase)`, `$noms.stream().map(Path::of)` |
| Argument d'une **méthode Java** (`stream().filter(…)`, `sort(…)`) | lambda sans accolades | `$l.stream().filter(s -> s.length() > 4)` |
| Argument d'un **cmdlet** (`where`, `map`) | accolades obligatoires | `where { f -> f.size > 1mb }` (jamais `where f -> …`) |
| Script `.pj` destiné à être relu et maintenu | lambda nommée | `where { fichier -> fichier.ext == "log" }` |

Règle courte : **`$_` pour les filtres d'une ligne au clavier, une lambda nommée dès que l'expression grandit, s'imbrique ou prend deux paramètres, une référence de méthode quand elle suffit.**

- Un paramètre de lambda masque, dans le corps du bloc, une classe de même nom (cas rare : nommer les paramètres en minuscules).
- `$a`, `$b` et `$args` (étape 5) sont **retirés** : on écrit une lambda à deux paramètres.
- **Booléens stricts** : là où une condition est attendue (`where`, `filter`, `&&`, `||`, `!`, ternaire), la valeur doit être un `boolean`, comme un `Predicate` Java. `where { f -> f.name }` est une erreur non bloquante (`le bloc doit renvoyer un booléen`) ; écrire `where { f -> !f.name.isEmpty() }`. `null` n'est pas un booléen.
- Les conversions gardent la notation `[type] valeur` : la forme Java `(type) valeur` serait ambiguë avec `(commande)`.
- **Blocs de texte** : `"""…"""` comme en Java (indentation commune retirée), avec interpolation `$x` et `$( … )`.

**FR-34 — Redirections (hors blocs).** `> fichier` (écrase), `>> fichier` (ajoute) pour le flux de sortie ; `2> fichier`, `2>&1` pour le flux d'erreur. Les objets redirigés vers un fichier sont écrits sous leur forme affichée.

### 3.9 Cmdlets du périmètre actuel

Seuls **quatre cmdlets** sont dans le périmètre de ce document : `ls`, `where`, `map` et `env`. Les deux premiers couvrent à eux seuls les mécanismes centraux : production d'objets records, accès aux attributs, pipeline, expressions, mélange avec les commandes natives.

#### FR-35 — `ls` : lister des fichiers

```text
ls [chemin...] [-a|--all] [-r|--recurse] [-f|--filter <motif>] [-d|--dirs] [--files]
```

| Option | Type | Description |
|---|---|---|
| `chemin` (positionnel, multiple) | `Path` | Dossier(s) ou fichier(s) à lister ; défaut : répertoire courant. Jokers acceptés (`*.txt`). |
| `-a`, `--all` | booléen | Inclut les fichiers cachés/système. |
| `-r`, `--recurse` | booléen | Parcourt les sous-dossiers. |
| `-f`, `--filter` | motif | Ne garde que les noms correspondant au motif (`*.java`). |
| `-d`, `--dirs` | booléen | Uniquement les dossiers. |
| `--files` | booléen | Uniquement les fichiers. |

Sortie : flux de

```java
public record FileEntry(
        String name,       // nom avec extension
        long size,         // taille en octets (0 pour un dossier)
        Instant modified,  // dernière modification
        Path path,         // chemin absolu
        boolean dir,       // true si dossier
        String ext) { }    // extension sans le point, "" si aucune
```

Colonnes affichées par défaut : `name size modified dir`. Les dossiers sont listés avant les fichiers, par ordre alphabétique. Le parcours est **paresseux** (streaming) : `ls -r C:\ | where …` affiche les premiers résultats immédiatement et Ctrl+C l'interrompt. Un dossier inaccessible produit une erreur non bloquante et le parcours continue.

CA :
- `ls` affiche le contenu du répertoire courant en tableau ;
- `ls -r --filter *.txt` liste récursivement les `.txt` ;
- `(ls)*.name` affiche uniquement les noms ;
- `$f = ls; $f[0].size` affiche la taille du premier élément ;
- `^ls` exécute le `ls` natif s'il existe (Git Bash, WSL…), sinon erreur `commande native introuvable`.

#### FR-36 — `where` : filtrer des objets

```text
where { <expression> }
where <attribut> <opérateur> <valeur>        # forme courte
```

Évalue la condition pour chaque objet reçu (paramètre de la lambda, ou `$_`) et ne laisse passer que ceux pour lesquels elle vaut `true`. Fonctionne sur les records, les scalaires et donc les **lignes `String` produites par une commande native**. La forme courte `where size > 1mb` équivaut à `where { $_.size > 1mb }`.

La condition doit renvoyer un `boolean` (FR-33b) ; une autre valeur, ou une erreur d'évaluation, produit une erreur non bloquante et l'objet est ignoré.

CA :
- `ls -r | where { $_.size > 1mb }` ;
- `ls -r | where { f -> f.size > 1mb && !f.dir }` ;
- `ls | where { $_.name.endsWith(".java") && !$_.dir }` ;
- `ls | where { List.of("png", "jpg").contains($_.ext) }` ;
- `git status --porcelain | where { $_.startsWith(" M ") }` ;
- `ipconfig | where { $_.contains("IPv4") }`.

#### FR-36c — `map` : transformer des objets

```text
map { <lambda ou expression> }
map <référence de méthode>
```

Applique le bloc à chaque objet reçu et émet le résultat, comme `Stream.map` : `ls -r | map { f -> f.name + " : " + f.name.length() }`, `ls | map FileEntry::name`. Un résultat `null` n'émet rien ; un résultat collection est déroulé (FR-30b), comme un `flatMap`. Une erreur d'évaluation est non bloquante (objet ignoré).

CA :
- `ls -r | map { f -> f.name.length() }` ;
- `ls | map { $_.name.toUpperCase() } | where { s -> s.startsWith("P") }` ;
- `env | map EnvVar::name`.

#### FR-36b — `env` : variables d'environnement

```text
env                              # liste toutes les variables
env <NOM>                        # une variable
env --set <NOM>=<valeur>         # crée ou modifie (aussi : env -s NOM=valeur)
env --unset <NOM>                # supprime
env --append <NOM> <valeur>      # ajoute à une liste (séparateur ; sous Windows, : ailleurs)
env --prepend <NOM> <valeur>     # idem, en tête de liste
```

Sortie : flux de `record EnvVar(String name, String value)`, triés par nom (noms insensibles à la casse sous Windows).

Les modifications concernent **l'environnement de la session PowerJ** : elles s'appliquent à toutes les commandes natives lancées ensuite, à la recherche des exécutables dans le `PATH` (FR-13) et aux réglages lus par PowerJ (`POWERJ_NATIVE_ENCODING`…). Elles ne sont pas persistées : pour les rendre permanentes, les placer dans `profile.pj` (§8). Note : `System.getenv()` en expression Java renvoie l'environnement **initial** du process (une JVM ne peut pas modifier son propre environnement) ; utiliser `env` pour l'environnement de la session.

CA :
- `env | where { $_.name.startsWith("JAVA") }` ;
- `(env PATH).value.split(";")` liste les dossiers du `PATH` ;
- `env --append PATH C:\tools` puis un outil de `C:\tools` est trouvé et `which` l'indique ;
- `env --set MAVEN_OPTS=-Xmx2g` puis `mvn` reçoit la variable ;
- `env --unset MAVEN_OPTS`.

### 3.10 Commandes natives

**FR-37 — Principe : les flux restent des flux.** Une commande native n'est **pas** encapsulée dans un objet : sa sortie standard et sa sortie d'erreur sont traitées comme des flux, à la manière d'un shell classique.

| Situation | stdout | stderr |
|---|---|---|
| **Dernière étape** au REPL (`git log`) | Hérité directement du terminal : couleurs, pagination, programmes interactifs (`vim`, `ssh`, `python`) fonctionnent. | Hérité du terminal. |
| **Étape suivie d'un cmdlet** (`git status \| where …`) | Converti en **flux de lignes `String`** (décodage selon FR-40b), en streaming. | **Flux d'erreur PowerJ** (affiché en rouge), jamais mélangé aux objets. |
| **Affectation** (`$l = ipconfig`) | Capturé en liste de lignes `String`. | Flux d'erreur PowerJ. |
| **Natif → natif** (`^cat a.txt \| ^sort`) | Octets transmis **directement** d'un process à l'autre, sans décodage (préserve encodage et binaire). | Flux d'erreur PowerJ. |
| **Cmdlet → natif** (`ls \| ^more`) | Les objets sont convertis en texte (forme affichée) et écrits sur le stdin du process. | — |

Redirections applicables (FR-34) : `git log > log.txt`, `git badcmd 2> err.txt`, `cmd 2>&1 | where …`.

**FR-38 — Métadonnées d'exécution.** Après chaque commande native, PowerJ renseigne (sans les injecter dans le flux) :

```java
public record NativeRun(
        String command,     // chemin absolu de l'exécutable
        List<String> args,
        long pid,
        int exitCode,
        Duration duration) { }
```

accessible via `$last` ; `$exit` vaut `$last.exitCode` ; `$?` vaut `true` si le code est 0. Un code non nul n'est **pas** une erreur bloquante.
- CA : `^cmd /c "exit 3"` puis `$exit` affiche `3` ; `$last.duration` affiche la durée.

**FR-39 — Applications graphiques.** Un exécutable Windows du sous-système GUI (détecté en lisant l'en-tête PE) est lancé **détaché** : le shell rend la main immédiatement, `$last.pid` est renseigné, `$exit` vaut `null`.
- CA : `notepad` ouvre le Bloc-notes et le prompt revient aussitôt.

**FR-40b — Encodage des commandes natives.** Le texte échangé avec les commandes natives (stdout/stderr décodés en `String`, objets écrits sur stdin) utilise l'encodage défini par la **variable d'environnement `POWERJ_NATIVE_ENCODING`** :

| Valeur | Effet |
|---|---|
| *(non définie)* ou `auto` | Défaut : page de code de sortie de la console Windows (ex. `cp850` sur un Windows français) ; UTF-8 sous Linux/macOS. |
| un nom de charset Java (`UTF-8`, `cp850`, `windows-1252`…) | Utilisé pour toutes les commandes natives. |

Une valeur propre à un programme peut être donnée par **`POWERJ_NATIVE_ENCODING_<NOM>`**, où `<NOM>` est le nom de l'exécutable en majuscules, sans extension : `POWERJ_NATIVE_ENCODING_GIT=UTF-8` (git produit de l'UTF-8 alors que `ipconfig` utilise la page de code console). La variable se définit dans l'environnement Windows ou dans la session avec `env --set` (FR-36b), et s'applique dès la commande suivante. Un nom de charset invalide produit une erreur explicite au lancement de la commande. Le flux natif → natif n'est jamais décodé (FR-37).
- CA : sur un Windows français, `ipconfig | where { $_.contains("Adresse") }` affiche les accents correctement sans configuration ; `env --set POWERJ_NATIVE_ENCODING_GIT=UTF-8` puis `git log --oneline | where { $_.contains("é") }`.

**FR-40 — Arguments.** Les arguments sont passés tels quels après expansion des variables et des jokers (expansion des jokers sur les chemins existants, désactivable en mettant l'argument entre guillemets). Sous Windows, la ligne de commande est construite selon les règles de quoting de `CommandLineToArgvW`.

### 3.11 Erreurs

**FR-41 — Deux catégories.**
- **Non bloquante** : signalée sur le flux d'erreur, le pipeline continue (fichier inaccessible pendant `ls -r`).
- **Bloquante** : arrête tout le pipeline (erreur de syntaxe, commande inconnue, option invalide, exception non prévue).

Les erreurs sont conservées dans `$errors` (50 dernières). Format : `ls : accès refusé : C:\System Volume Information`.

**FR-42 — Option commune `--on-error`.** Tout cmdlet accepte `--on-error stop|continue|silent` (défaut `continue`) pour changer la gestion des erreurs non bloquantes.

**FR-43 — Mode debug.** `powerj.exe --debug` (ou `$debug = true`) affiche la pile Java complète des erreurs inattendues ; sinon un message court est affiché.

**FR-44 — Langue.** Messages en français ou en anglais selon la locale système, forçable via `config.properties` (`lang=fr`).

### 3.12 Aide

**FR-45 — `help`.** `help` liste les cmdlets par catégorie avec leur résumé ; `help ls` affiche le synopsis, les options, le record de sortie et des exemples ; `ls --help` est équivalent. Ces informations sont générées depuis les métadonnées du cmdlet (annotations), donc disponibles pour les cmdlets tiers.

### 3.13 Interopérabilité Java

PowerJ donne un accès direct à **toute l'API Java publique** disponible dans le runtime : méthodes statiques, constructeurs, champs et méthodes d'instance. Le résultat est un objet Java ordinaire qui s'intègre au pipeline (FR-27, FR-30b).

**FR-46 — Appels statiques.** Un nom qualifié suivi de parenthèses appelle une méthode statique ; sans parenthèses, il lit un champ statique ou désigne la classe :

```text
PJ> java.util.List.of("apple", "banana", "orange")
apple
banana
orange
PJ> java.lang.Math.max(3, 7)
7
PJ> java.lang.Math.PI
3.141592653589793
PJ> java.time.DayOfWeek.MONDAY
MONDAY
```

**Règle lexicale (désambiguïsation avec les commandes).** En position de commande, un mot de la forme `ident(.ident)+` est une **expression Java** s'il est **immédiatement** suivi de `(` (sans espace), ou s'il désigne une classe ou un champ statique d'une classe connue. Sinon, la résolution des commandes (FR-13) s'applique. Ainsi `java -version` et `notepad.exe fichier.txt` restent des commandes natives, tandis que `java.lang.Math.max(1, 2)` est un appel Java. En cas de doute, une expression peut toujours être mise entre parenthèses : `(Math.max(1, 2))`.

**FR-47 — Imports par défaut.** Les packages les plus utiles du JDK sont **importés automatiquement** : leurs classes s'utilisent directement par leur nom simple, sans `import`.

| Domaine | Packages importés par défaut |
|---|---|
| Base | `java.lang`, `java.math`, `java.text` |
| Collections et flux | `java.util`, `java.util.function`, `java.util.stream`, `java.util.regex`, `java.util.concurrent` |
| Fichiers et E/S | `java.io`, `java.nio.file`, `java.nio.charset` |
| Réseau | `java.net`, `java.net.http` |
| Dates | `java.time`, `java.time.format` |

Ces packages ne contiennent aucun nom de classe en double (vérifié sur le JDK), donc aucun conflit. Les packages susceptibles d'en créer (`java.awt` avec `List`, `java.sql` avec `Date`…) ne sont pas importés par défaut, mais restent utilisables par leur nom complet (`java.sql.Date`) ou par un `import` explicite.

**Imports explicites.** `import java.security.*` ou `import javax.crypto.Cipher` ajoute des imports pour le reste de la session (ou depuis `profile.pj`). `import` sans argument liste les imports actifs. Un nom simple devenu ambigu (deux imports) produit une erreur listant les candidats.

```text
PJ> LocalDate.now().plusDays(10).dayOfWeek
FRIDAY
PJ> Files.readString(Path.of("notes.txt")).lines().count()
42
PJ> HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("https://example.com")).build(), HttpResponse.BodyHandlers.ofString()).statusCode()
200
PJ> new BigDecimal("0.1").add(new BigDecimal("0.2"))
0.3
```

**FR-48 — Instanciation.** `new Classe(args)` appelle un constructeur public : `new java.io.File("C:\\temp")`, `new StringBuilder("ab").reverse()`.

**FR-49 — Appels d'instance.** Toute valeur expose ses méthodes publiques : `$l.size()`, `"abc".toUpperCase().length()`, `$f[0].path.toFile().length()`, `(ls)[0].modified.atZone(ZoneId.systemDefault()).year`. Les propriétés (FR-28) et les appels se combinent librement. Un appel de méthode peut apparaître partout où une expression est attendue, y compris dans les blocs `{ }` de `where`.

**FR-50 — Résolution des surcharges et conversions.**
- Les valeurs PowerJ sont converties vers les types de paramètres : entier → `int`/`long`/`short`/`byte`/`Integer`/`Long`/`BigInteger` (si sans perte) ; décimal → `double`/`float`/`BigDecimal` ; chaîne → `String`, `CharSequence`, `char` (si 1 caractère), `Path`, `File`, enum (par nom) ; liste PowerJ → `List`, `Collection`, tableau ; taille (FR-19) → `long` ; durée → `Duration`.
- **varargs** supportés (`List.of("a", "b", "c")`, `String.format("%s-%s", 1, 2)`).
- Parmi les surcharges applicables, la plus spécifique est choisie (règles proches de JLS §15.12) ; en cas d'ambiguïté, erreur listant les signatures candidates ; aucun candidat → erreur listant les surcharges existantes.
- Une conversion explicite est possible par cast : `[long] 5`, `[java.util.ArrayList] $l` (vérification à l'exécution).

**FR-51 — Lambdas et références de méthode vers interfaces fonctionnelles.** Une lambda (FR-33b), un bloc ou une référence de méthode passé à un paramètre dont le type est une interface fonctionnelle (`Predicate`, `Function`, `Comparator`, `Runnable`, `Supplier`…) est converti automatiquement :
- le nombre de paramètres de la lambda doit correspondre à celui de la méthode abstraite ; un bloc sans paramètre déclaré reçoit son unique argument dans `$_` ;
- la valeur est convertie vers le type de retour de la méthode abstraite (FR-50) ; `boolean` exige un booléen ;
- une référence de méthode est résolue au moment de l'appel, selon le nombre d'arguments reçus.

```text
PJ> $l = List.of("apple", "banana", "kiwi")
PJ> $l.stream().filter(s -> s.length() > 4).map(String::toUpperCase).toList()
APPLE
BANANA
PJ> $m = new ArrayList($l); $m.sort((a, b) -> a.length() - b.length()); $m
kiwi
apple
banana
```

**FR-52 — Périmètre et sécurité.**
- Accessibles par défaut : classes et membres **`public`** de **toute la bibliothèque standard Java SE**, c'est-à-dire tous les packages exportés par les modules `java.*` du runtime (agrégat `java.se`, cf. §7) : `java.base` (lang, util, io, nio, net, math, time, text, security…), `java.net.http`, `java.sql`, `java.xml`, `java.desktop`, `java.logging`, `java.management`, `java.prefs`, `javax.crypto`, `javax.net.ssl`, etc.
- **Pas de bibliothèque externe** en interop : l'appel direct est limité au JDK. Un besoin qui demande une bibliothèque tierce (Apache Commons, client de base de données…) se traite en **écrivant un cmdlet** (§4) qui embarque cette bibliothèque. Les classes des modules tiers ne sont pas exposées en expression Java.
- Pas d'accès réflexif forcé (`setAccessible`), ni aux packages internes (`jdk.internal.*`, `sun.*`).
- Les méthodes `default` des interfaces et les méthodes héritées sont accessibles normalement ; l'appel passe par l'interface publique quand la classe d'implémentation n'est pas exportée (ex. `List.of(...)` renvoie une classe interne, ses méthodes sont appelées via `java.util.List`).

**FR-53 — Exceptions.** Une exception levée par un appel Java est une **erreur bloquante** affichée sous forme courte : `java.lang.NumberFormatException : For input string: "x"` (pile complète en mode `--debug`). L'objet exception est conservé dans `$errors` (`$errors[0].cause`, `$errors[0].stackTrace`).

**FR-54 — Introspection.**
- `help members $x` : méthodes publiques (avec signatures), propriétés dérivées (composants de record, getters, champs) du type réel de `$x`.
- `help java.util.List` (ou `help List` après import) : constructeurs, méthodes statiques et d'instance, champs.
- `$x.getClass()` reste disponible.

**FR-55 — Exemples de session.**

```text
PJ> Files.readAllLines(Path.of("notes.txt")) | where { $_.contains("TODO") }
PJ> Files.size(Path.of("gros.iso")) / 1mb
PJ> new java.io.File("C:\\Windows").listFiles() | where { $_.directory && $_.name.startsWith("S") }
PJ> java.util.UUID.randomUUID()
PJ> java.net.InetAddress.getLocalHost().hostAddress
PJ> String.join(", ", (ls)*.name)
PJ> (ls -r | where size > 1mb).size()
```

### 3.14 Robustesse

Principe : **rien de ce qu'exécute une ligne ne peut faire tomber le shell.** Une commande se termine toujours par l'un de quatre résultats, et la boucle du REPL ne voit jamais d'exception.

**FR-56 — Résultat d'exécution.** Chaque ligne est exécutée par un **superviseur** qui renvoie un résultat d'un type scellé :

```java
sealed interface Outcome {
    record Success(List<Object> values)        implements Outcome { }
    record Failure(PjError error)              implements Outcome { }
    record Cancelled()                         implements Outcome { }   // Ctrl+C
    record Abandoned(String commandLine)       implements Outcome { }   // ne répondait plus
}
```

La boucle du REPL se réduit à `switch (supervisor.run(line))` sur ces quatre cas (affichage, message d'erreur, `^C`, avertissement). Le superviseur capture **tout `Throwable`**, y compris :

| Problème | Traitement |
|---|---|
| Exception Java (cmdlet, appel Java) | Erreur bloquante courte (FR-53), objet dans `$errors`. |
| `StackOverflowError` (récursion infinie) | Erreur « récursion trop profonde » ; l'interpréteur limite aussi sa propre profondeur d'évaluation. |
| `OutOfMemoryError` | Une **réserve mémoire** allouée au démarrage est libérée pour permettre au shell de continuer ; les objets de la commande sont relâchés ; message conseillant de filtrer plus tôt dans le pipeline. La réserve est réallouée ensuite. |
| `LinkageError`, `ExceptionInInitializerError` | Erreur bloquante avec le nom de la classe en cause. |
| Erreur interne de PowerJ (bug) | Message court + pile complète écrite dans `~/.powerj/logs/powerj.log`. |

**FR-57 — Annulation par Ctrl+C, en trois niveaux.** Toutes les étapes d'une ligne s'exécutent dans une même portée de concurrence structurée (§5.3), ce qui permet de tout annuler d'un coup.
1. **Coopératif (immédiat)** : un jeton d'annulation (transmis par `ScopedValue`) est vérifié par l'interpréteur à chaque nœud évalué et à chaque appel de bloc `{ }`, par le pipeline entre deux objets et par les cmdlets (`ctx.cancelled()`). Cela couvre les boucles du shell et les appels Java qui rappellent un bloc (`Stream.iterate(0, { $_ + 1 }).forEach(...)`).
2. **Interruption** : les threads de la commande sont interrompus (`Thread.interrupt`), ce qui débloque les E/S, `sleep`, `HttpClient`, les files d'attente. Les **process natifs** et tous leurs descendants (`ProcessHandle.descendants()`) sont arrêtés, puis tués de force s'ils ne s'arrêtent pas.
3. **Abandon** : si la commande ne s'est toujours pas arrêtée — typiquement du code du JDK qui ne vérifie pas l'interruption, comme une expression régulière catastrophique ou un tri géant — un **second Ctrl+C** l'abandonne : le shell rend la main avec l'avertissement `commande abandonnée, elle continue en arrière-plan`, sa sortie est ignorée, et elle est arrêtée à la fermeture du shell. (Java ne permet pas de tuer un thread de force ; l'abandon est la seule issue sûre.)

**FR-58 — Appels Java dangereux pour le shell.** Certaines méthodes du JDK agiraient sur le shell lui-même plutôt que sur la commande. Elles sont **interceptées lors de la résolution de l'appel** (§5.4), sans mécanisme de sécurité supplémentaire :

| Appel | Traitement |
|---|---|
| `System.exit(n)`, `Runtime.getRuntime().exit(n)`, `Runtime.getRuntime().halt(n)` | Équivaut à la commande `exit n` (fermeture propre, historique sauvegardé). |
| `System.setOut(…)`, `System.setErr(…)`, `System.setIn(…)` | Refusé, avec un message explicatif (casserait l'affichage du terminal). |

Le code Java qui écrit sur `System.out` / `System.err` (`System.out.println("x")`) s'affiche normalement, sans corrompre la ligne en cours de saisie : au démarrage, ces flux sont reliés au terminal JLine.

**FR-59 — Terminal et historique toujours restaurés.** L'historique est écrit après chaque commande (FR-09), donc un arrêt brutal ne perd rien. À la sortie, y compris sur erreur fatale de la JVM ou fermeture de la fenêtre, un hook d'arrêt remet le terminal dans son état initial (mode raw désactivé, couleurs réinitialisées).

**FR-60 — Journal de diagnostic.** Les erreurs internes et les avertissements sont journalisés dans `~/.powerj/logs/powerj.log` (rotation, 5 fichiers maximum). `--debug` affiche aussi ces détails à l'écran.

---

## 4. API d'extension (cmdlets tiers)

### 4.1 Principes

- Le module Maven **`powerj-api`** est la **seule dépendance** nécessaire pour écrire un cmdlet. Il est publié séparément et versionné sémantiquement.
- Un cmdlet est une classe qui implémente `Cmdlet<P, I, O>` ; le type de sortie `O` est libre (**un record est recommandé** pour bénéficier de l'affichage en tableau et de la complétion des attributs).
- Les options sont déclarées par un **record de paramètres** dont les composants sont annotés `@Option`.
- Les modules sont découverts par `ServiceLoader` et chargés dans un **`ModuleLayer` isolé** par jar.

### 4.2 Contrat

```java
package io.powerj.api;

/** P : record de paramètres. I : type des objets reçus (Void si le cmdlet ne lit pas le pipeline).
 *  O : type des objets produits (record recommandé, tout type accepté). */
public interface Cmdlet<P extends Record, I, O> {
    default void begin(P params, CmdletContext<O> ctx) throws Exception { }
    default void process(P params, I input, CmdletContext<O> ctx) throws Exception { }
    default void end(P params, CmdletContext<O> ctx) throws Exception { }
}

public interface CmdletContext<O> {
    void emit(O value);                 // écrit sur le flux de sortie
    void error(String message);         // erreur non bloquante
    Path cwd();
    Optional<Object> variable(String name);
    boolean cancelled();                // Ctrl+C demandé
}

@Retention(RUNTIME) @Target(TYPE)
public @interface CmdletInfo {
    String name();
    String category() default "Divers";
    String summary();
    String[] examples() default {};
}

@Retention(RUNTIME) @Target(RECORD_COMPONENT)
public @interface Option {
    char shortName() default '\0';
    String longName() default "";      // défaut : nom du composant
    boolean mandatory() default false;
    int position() default -1;          // >= 0 : paramètre positionnel
    String description() default "";
}

@Retention(RUNTIME) @Target(RECORD_COMPONENT)
public @interface Completion { Class<? extends Completer> value(); }

public interface CmdletProvider { List<Cmdlet<?, ?, ?>> cmdlets(); }
```

> Les signatures exactes seront figées à l'étape 3 ; elles sont données ici pour fixer l'intention.

### 4.3 Exemple complet : module `greet`

```java
// module-info.java
module com.example.greet {
    requires io.powerj.api;
    provides io.powerj.api.CmdletProvider with com.example.greet.GreetProvider;
}

// Greeting.java — record de sortie
public record Greeting(String name, String message, Instant at) { }

// GreetParams.java — record de paramètres
public record GreetParams(
        @Option(shortName = 'n', mandatory = true, description = "Nom à saluer") String name,
        @Option(shortName = 'c', description = "Nombre de répétitions") int count) {
    public GreetParams {
        if (count <= 0) count = 1;
    }
}

// Greet.java
@CmdletInfo(name = "greet", category = "Exemples", summary = "Salue quelqu'un",
            examples = "greet --name Yves -c 3")
public final class Greet implements Cmdlet<GreetParams, Void, Greeting> {
    @Override
    public void begin(GreetParams p, CmdletContext<Greeting> ctx) {
        for (int i = 0; i < p.count(); i++) {
            ctx.emit(new Greeting(p.name(), "Bonjour " + p.name() + " !", Instant.now()));
        }
    }
}

// GreetProvider.java
public final class GreetProvider implements CmdletProvider {
    public List<Cmdlet<?, ?, ?>> cmdlets() { return List.of(new Greet()); }
}
```

Utilisation :

```text
PJ C:\> greet --name Yves -c 2 | where { $_.message.contains("Yves") }
name   message          at
----   -------          --
Yves   Bonjour Yves !   2026-10-07 10:12:03
Yves   Bonjour Yves !   2026-10-07 10:12:03
```

### 4.4 Installation et chargement

- Au démarrage, chaque `~/.powerj/modules/*.jar` est chargé dans son propre `ModuleLayer` (isolation des dépendances entre modules).
- `mod-load <chemin.jar>` charge un module à chaud ; `mod-list` liste les modules chargés et leurs cmdlets.
- Les classes d'un module ne sont **pas** utilisables en expression Java (§3.13) : seuls ses cmdlets sont exposés. C'est le moyen prévu pour utiliser une bibliothèque externe depuis PowerJ.
- Un module invalide (nom en conflit, exception au chargement) est signalé par un avertissement ; les autres modules sont chargés normalement.

---

## 5. Architecture technique

### 5.1 Modules Maven

```text
powerj/                         (POM parent, packaging pom)
├── powerj-api/                 API publique pour les cmdlets (aucune dépendance)
├── powerj-core/                Lexer, parser, AST, résolution, évaluateur, pipeline,
│                               accès aux objets, interop Java, formatage,
│                               exécution native
├── powerj-cmdlets/             Cmdlets intégrés (ls, where)
├── powerj-shell/               REPL JLine, complétion, coloration, main
├── powerj-sample-module/       Module tiers d'exemple (greet)
└── powerj-dist/                jlink + jpackage → powerj.exe
```

Tous les modules sont des **modules JPMS** (`module-info.java`).

### 5.2 Chaîne de traitement d'une ligne

```text
ligne saisie
  → Lexer         (sealed interface Token, records)
  → Parser        (AST : sealed interface Node, records)
  → Résolution    (cmdlet / natif / interne, liaison des options)
  → Pipeline      (une étape = un thread virtuel, files bornées entre étapes)
  → Formatage     (tableau / liste) → terminal
```

### 5.3 Exécution du pipeline

- Chaque étape s'exécute dans un **thread virtuel** ; les étapes sont reliées par des **files bornées** (contre-pression : un `ls -r C:\` ne remplit pas la mémoire si l'aval est lent).
- La dernière étape s'exécute dans le fil qui reçoit Ctrl+C ; quand elle s'arrête (fin, erreur bloquante, Ctrl+C), elle ferme sa file d'entrée, ce qui arrête en cascade les étapes amont (fil interrompu, process natifs tués). Une étape qui cesse de lire (`ls | ^more` puis `q`) arrête de même l'amont. Ce cycle de vie sera confié à **Structured Concurrency** (`StructuredTaskScope`) quand l'API sera finale (elle est en preview, non utilisée sans accord du PM).
- L'objet courant `$_` d'un bloc est lié par une **Scoped Value** pendant l'évaluation.
- Les commandes natives sont lancées via `ProcessBuilder` (redirections `INHERIT`, `PIPE`) ; les natives consécutives forment un groupe lancé par `ProcessBuilder.startPipeline` (octets transmis directement) ; les objets envoyés à un natif sont écrits sur son stdin sous leur forme affichée.

### 5.4 Interopérabilité Java (`powerj-core`, `JavaClasses`, `JavaInvoker`, `FunctionalAdapter`)

- **Résolution des classes** : recherche par le chargeur de classes de la plateforme (qui ne voit pas les modules tiers), en ne gardant que les classes publiques des packages exportés par les modules `java.*` ; imports par défaut (FR-47) et imports de session ; résultats mis en cache.
- **Résolution des membres** : méthodes publiques par type et par nom mises en cache via `ClassValue`, vues à travers l'interface ou la superclasse publique exportée quand la classe concrète ne l'est pas (`List.of(…)`) ; appel par réflexion (`Method.invoke`), suffisant en v1 — les `MethodHandle` restent une optimisation possible si les appels Java deviennent un goulot.
- **Conversion des arguments** : table de conversions (FR-50) exprimée par `switch` sur les types ; choix de surcharge par score de spécificité.
- **Lambdas et références de méthode → interfaces fonctionnelles** : implémentation par `java.lang.reflect.Proxy` de la méthode abstraite unique (méthodes `default` déléguées par `InvocationHandler.invokeDefault`) ; paramètres de la lambda (ou `$_`) liés par `ScopedValue` à chaque appel.
- **Déroulage** (FR-30b) : appliqué à la sortie de chaque étape par l'exécuteur du pipeline.
- **Interceptions** (FR-58) : table des méthodes redirigées ou refusées (`System.exit`, `Runtime.halt`, `System.setOut`…), consultée à la résolution d'un appel ; vérification du jeton d'annulation (FR-57) à chaque invocation d'un bloc.

### 5.5 Dépendances

| Librairie | Usage |
|---|---|
| JLine 3 | Terminal, édition de ligne, historique, complétion, coloration (terminal Windows natif via FFM) |
| JUnit 5, AssertJ | Tests |

Toute nouvelle dépendance doit être justifiée et validée.

---

## 6. Utilisation des fonctionnalités Java modernes

Les nouveautés de Java sont utilisées **là où elles apportent un bénéfice concret** :

| Fonctionnalité | Usage dans PowerJ |
|---|---|
| **Records** | Objets de sortie (`FileEntry`, `NativeRun`), paramètres des cmdlets, tokens, nœuds d'AST, entrées d'historique. Validation dans les constructeurs compacts. |
| **Sealed interfaces** | Hiérarchies fermées : `Token`, `Node` (AST), `Resolved` (`CmdletCall` / `NativeCall` / `Builtin`), `Value`. Le compilateur garantit l'exhaustivité des traitements. |
| **Pattern matching `switch` + record patterns + `_`** | Évaluateur d'expressions, formateur, moteur de complétion : `case BinaryOp(var l, Op.GT, var r) -> …`, `case FileEntry(var name, _, _, _, true, _) -> …`. |
| **Threads virtuels** | Une étape de pipeline = un thread virtuel ; lecture des flux stdout/stderr des process natifs. |
| **Structured Concurrency** | Cycle de vie du pipeline : annulation globale sur Ctrl+C ou erreur bloquante — dès que l'API sera finale (en attendant : annulation en cascade par les files, §5.3). |
| **Scoped Values** | Contexte de session immuable par exécution, à la place de `ThreadLocal`. |
| **Stream Gatherers** | Opérations de flux sur mesure dans le pipeline (fenêtrage, `first`/`last`, dédoublonnage — utiles dès les cmdlets du backlog). |
| **FFM API** | Accès console Windows (via JLine) ; lecture de l'en-tête PE pour détecter les applications GUI, sans JNI. |
| **Sequenced Collections** | Historique (`getFirst`/`getLast`/`reversed`), colonnes ordonnées. |
| **Réflexion, `Proxy`, `ClassValue`** | Interopérabilité Java : appels de méthodes/constructeurs avec cache par type, conversion des blocs `{ }` en interfaces fonctionnelles (`MethodHandles` : optimisation possible plus tard). |
| **`ClassValue`** | Cache des métadonnées de membres par type, sans fuite de classloader (modules tiers). |
| **Module import declarations, constructeurs flexibles** | Lisibilité du code. |
| **Patterns primitifs** | Dans l'évaluateur pour les comparaisons numériques, si finalisés dans le JDK 27. |

**Règle :** une fonctionnalité encore en *preview* dans le JDK 27 n'est activée (`--enable-preview`) qu'après validation du PM. La liste ci-dessus est revérifiée contre les JEP effectivement livrés dans le JDK 27 au démarrage de l'étape 0.

---

## 7. Build et distribution

- **Maven 3.9** ; `maven-enforcer-plugin` impose Java 27 et Maven ≥ 3.9 ; `maven.compiler.release=27`.
- `mvn verify` : compilation, tests, couverture (JaCoCo).
- Module `powerj-dist` :
  1. **jlink** : runtime Java contenant **tous les modules `java.se`** (nécessaire pour que toute l'API standard soit appelable, §3.13), sans les outils de développement (`--strip-debug --no-header-files --no-man-pages`) ;
  2. **jpackage `--type app-image`** puis **`--type exe`** (WiX Toolset) : `powerj.exe` + installeur avec icône, ajout au `PATH`, entrée dans le menu Démarrer, mode console (`--win-console`).
- **CI GitHub Actions** :
  - job `build` (Linux) : `mvn verify` à chaque push et PR ;
  - job `package-windows` (`windows-latest`) : produit l'installeur et le dossier portable `powerj/` en **artefacts téléchargeables** à chaque push sur une branche de livraison.
- Lanceurs Linux/macOS (`jpackage --type app-image`) en bonus, non bloquants.

---

## 8. Configuration

Dossier utilisateur `~/.powerj/` (créé au premier lancement) :

| Fichier / dossier | Rôle |
|---|---|
| `history` | Historique des commandes (FR-09). |
| `modules/` | Jars des modules tiers (§4.4). |
| `config.properties` | `history.size=10000`, `lang=fr`, `native.prefer=find,sort`, `colors.cmdlet=green`… |
| `logs/` | Journal de diagnostic (FR-60). |
| `profile.pj` | Lignes exécutées au démarrage (affectations de variables, alias : `alias ll = ls -a`). |

Variables d'environnement lues par PowerJ :

| Variable | Rôle |
|---|---|
| `POWERJ_HOME` | Emplacement du dossier de configuration (défaut `~/.powerj`). |
| `POWERJ_NATIVE_ENCODING` | Encodage des commandes natives (FR-40b). |
| `POWERJ_NATIVE_ENCODING_<NOM>` | Encodage pour un exécutable précis (FR-40b). |

---

## 9. Exigences non fonctionnelles

| ID | Exigence |
|---|---|
| NFR-01 | Temps de démarrage : pas d'exigence en v1 (quelques secondes acceptables). L'optimisation (cache AOT du JDK, index des classes en tâche de fond) est prévue dans un second temps. |
| NFR-02 | Latence de frappe imperceptible ; complétion < 50 ms. |
| NFR-03 | Windows 10/11 x64 prioritaire ; UTF-8 de bout en bout (console en page de code 65001). |
| NFR-04 | `ls -r` sur 100 000 fichiers sans dépassement mémoire (streaming). |
| NFR-05 | Couverture de tests ≥ 80 % sur `powerj-core`. |
| NFR-06 | Aucune exception Java brute affichée à l'utilisateur hors mode debug. |
| NFR-07 | Premier appel d'une méthode Java < 50 ms ; appels suivants (cache) < 1 ms. |

---

## 10. Stratégie de test

- **Unitaires** : lexer, parser, résolution, liaison des options, évaluateur d'expressions, accès aux propriétés (records, getters, champs, `Map`), formatage, déroulage.
- **Interop Java** : appels statiques/instance/constructeurs, surcharges et varargs, conversions, blocs → interfaces fonctionnelles, classes non exportées appelées via interface publique, exceptions, règle lexicale (`java -version` vs `java.lang.Math.max(1,2)`).
- **Cmdlets** : `ls` sur une arborescence temporaire (`@TempDir`), `where` sur des flux construits.
- **Commandes natives** : tests multiplateformes avec `cmd /c echo` (Windows) / `echo` (Linux), code retour, stderr, natif → natif.
- **Complétion** : candidats attendus pour des lignes partielles.
- **Intégration REPL** : terminal JLine « dumb » piloté par script (entrées simulées, sorties vérifiées), y compris historique et Ctrl+R.
- **Modules** : chargement de `powerj-sample-module`, gestion des collisions de noms, classes du module non accessibles en expression Java.
- **Imports par défaut** : test automatique vérifiant qu'aucun nom simple n'est en double entre les packages importés par défaut (protège contre l'ajout de classes dans une future version du JDK).
- **Recette manuelle** : une fiche par étape (§11), exécutée par le PM sur l'exe produit par la CI.

---

## 11. Plan de développement itératif

Chaque étape :
- livre un **`powerj.exe` installable et testable**, produit par la CI (artefact GitHub Actions) ;
- est accompagnée d'une **fiche de recette** (scénario pas à pas pour le PM) et de tests automatisés ;
- ne démarre qu'après **validation de la recette** de l'étape précédente.

### Étape 0 — Squelette et exe

**Contenu :** structure multi-module Maven, JPMS, enforcer Java 27 / Maven 3.9, jlink + jpackage, CI Linux + Windows. Le REPL est minimal (lecture simple, `exit`).

**Recette :**
1. Télécharger l'artefact CI, installer `powerj.exe`.
2. Lancer `powerj` depuis le menu Démarrer et depuis un terminal (`PATH`).
3. Vérifier la bannière de version et le prompt `PJ C:\…> `.
4. Taper `exit` : le shell se ferme.

### Étape 1 — Édition de ligne et historique

**Contenu :** JLine, FR-01 à FR-11 (prompt, multi-ligne, Ctrl+C, Ctrl+D, édition, ↑/↓, Ctrl+R, historique persistant, `history`, `!!`, `!n`) ; superviseur et résultat `Outcome` (FR-56), restauration du terminal (FR-59), journal (FR-60).

**Recette :**
1. Taper `bonjour`, `test un`, `test deux` (affichage d'une erreur « commande inconnue » attendu).
2. ↑ trois fois : les lignes reviennent dans l'ordre inverse.
3. Ctrl+R puis `un` : `test un` est proposé.
4. Quitter, relancer : ↑ retrouve les lignes.
5. `history` liste les entrées ; `!!` ré-exécute la dernière.
6. Ctrl+C sur une ligne en cours de saisie l'efface ; Ctrl+D sur une ligne vide quitte le shell.

### Étape 2 — Commandes natives

**Contenu :** lexer/parser minimal (commandes, arguments, chaînes, variables), résolution `PATH`, exécution avec stdout/stderr hérités, flux d'erreur, `$last` (`NativeRun`), `$exit`, `$?`, affectation `$x = …` (capture des lignes), applications GUI détachées, `which`, redirections `>`, `2>`, encodage des commandes natives, navigation `cd`/`pwd`, enchaînement `;` `&&` `||`, Ctrl+C sur une commande native. FR-03, FR-04b, FR-04c, FR-13 à FR-15, FR-31, FR-32b, FR-34, FR-37 à FR-40b, FR-57 (niveaux 1-2 pour les natifs).

**Recette :**
1. `git --version` affiche la version.
2. `git log` : couleurs et pagination fonctionnent.
3. `$l = ipconfig` puis `$l[0]` affiche la première ligne.
4. `^cmd /c "exit 3"` puis `$exit` affiche `3`.
5. `git commandeinconnue` : message d'erreur en rouge.
6. `git log > log.txt` crée le fichier.
7. `notepad` : le Bloc-notes s'ouvre et le prompt revient immédiatement.
8. `which git` affiche le chemin de l'exécutable.
9. `cd C:\Windows`, `cd ..`, `cd -`, `cd ~`, `pwd` ; `cd "C:\\Program Files"`.
10. `ipconfig` dans une variable (`$l = ipconfig`) puis `$l` : les accents sont corrects sur un Windows français.
11. `^cmd /c "exit 1" || "échec"` affiche `échec` ; `git --version && "ok"` affiche la version puis `ok` ; `^cmd /c "exit 1" && "jamais"` n'affiche rien.
12. `ping -t localhost` puis Ctrl+C : `ping` s'arrête et le prompt revient.

### Étape 3 — Modèle objet et cmdlet `ls`

**Contenu :** `powerj-api` (FR : §4.2), registre des cmdlets, cmdlet `env` (FR-36b) et réglage de l'encodage par variable (FR-40b), priorité cmdlet > natif, `^`, liaison des options Unix (FR-18 à FR-20), accès aux propriétés des objets (FR-27 à FR-29 : records, getters, champs), affichage selon le type (FR-30), déroulage (FR-30b), cmdlet `ls` (FR-35), `help` (FR-45).

**Recette :**
1. `ls` affiche un tableau `name size modified dir`.
2. `ls -r --filter *.txt` liste récursivement les `.txt`.
3. `(ls)*.name` affiche les noms seuls.
4. `$f = ls` puis `$f[0].size` et `$f[0].path.parent`.
5. `ls --recurce` : erreur avec suggestion `--recurse`.
6. `help ls` et `ls --help` affichent l'aide.
7. `^ls` exécute le `ls` natif (si Git Bash installé) ; `which ls` indique `cmdlet`.
8. `env`, `env PATH`, `env --set MAVEN_OPTS=-Xmx2g` puis `env MAVEN_OPTS`, `env --unset MAVEN_OPTS`.
9. `env --append PATH C:\tools` : un outil de `C:\tools` devient exécutable et `which` le trouve.
10. `env --set POWERJ_NATIVE_ENCODING_GIT=UTF-8` puis `$l = git log --oneline` : accents corrects.

### Étape 4 — Pipeline et cmdlet `where`

**Contenu :** pipeline streaming (threads virtuels, files bornées, annulation en cascade), langage d'expression (FR-32, FR-33), littéraux d'unités (FR-19), cmdlet `where` (FR-36), natifs dans le pipeline (lignes `String`, cmdlet → natif, natif → natif), `2>&1`, Ctrl+C sur un pipeline, `--on-error`, mode non interactif (FR-04d).

**Recette :**
1. `ls -r | where { $_.size > 1mb }`.
2. `ls | where { $_.name.endsWith(".java") && !$_.dir }`.
3. `ls | where size > 10kb` (forme courte).
4. `git status --porcelain | where { $_.startsWith(" M ") }`.
5. `ipconfig | where { $_.contains("IPv4") }`.
6. `ls | ^more` : sortie paginée.
7. `ls -r C:\ | where { $_.ext == "log" }` puis Ctrl+C : arrêt immédiat.
8. `git commandeinconnue 2> err.txt` : `err.txt` contient le message.
9. `env | where { $_.name.startsWith("JAVA") }`.
10. Depuis `cmd.exe` : `powerj -c "ls | where { $_.size > 1mb }"` ; `powerj -c "^cmd /c exit 3"` puis `echo %ERRORLEVEL%` affiche 3 ; `dir /b | powerj -c "where { $_.endsWith(\".txt\") }"`.

### Étape 5 — Interopérabilité Java

**Contenu :** §3.13 (FR-46 à FR-55) : appels statiques, champs statiques, `import`, `new`, appels d'instance, surcharges et conversions, varargs, casts, blocs → interfaces fonctionnelles, exceptions, `help members` / `help <classe>` ; runtime jlink `java.se` complet ; robustesse des appels Java : annulation coopérative et abandon (FR-57), interceptions (FR-58), erreurs graves (FR-56).

**Recette :**
1. `java.util.List.of("apple", "banana", "orange") | where { $_.contains("b") }` affiche `banana`.
2. `$l = java.util.List.of("apple", "banana")` puis `$l.size()` affiche `2`.
3. `Math.max(3, 7)` et `java.lang.Math.PI`.
4. `LocalDate.now().plusDays(10).dayOfWeek` (sans import : `java.time` est importé par défaut) ; `import java.security.*` puis `MessageDigest.getInstance("SHA-256")`.
5. `new java.io.File("C:\\Windows").listFiles() | where { $_.directory }`.
6. `$l.stream().map({ $_.toUpperCase() }).toList()`.
7. `String.format("%s-%05d", "id", 42)` (varargs + conversion).
8. `Integer.parseInt("x")` : erreur lisible `NumberFormatException`, pile visible avec `--debug`.
9. `java -version` lance toujours le `java` natif (s'il est installé).
10. `help members $l` et `help java.util.List`.
11. `Stream.iterate(0, { $_ + 1 }).forEach({ $_ })` puis Ctrl+C : le prompt revient.
12. `System.exit(0)` : le shell se ferme proprement (historique sauvegardé) ; `System.setOut(null)` : refusé avec un message.
13. `new ArrayList().addAll(Collections.nCopies(2000000000, "x"))` : erreur mémoire, le shell reste utilisable.

### Étape 5b — Alignement Java

Livrée avec l'étape 5 (même PR, même exe).

**Contenu :** FR-33b (lambdas `f ->` et `(a, b) ->`, lambdas sans accolades dans les appels Java, références de méthode `Classe::méthode`, `$x::méthode`, `Classe::new`, blocs de texte `"""…"""`), booléens stricts (`where`, `&&`, `||`, `!`, ternaire), retrait de `$a` / `$b` / `$args`, cmdlet `map` (FR-36c). Mise à jour des recettes 4 et 5 et des exemples de la spécification.

**Recette :**
1. `ls -r | where { f -> f.size > 1mb && !f.dir }`.
2. `ls -r | map { f -> f.name + " : " + f.name.length() }` ; fonctionne aussi quand `ls` ne renvoie qu'un fichier.
3. `ls | map FileEntry::name` et `env | map EnvVar::name`.
4. `$l = List.of("apple", "banana", "kiwi")` puis `$l.stream().filter(s -> s.length() > 4).map(String::toUpperCase).toList()`.
5. `$m = new ArrayList($l); $m.sort((a, b) -> a.length() - b.length()); $m`.
6. `$l.stream().map(Path::of).toList()` et `Stream.of("a", "b").map(StringBuilder::new).toList()`.
7. `ls | where { f -> f.name }` : erreur non bloquante `le bloc doit renvoyer un booléen` pour chaque objet.
8. `$m.sort({ $a.length() - $b.length() })` : erreur claire indiquant d'écrire `(a, b) -> …`.
9. `$min = 1kb; ls | where { f -> f.size > $min }` (variables du shell dans une lambda).
10. Bloc de texte multi-ligne : `$t = """` … `"""` puis `$t.lines().count()`.

### Étape 6 — Autocomplétion Tab et coloration

**Contenu :** FR-08, FR-21 à FR-26, FR-24b (complétion Java).

**Recette :**
1. `l<Tab>` propose `ls [pj]` et les natifs commençant par `l`.
2. `ls --<Tab>` propose les options ; `ls -r --<Tab>` ne repropose plus `--recurse`.
3. `ls C:\Pro<Tab>` complète `C:\Program Files\`.
4. `ls | where { $_.<Tab>` propose `name size modified path dir ext`.
5. `$f = ls` puis `$f[0].<Tab>`.
6. `^no<Tab>` propose `notepad`.
7. `java.util.Li<Tab>`, `List.<Tab>`, `$l.<Tab>`, `new java.io.F<Tab>` (complétion Java avec signatures).
8. Vérifier les couleurs : cmdlet, natif, commande inconnue, chaîne, variable.

### Étape 7 — Modules tiers

**Contenu :** `powerj-api` publiable seul, chargement de `~/.powerj/modules/*.jar` dans des `ModuleLayer` isolés, `mod-load`, `mod-list`, gestion des collisions (FR-17), module d'exemple `greet` (§4.3).

**Recette :**
1. Copier `greet.jar` (artefact CI) dans `~/.powerj/modules/`, relancer.
2. `greet --name Yves -c 2` affiche deux objets.
3. `gr<Tab>` et `greet --<Tab>` complètent.
4. `greet -n Yves | where { $_.message.contains("Yves") }`.
5. `help greet` affiche l'aide générée.
6. `mod-list` liste le module.
7. `new com.example.greet.Greeting(...)` : erreur « classe inconnue » (les classes des modules ne sont pas exposées).

### Après ces étapes

Les cmdlets du backlog (§12.3) sont ajoutés **un par mini-itération**, chacune avec une courte spécification (options, record de sortie, CA), un exe et une fiche de recette. La v2 introduira le scripting (`if`, `foreach`, fonctions, fichiers `.pj`).

---

## 12. Annexes

### 12.1 Grammaire (EBNF, v1)

```ebnf
ligne         = import | [ affectation | pipeline ] [ redirection* ] ;
import        = "import" nom_qualifie [ ".*" ] ;
affectation   = variable "=" pipeline ;
pipeline      = etape { "|" etape } ;
etape         = expr_java | commande | "(" pipeline ")" ;
(* expr_java est tenté en premier : nom_qualifie collé à "(", "new",
   cast, ou nom désignant une classe/un champ statique connu (FR-46) *)
expr_java     = postfixe ;
commande      = [ "^" ] nom { argument } ;
argument      = option | valeur | bloc ;
option        = "-" lettre { lettre } | "--" ident [ "=" valeur ] | "--" ;
valeur        = chaine | nombre | unite | variable_acces | liste | mot ;
bloc          = "{" ( lambda | expression ) "}" ;
lambda        = params "->" expression ;                 (* FR-33b *)
params        = ident | "(" [ ident { "," ident } ] ")" ;
redirection   = ( ">" | ">>" | "2>" | "2>>" ) chemin | "2>&1" ;

expression    = ou [ "?" expression ":" expression ] ;
ou            = et { "||" et } ;
et            = egalite { "&&" egalite } ;
egalite       = comparaison [ ( "==" | "!=" ) comparaison ] ;
comparaison   = somme [ ( "<" | "<=" | ">" | ">=" ) somme ] ;
somme         = produit { ( "+" | "-" ) produit } ;
produit       = unaire { ( "*" | "/" | "%" ) unaire } ;
unaire        = [ "-" | "!" ] [ cast ] postfixe ;
cast          = "[" nom_qualifie "]" ;
postfixe      = primaire { "." ident [ arguments ] | "*." ident [ arguments ] | "::" ident | "[" expression "]" } ;   (* "*." : chaque élément *)
arguments     = "(" [ arg_java { "," arg_java } ] ")" ;   (* sans espace avant "(" *)
arg_java      = lambda | expression | bloc ;              (* lambda, bloc, ref_methode → interface fonctionnelle *)
primaire      = litteral | variable | "(" pipeline ")" | liste | ref_methode
              | "new" nom_qualifie arguments
              | nom_qualifie [ arguments ] ;              (* classe, champ ou méthode statique *)
nom_qualifie  = ident { "." ident } ;
ref_methode   = ( nom_qualifie | variable ) "::" ( ident | "new" ) ;
variable_acces= postfixe ;
variable      = "$" ( ident | "_" | "?" ) ;
liste         = "[" [ expression { "," expression } ] "]" ;
litteral      = chaine | caractere | nombre | unite | "true" | "false" | "null" | "now" ;
unite         = nombre ( "b" | "kb" | "mb" | "gb" | "tb" | "s" | "m" | "h" | "d" ) ;
chaine        = '"' { car | echappement | "$" ident | "$(" pipeline ")" } '"' | bloc_texte ;
echappement   = "\\" ( "\\" | '"' | "n" | "t" | "r" | "$" | "u" hex hex hex hex ) ;
caractere     = "'" ( car | echappement ) "'" ;
mot           = { car_sans_espace } ;   (* argument non quoté : pris tel quel, "\" littéral *)
```

### 12.2 Sessions d'exemple (périmètre `ls` + `where` + natifs)

```text
PJ C:\dev> ls -r --filter *.java | where { $_.modified > now - 1d }
PJ C:\dev> $gros = ls -r | where size > 100mb
PJ C:\dev> $gros.path
PJ C:\dev> git branch --list | where { $_.contains("feature") }
PJ C:\dev> ls -d | where { $_.name.matches("^[a-m].*") } | ^more
PJ C:\dev> mvn -q verify; $exit
PJ C:\dev> code .                       # application graphique, rend la main
PJ C:\dev> java.util.List.of("apple", "banana", "orange") | where { $_.contains("b") }
PJ C:\dev> ls -r --filter *.log | where { Files.size($_.path) > 10mb }
PJ C:\dev> (ls)*.name.stream().map({ $_.toUpperCase() }).sorted().toList()
```

### 12.3 Backlog des cmdlets (hors périmètre actuel)

| Cmdlet | Description | Record de sortie envisagé |
|---|---|---|
| `cat` | Lire un fichier ligne par ligne | `String` |
| `find` | Recherche avancée (`--name --since --size --type`) | `FileEntry` |
| `cp`, `mv`, `rm`, `mkdir`, `touch` | Opérations sur fichiers | `FileEntry` |
| `ps`, `kill` | Processus | `ProcessEntry` |
| `select` | Projection d'attributs, `--expand` | `Row` |
| `sort` | Tri (`--desc`) | inchangé |
| `first`, `last` | N premiers / derniers | inchangé |
| `group` | Regroupement | `Group<T>` |
| `count`, `sum`, `avg`, `min`, `max` | Agrégats | `Stats` |
| `uniq` | Dédoublonnage | inchangé |
| `tee` | Copie dans une variable | inchangé |
| `table`, `tree` | Formats d'affichage | — |
| `from-json`, `to-json`, `from-csv`, `to-csv` | Conversions | `Map` / `String` |
| `http`, `ping` | Réseau | `HttpResponse`, `PingResult` |
| `env` | Variables d'environnement | `EnvVar` |
| `open` | Ouvrir avec l'application associée | — |

Vision cible une fois le backlog réalisé :

```text
find src --name *.java --since 7d | where { $_.size > 2kb } | group { $_.path.parent }
    | sort count --desc | first 5 | to-json rapport.json
```

### 12.4 Correspondance PowerShell → PowerJ

| PowerShell | PowerJ |
|---|---|
| `Get-ChildItem -Recurse -Filter *.java` | `ls -r --filter *.java` |
| `Where-Object { $_.Length -gt 1MB }` | `where { $_.size > 1mb }` ou `where { f -> f.size > 1mb }` |
| `ForEach-Object { $_.Name }` | `map { f -> f.name }` ou `map FileEntry::name` |
| `$_.Name -like '*.txt'` | `$_.name.endsWith(".txt")` |
| `-and`, `-or`, `-not` | `&&`, `\|\|`, `!` |
| `$_ -match 'IPv4'` | `$_.contains("IPv4")` / `$_.matches(".*IPv4.*")` |
| `$LASTEXITCODE` | `$exit` |
| `& "C:\outil.exe"` | `^"C:\outil.exe"` |
| `Get-Member` | `help members` |
| `[System.Math]::Max(3, 7)` | `Math.max(3, 7)` |
| `[System.IO.File]::ReadAllLines("a.txt")` | `java.nio.file.Files.readAllLines(Path.of("a.txt"))` |
| `New-Object System.Text.StringBuilder` | `new StringBuilder()` |
| `using namespace System.Security` | `import java.security.*` (java.io, java.util, java.nio.file… sont importés par défaut) |
| `[int] "42"` | `[int] "42"` |

### 12.5 Questions ouvertes pour le PM

1. **Couleurs et thème** : faut-il un thème clair / sombre configurable dès la v1 ?
2. **Signature de code** de l'exe et de l'installeur (évite l'avertissement SmartScreen) : certificat disponible ?
3. **Nom de l'installeur et éditeur** affichés dans « Programmes et fonctionnalités ».
4. **Dictionnaires littéraux** (`{k: v}`) : utiles en v1 ou reportés ? (Avec l'interop, `java.util.Map.of("k", "v")` couvre déjà le besoin.)
5. **Licence** du module `powerj-api` pour les auteurs de modules tiers (même licence que le projet ?).
6. **Interop et effets de bord** : faut-il une option de configuration pour désactiver l'interop Java (`interop.enabled=false`) dans des contextes restreints ?
