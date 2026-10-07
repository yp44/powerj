# PowerJ — Spécification fonctionnelle et technique

| | |
|---|---|
| **Version du document** | 0.2 (ajout de l'interopérabilité Java) |
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
PJ C:\dev\powerj> ls -r --filter *.java | where { $_.size > 10kb and $_.modified > now - 7d }

name               size      modified              dir
----               ----      --------              ---
Parser.java        14,2 KB   2026-10-05 18:12      false
Evaluator.java     11,8 KB   2026-10-06 09:40      false

PJ C:\dev\powerj> git status --porcelain | where { $_ like ' M *' }
 M src/core/Parser.java
 M src/core/Evaluator.java

PJ C:\dev\powerj> java.util.List.of("apple", "banana", "orange") | where { $_ like 'b*' }
banana

PJ C:\dev\powerj> import java.time.*
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
- **Périmètre cmdlets volontairement réduit à 2 cmdlets (`ls` et `where`)** ; le reste du catalogue est au backlog (§12.3).

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

**FR-02 — Saisie multi-ligne.** Si une ligne se termine par `|` ou `\`, ou si une accolade/guillemet reste ouvert, le shell affiche un prompt de continuation `>> ` et attend la suite.
- CA : `ls |` + Entrée affiche `>> ` ; `where { $_.dir }` + Entrée exécute le pipeline complet.

**FR-03 — Interruption.** **Ctrl+C** pendant l'exécution annule la commande en cours (cmdlets et process natifs) et rend la main sans quitter le shell. Ctrl+C sur une ligne en cours de saisie l'efface.
- CA : `ls -r C:\` puis Ctrl+C rend le prompt en moins de 500 ms.

**FR-04 — Sortie.** `exit` (ou Ctrl+D sur ligne vide) quitte le shell en sauvegardant l'historique. `exit <code>` quitte avec ce code retour.

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
1. mot-clé interne du REPL (`exit`, `history`, `help`, `which`) ;
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
- Sur une collection, l'accès à une propriété s'applique à chaque élément (`(ls).name` → liste des noms), sauf si la propriété existe sur la collection elle-même (`$l.empty` → `isEmpty()`).
- Indexation : `$f[0]`, `$f[-1]` sur `List`, tableau ou `String` ; `$m['clé']` sur `Map`.
- Propriété inexistante → erreur `FileEntry n'a pas de propriété 'siz' (propriétés : name, size, …)`.

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
PJ> java.util.List.of("apple", "banana", "orange") | where { $_ like 'b*' }
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

**FR-32 — Littéraux.** Chaînes `'brutes'` et `"interpolées $var $(expr)"`, entiers, décimaux, `true`/`false`/`null`, tailles et durées (FR-19), `now`, listes `[1, 2, 3]`.

**FR-33 — Opérateurs dans `{ }`.**

| Catégorie | Opérateurs |
|---|---|
| Comparaison | `==  !=  <  <=  >  >=` |
| Motifs | `like` (joker `*` `?`, insensible à la casse), `=~` (expression régulière) |
| Logique | `and  or  not` |
| Arithmétique | `+  -  *  /  %` (y compris `Instant - Duration`) |
| Appartenance | `in` (`$_.ext in ['java', 'kt']`) |

Comparaisons de chaînes insensibles à la casse par défaut.

**FR-34 — Redirections (hors blocs).** `> fichier` (écrase), `>> fichier` (ajoute) pour le flux de sortie ; `2> fichier`, `2>&1` pour le flux d'erreur. Les objets redirigés vers un fichier sont écrits sous leur forme affichée.

### 3.9 Cmdlets du périmètre actuel

Seuls **deux cmdlets** sont dans le périmètre de ce document. Ils couvrent à eux seuls les mécanismes centraux : production d'objets records, accès aux attributs, pipeline, expressions, mélange avec les commandes natives.

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
- `(ls).name` affiche uniquement les noms ;
- `$f = ls; $f[0].size` affiche la taille du premier élément ;
- `^ls` exécute le `ls` natif s'il existe (Git Bash, WSL…), sinon erreur `commande native introuvable`.

#### FR-36 — `where` : filtrer des objets

```text
where { <expression> }
where <attribut> <opérateur> <valeur>        # forme courte
```

Évalue l'expression pour chaque objet reçu (`$_`) et ne laisse passer que ceux pour lesquels elle est vraie. Fonctionne sur les records, les scalaires et donc les **lignes `String` produites par une commande native**. La forme courte `where size > 1mb` équivaut à `where { $_.size > 1mb }`.

Vérité : `false`, `null`, `0`, `""` et liste vide sont faux ; tout le reste est vrai. Une erreur d'évaluation sur un objet produit une erreur non bloquante et l'objet est ignoré.

CA :
- `ls -r | where { $_.size > 1mb }` ;
- `ls | where { $_.name like '*.java' and not $_.dir }` ;
- `ls | where ext in ['png', 'jpg']` ;
- `git status --porcelain | where { $_ like ' M *' }` ;
- `ipconfig | where { $_ =~ 'IPv4' }`.

### 3.10 Commandes natives

**FR-37 — Principe : les flux restent des flux.** Une commande native n'est **pas** encapsulée dans un objet : sa sortie standard et sa sortie d'erreur sont traitées comme des flux, à la manière d'un shell classique.

| Situation | stdout | stderr |
|---|---|---|
| **Dernière étape** au REPL (`git log`) | Hérité directement du terminal : couleurs, pagination, programmes interactifs (`vim`, `ssh`, `python`) fonctionnent. | Hérité du terminal. |
| **Étape suivie d'un cmdlet** (`git status \| where …`) | Converti en **flux de lignes `String`** (décodage UTF-8 ou page de code console), en streaming. | **Flux d'erreur PowerJ** (affiché en rouge), jamais mélangé aux objets. |
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

**FR-40 — Arguments.** Les arguments sont passés tels quels après expansion des variables et des jokers (expansion des jokers sur les chemins existants, désactivable en mettant l'argument entre guillemets simples). Sous Windows, la ligne de commande est construite selon les règles de quoting de `CommandLineToArgvW`.

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

**FR-47 — Imports.** `import java.util.*` ou `import java.time.LocalDate` rend les classes utilisables par leur nom simple pour le reste de la session (ou depuis `profile.pj`). `java.lang.*` est importé par défaut. `import` sans argument liste les imports actifs. Un nom simple ambigu (deux imports) produit une erreur listant les candidats.

```text
PJ> import java.time.*
PJ> LocalDate.now().plusDays(10).dayOfWeek
FRIDAY
PJ> Math.max(3, 7)
7
```

**FR-48 — Instanciation.** `new Classe(args)` appelle un constructeur public : `new java.io.File("C:\\temp")`, `new StringBuilder("ab").reverse()`.

**FR-49 — Appels d'instance.** Toute valeur expose ses méthodes publiques : `$l.size()`, `"abc".toUpperCase().length()`, `$f[0].path.toFile().length()`, `(ls)[0].modified.atZone(ZoneId.systemDefault()).year`. Les propriétés (FR-28) et les appels se combinent librement. Un appel de méthode peut apparaître partout où une expression est attendue, y compris dans les blocs `{ }` de `where`.

**FR-50 — Résolution des surcharges et conversions.**
- Les valeurs PowerJ sont converties vers les types de paramètres : entier → `int`/`long`/`short`/`byte`/`Integer`/`Long`/`BigInteger` (si sans perte) ; décimal → `double`/`float`/`BigDecimal` ; chaîne → `String`, `CharSequence`, `char` (si 1 caractère), `Path`, `File`, enum (par nom) ; liste PowerJ → `List`, `Collection`, tableau ; taille (FR-19) → `long` ; durée → `Duration`.
- **varargs** supportés (`List.of("a", "b", "c")`, `String.format("%s-%s", 1, 2)`).
- Parmi les surcharges applicables, la plus spécifique est choisie (règles proches de JLS §15.12) ; en cas d'ambiguïté, erreur listant les signatures candidates ; aucun candidat → erreur listant les surcharges existantes.
- Une conversion explicite est possible par cast : `[long] 5`, `[java.util.ArrayList] $l` (vérification à l'exécution).

**FR-51 — Blocs `{ }` vers interfaces fonctionnelles.** Un bloc passé à un paramètre dont le type est une interface fonctionnelle (`Predicate`, `Function`, `Comparator`, `Runnable`, `Supplier`…) est converti automatiquement :
- 0 paramètre → aucun argument ; 1 paramètre → `$_` ; 2 paramètres → `$a` et `$b` ; au-delà → `$args[0]`, `$args[1]`… ;
- la valeur du bloc est convertie vers le type de retour de la méthode abstraite (FR-50).

```text
PJ> $l = List.of("apple", "banana", "kiwi")
PJ> $l.stream().filter({ $_.length() > 4 }).map({ $_.toUpperCase() }).toList()
APPLE
BANANA
PJ> $m = new java.util.ArrayList($l); $m.sort({ $a.length() - $b.length() }); $m
kiwi
apple
banana
```

**FR-52 — Périmètre et sécurité.**
- Accessibles : classes et membres **`public`** des packages **exportés** par les modules du runtime (`java.se` complet, cf. §7) et par les modules tiers chargés (§4.4).
- Pas d'accès réflexif forcé (`setAccessible`), ni aux packages internes (`jdk.internal.*`, `sun.*`).
- Les méthodes `default` des interfaces et les méthodes héritées sont accessibles normalement ; l'appel passe par l'interface publique quand la classe d'implémentation n'est pas exportée (ex. `List.of(...)` renvoie une classe interne, ses méthodes sont appelées via `java.util.List`).

**FR-53 — Exceptions.** Une exception levée par un appel Java est une **erreur bloquante** affichée sous forme courte : `java.lang.NumberFormatException : For input string: "x"` (pile complète en mode `--debug`). L'objet exception est conservé dans `$errors` (`$errors[0].cause`, `$errors[0].stackTrace`).

**FR-54 — Introspection.**
- `help members $x` : méthodes publiques (avec signatures), propriétés dérivées (composants de record, getters, champs) du type réel de `$x`.
- `help java.util.List` (ou `help List` après import) : constructeurs, méthodes statiques et d'instance, champs.
- `$x.getClass()` reste disponible.

**FR-55 — Exemples de session.**

```text
PJ> import java.nio.file.*
PJ> Files.readAllLines(Path.of("notes.txt")) | where { $_ like '*TODO*' }
PJ> Files.size(Path.of("gros.iso")) / 1mb
PJ> new java.io.File("C:\\Windows").listFiles() | where { $_.directory and $_.name like 'S*' }
PJ> java.util.UUID.randomUUID()
PJ> java.net.InetAddress.getLocalHost().hostAddress
PJ> String.join(", ", (ls).name)
PJ> (ls -r | where size > 1mb).size()
```

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
PJ C:\> greet --name Yves -c 2 | where { $_.message like '*Yves*' }
name   message          at
----   -------          --
Yves   Bonjour Yves !   2026-10-07 10:12:03
Yves   Bonjour Yves !   2026-10-07 10:12:03
```

### 4.4 Installation et chargement

- Au démarrage, chaque `~/.powerj/modules/*.jar` est chargé dans son propre `ModuleLayer` (isolation des dépendances entre modules).
- `mod-load <chemin.jar>` charge un module à chaud ; `mod-list` liste les modules chargés et leurs cmdlets.
- Les classes publiques exportées par un module chargé sont aussi utilisables en expression Java (§3.13) : `import com.example.greet.*`.
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
- L'ensemble des étapes est piloté par **Structured Concurrency** : une erreur bloquante ou Ctrl+C annule toutes les étapes et tue les process natifs.
- Le contexte de session (répertoire courant, variables, terminal, configuration) est transmis par **Scoped Values**.
- Les commandes natives sont lancées via `ProcessBuilder` (redirections `INHERIT`, `PIPE` ou pipeline natif → natif via `ProcessBuilder.startPipeline`).

### 5.4 Interopérabilité Java (`powerj-core`, package `interop`)

- **Résolution des classes** : index des packages exportés construit au démarrage depuis `ModuleLayer.boot()` et les couches des modules tiers (paresseux, en tâche de fond) ; imports de session.
- **Résolution des membres** : métadonnées par type (méthodes publiques, propriétés dérivées, champs) mises en cache via `ClassValue` ; appels via `MethodHandle` obtenus par `MethodHandles.publicLookup()`, en remontant à l'interface ou la superclasse publique exportée quand la classe concrète ne l'est pas.
- **Conversion des arguments** : table de conversions (FR-50) exprimée par `switch` sur les types ; choix de surcharge par score de spécificité.
- **Blocs → interfaces fonctionnelles** : adaptation via `MethodHandleProxies.asInterfaceInstance` (ou `LambdaMetafactory` pour les interfaces fréquentes du JDK).
- **Déroulage** (FR-30b) : appliqué à la sortie de chaque étape par l'exécuteur du pipeline.

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
| **Structured Concurrency** | Cycle de vie du pipeline : annulation globale sur Ctrl+C ou erreur bloquante. |
| **Scoped Values** | Contexte de session immuable par exécution, à la place de `ThreadLocal`. |
| **Stream Gatherers** | Opérations de flux sur mesure dans le pipeline (fenêtrage, `first`/`last`, dédoublonnage — utiles dès les cmdlets du backlog). |
| **FFM API** | Accès console Windows (via JLine) ; lecture de l'en-tête PE pour détecter les applications GUI, sans JNI. |
| **Sequenced Collections** | Historique (`getFirst`/`getLast`/`reversed`), colonnes ordonnées. |
| **`MethodHandles`, `MethodHandleProxies`, `LambdaMetafactory`** | Interopérabilité Java : appels de méthodes/constructeurs rapides et mis en cache, conversion des blocs `{ }` en interfaces fonctionnelles. |
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
| `profile.pj` | Lignes exécutées au démarrage (affectations de variables, alias : `alias ll = ls -a`). |

Le dossier peut être déplacé via la variable d'environnement `POWERJ_HOME`.

---

## 9. Exigences non fonctionnelles

| ID | Exigence |
|---|---|
| NFR-01 | Démarrage jusqu'au prompt < 1 s sur un poste standard (AOT cache / CDS du JDK). |
| NFR-02 | Latence de frappe imperceptible ; complétion < 50 ms. |
| NFR-03 | Windows 10/11 x64 prioritaire ; UTF-8 de bout en bout (console en page de code 65001). |
| NFR-04 | `ls -r` sur 100 000 fichiers sans dépassement mémoire (streaming). |
| NFR-05 | Couverture de tests ≥ 80 % sur `powerj-core`. |
| NFR-06 | Aucune exception Java brute affichée à l'utilisateur hors mode debug. |
| NFR-07 | Taille de l'installeur < 80 Mo (runtime `java.se` complet). |
| NFR-08 | Premier appel d'une méthode Java < 50 ms ; appels suivants (cache) < 1 ms. |

---

## 10. Stratégie de test

- **Unitaires** : lexer, parser, résolution, liaison des options, évaluateur d'expressions, accès aux propriétés (records, getters, champs, `Map`), formatage, déroulage.
- **Interop Java** : appels statiques/instance/constructeurs, surcharges et varargs, conversions, blocs → interfaces fonctionnelles, classes non exportées appelées via interface publique, exceptions, règle lexicale (`java -version` vs `java.lang.Math.max(1,2)`).
- **Cmdlets** : `ls` sur une arborescence temporaire (`@TempDir`), `where` sur des flux construits.
- **Commandes natives** : tests multiplateformes avec `cmd /c echo` (Windows) / `echo` (Linux), code retour, stderr, natif → natif.
- **Complétion** : candidats attendus pour des lignes partielles.
- **Intégration REPL** : terminal JLine « dumb » piloté par script (entrées simulées, sorties vérifiées), y compris historique et Ctrl+R.
- **Modules** : chargement de `powerj-sample-module`, gestion des collisions de noms, import de ses classes en expression Java.
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

**Contenu :** JLine, FR-01 à FR-11 (prompt, multi-ligne, Ctrl+C, édition, ↑/↓, Ctrl+R, historique persistant, `history`, `!!`, `!n`).

**Recette :**
1. Taper `bonjour`, `test un`, `test deux` (affichage d'une erreur « commande inconnue » attendu).
2. ↑ trois fois : les lignes reviennent dans l'ordre inverse.
3. Ctrl+R puis `un` : `test un` est proposé.
4. Quitter, relancer : ↑ retrouve les lignes.
5. `history` liste les entrées ; `!!` ré-exécute la dernière.

### Étape 2 — Commandes natives

**Contenu :** lexer/parser minimal (commandes, arguments, chaînes, variables), résolution `PATH`, exécution avec stdout/stderr hérités, flux d'erreur, `$last` (`NativeRun`), `$exit`, `$?`, affectation `$x = …` (capture des lignes), applications GUI détachées, `which`, redirections `>`, `2>`. FR-13 à FR-15, FR-31, FR-34, FR-37 à FR-40.

**Recette :**
1. `git --version` affiche la version.
2. `git log` : couleurs et pagination fonctionnent.
3. `$l = ipconfig` puis `$l[0]` affiche la première ligne.
4. `^cmd /c "exit 3"` puis `$exit` affiche `3`.
5. `git commandeinconnue` : message d'erreur en rouge.
6. `git log > log.txt` crée le fichier.
7. `notepad` : le Bloc-notes s'ouvre et le prompt revient immédiatement.
8. `which git` affiche le chemin de l'exécutable.

### Étape 3 — Modèle objet et cmdlet `ls`

**Contenu :** `powerj-api` (FR : §4.2), registre des cmdlets, priorité cmdlet > natif, `^`, liaison des options Unix (FR-18 à FR-20), accès aux propriétés des objets (FR-27 à FR-29 : records, getters, champs), affichage selon le type (FR-30), déroulage (FR-30b), cmdlet `ls` (FR-35), `help` (FR-45).

**Recette :**
1. `ls` affiche un tableau `name size modified dir`.
2. `ls -r --filter *.txt` liste récursivement les `.txt`.
3. `(ls).name` affiche les noms seuls.
4. `$f = ls` puis `$f[0].size` et `$f[0].path.parent`.
5. `ls --recurce` : erreur avec suggestion `--recurse`.
6. `help ls` et `ls --help` affichent l'aide.
7. `^ls` exécute le `ls` natif (si Git Bash installé) ; `which ls` indique `cmdlet`.

### Étape 4 — Pipeline et cmdlet `where`

**Contenu :** pipeline streaming (threads virtuels, Structured Concurrency, files bornées), langage d'expression (FR-32, FR-33), littéraux d'unités (FR-19), cmdlet `where` (FR-36), natifs dans le pipeline (lignes `String`, cmdlet → natif, natif → natif), `2>&1`, Ctrl+C sur un pipeline, `--on-error`.

**Recette :**
1. `ls -r | where { $_.size > 1mb }`.
2. `ls | where { $_.name like '*.java' and not $_.dir }`.
3. `ls | where size > 10kb` (forme courte).
4. `git status --porcelain | where { $_ like ' M *' }`.
5. `ipconfig | where { $_ =~ 'IPv4' }`.
6. `ls | ^more` : sortie paginée.
7. `ls -r C:\ | where { $_.ext == 'log' }` puis Ctrl+C : arrêt immédiat.
8. `git commandeinconnue 2> err.txt` : `err.txt` contient le message.

### Étape 5 — Interopérabilité Java

**Contenu :** §3.13 (FR-46 à FR-55) : appels statiques, champs statiques, `import`, `new`, appels d'instance, surcharges et conversions, varargs, casts, blocs → interfaces fonctionnelles, exceptions, `help members` / `help <classe>` ; runtime jlink `java.se` complet.

**Recette :**
1. `java.util.List.of("apple", "banana", "orange") | where { $_ like 'b*' }` affiche `banana`.
2. `$l = java.util.List.of("apple", "banana")` puis `$l.size()` affiche `2`.
3. `Math.max(3, 7)` et `java.lang.Math.PI`.
4. `import java.time.*` puis `LocalDate.now().plusDays(10).dayOfWeek`.
5. `new java.io.File("C:\\Windows").listFiles() | where { $_.directory }`.
6. `$l.stream().map({ $_.toUpperCase() }).toList()`.
7. `String.format("%s-%05d", "id", 42)` (varargs + conversion).
8. `Integer.parseInt("x")` : erreur lisible `NumberFormatException`, pile visible avec `--debug`.
9. `java -version` lance toujours le `java` natif (s'il est installé).
10. `help members $l` et `help java.util.List`.

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
4. `greet -n Yves | where { $_.message like '*Yves*' }`.
5. `help greet` affiche l'aide générée.
6. `mod-list` liste le module.
7. `import com.example.greet.*` puis `new Greeting("Yves", "Salut", java.time.Instant.now())`.

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
bloc          = "{" expression "}" ;
redirection   = ( ">" | ">>" | "2>" | "2>>" ) chemin | "2>&1" ;

expression    = ou ;
ou            = et { "or" et } ;
et            = non { "and" non } ;
non           = [ "not" ] comparaison ;
comparaison   = somme [ ( "==" | "!=" | "<" | "<=" | ">" | ">=" | "like" | "=~" | "in" ) somme ] ;
somme         = produit { ( "+" | "-" ) produit } ;
produit       = unaire { ( "*" | "/" | "%" ) unaire } ;
unaire        = [ "-" ] [ cast ] postfixe ;
cast          = "[" nom_qualifie "]" ;
postfixe      = primaire { "." ident [ arguments ] | "[" expression "]" } ;
arguments     = "(" [ arg_java { "," arg_java } ] ")" ;   (* sans espace avant "(" *)
arg_java      = expression | bloc ;                       (* bloc → interface fonctionnelle *)
primaire      = litteral | variable | "(" pipeline ")" | liste
              | "new" nom_qualifie arguments
              | nom_qualifie [ arguments ] ;              (* classe, champ ou méthode statique *)
nom_qualifie  = ident { "." ident } ;
variable_acces= postfixe ;
variable      = "$" ( ident | "_" | "?" ) ;
liste         = "[" [ expression { "," expression } ] "]" ;
litteral      = chaine | nombre | unite | "true" | "false" | "null" | "now" ;
unite         = nombre ( "b" | "kb" | "mb" | "gb" | "tb" | "s" | "m" | "h" | "d" ) ;
chaine        = "'" { car } "'" | '"' { car | "$" ident | "$(" pipeline ")" } '"' ;
```

### 12.2 Sessions d'exemple (périmètre `ls` + `where` + natifs)

```text
PJ C:\dev> ls -r --filter *.java | where { $_.modified > now - 1d }
PJ C:\dev> $gros = ls -r | where size > 100mb
PJ C:\dev> $gros.path
PJ C:\dev> git branch --list | where { $_ like '*feature*' }
PJ C:\dev> ls -d | where { $_.name =~ '^[a-m]' } | ^more
PJ C:\dev> mvn -q verify; $exit
PJ C:\dev> code .                       # application graphique, rend la main
PJ C:\dev> java.util.List.of("apple", "banana", "orange") | where { $_ like 'b*' }
PJ C:\dev> import java.nio.file.*
PJ C:\dev> ls -r --filter *.log | where { Files.size($_.path) > 10mb }
PJ C:\dev> (ls).name.stream().map({ $_.toUpperCase() }).sorted().toList()
```

### 12.3 Backlog des cmdlets (hors périmètre actuel)

| Cmdlet | Description | Record de sortie envisagé |
|---|---|---|
| `cd`, `pwd` | Changer / afficher le répertoire courant (internes au début) | `Location` |
| `cat` | Lire un fichier ligne par ligne | `String` |
| `find` | Recherche avancée (`--name --since --size --type`) | `FileEntry` |
| `cp`, `mv`, `rm`, `mkdir`, `touch` | Opérations sur fichiers | `FileEntry` |
| `ps`, `kill` | Processus | `ProcessEntry` |
| `select` | Projection d'attributs, `--expand` | `Row` |
| `sort` | Tri (`--desc`) | inchangé |
| `first`, `last` | N premiers / derniers | inchangé |
| `group` | Regroupement | `Group<T>` |
| `count`, `sum`, `avg`, `min`, `max` | Agrégats | `Stats` |
| `each` | Transformation par bloc | variable |
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
| `Where-Object { $_.Length -gt 1MB }` | `where { $_.size > 1mb }` |
| `$_.Name -like '*.txt'` | `$_.name like '*.txt'` |
| `-and`, `-or`, `-not` | `and`, `or`, `not` |
| `$LASTEXITCODE` | `$exit` |
| `& "C:\outil.exe"` | `^"C:\outil.exe"` |
| `Get-Member` | `help members` |
| `[System.Math]::Max(3, 7)` | `Math.max(3, 7)` |
| `[System.IO.File]::ReadAllLines("a.txt")` | `java.nio.file.Files.readAllLines(Path.of("a.txt"))` |
| `New-Object System.Text.StringBuilder` | `new StringBuilder()` |
| `using namespace System.IO` | `import java.io.*` |
| `[int] "42"` | `[int] "42"` |

### 12.5 Questions ouvertes pour le PM

1. **Couleurs et thème** : faut-il un thème clair / sombre configurable dès la v1 ?
2. **`cd` / `pwd`** : ils sont indispensables pour tester `ls` confortablement ; les intégrer comme commandes internes dès l'étape 3 (hors quota de 2 cmdlets) ?
3. **Signature de code** de l'exe et de l'installeur (évite l'avertissement SmartScreen) : certificat disponible ?
4. **Nom de l'installeur et éditeur** affichés dans « Programmes et fonctionnalités ».
5. **Dictionnaires littéraux** (`{k: v}`) : utiles en v1 ou reportés ? (Avec l'interop, `java.util.Map.of("k", "v")` couvre déjà le besoin.)
6. **Licence** du module `powerj-api` pour les auteurs de modules tiers (même licence que le projet ?).
7. **Interop et effets de bord** : faut-il une option de configuration pour désactiver l'interop Java (`interop.enabled=false`) dans des contextes restreints ?
8. **Classpath additionnel** : autoriser l'ajout de jars « bibliothèques » (sans cmdlets) dans `~/.powerj/lib/` pour les utiliser en interop (ex. Apache Commons) ?
