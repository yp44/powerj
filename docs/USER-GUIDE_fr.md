# Guide utilisateur PowerJ — saisir des commandes

*Traduction française de [USER-GUIDE.md](USER-GUIDE.md). Les exemples, les noms de commandes et d'options et les messages
d'erreur cités sont ceux de l'interface en anglais ; avec `POWERJ_LANG=fr`, le shell affiche les mêmes messages traduits.*

Ce guide explique ce que l'on peut taper à l'invite de PowerJ et comment le shell le lit : comment une commande est
appelée, comment les objets circulent à travers `|`, ce qu'une ligne complexe peut contenir, ce que signifient `{ … }` et
`( … )`, quelles variables le shell définit pour vous (`$_`, `$?`, `$exit`…) et quelles variables d'environnement il lit.

Il décrit le comportement du shell tel qu'il est implémenté. La référence de chaque exigence est la
[spécification](SPECIFICATION.md) (rédigée en anglais ; les numéros `FR-nn` ci-dessous y renvoient), et les vérifications
pas à pas sont dans [`acceptance/`](acceptance/).

## Sommaire

1. [Aperçu d'une ligne](#1-aperçu-dune-ligne)
2. [Appeler une commande](#2-appeler-une-commande)
3. [Arguments : ce qui peut suivre un nom de commande](#3-arguments--ce-qui-peut-suivre-un-nom-de-commande)
4. [Pipelines : comment les objets circulent](#4-pipelines--comment-les-objets-circulent)
5. [Blocs](#5-blocs)
6. [Parenthèses et crochets](#6-parenthèses-et-crochets)
7. [Variables](#7-variables)
8. [Variables d'environnement](#8-variables-denvironnement)
9. [Saisie à l'invite](#9-saisie-à-linvite)
10. [Ce qui n'existe pas](#10-ce-qui-nexiste-pas)
11. [Erreurs fréquentes](#11-erreurs-fréquentes)

Conventions : `PJ>` est l'invite ; une ligne qui commence par elle est ce que vous tapez. Les exemples qui appellent des
programmes Unix (`printf`, `sort`, `git`…) fonctionnent de la même façon sous Windows quand le programme existe ; les
commandes internes de `cmd.exe` (`dir`, `echo`, `type`) ne sont pas des programmes et se lancent avec `^cmd /c dir`.

---

## 1. Aperçu d'une ligne

Une ligne est une suite d'**instructions**. Une instruction est une affectation facultative suivie d'un **pipeline** ; un
pipeline est une ou plusieurs **étapes** séparées par `|`, éventuellement suivies de **redirections**.

```text
line      = statement { ( ";" | "&&" | "||" | "&" ) statement }
statement = [ $name = ] pipeline
pipeline  = stage { "|" stage } [ redirections ]
stage     = built-in command | cmdlet | native program | Java expression
```

*(ligne = instruction { séparateur instruction } ; instruction = [ affectation ] pipeline ; pipeline = étape { `|` étape }
[ redirections ] ; étape = commande interne, cmdlet, programme natif ou expression Java.)*

```text
PJ> $big = ls -r --filter *.java | where { $_.size > 10kb } | sort size --desc 2> errors.txt ; $big.size()
```

Cette ligne contient deux instructions. La première affecte à `$big` le résultat d'un pipeline de trois étapes et envoie le
flux d'erreurs dans un fichier ; la seconde appelle une méthode Java sur la variable.

### Enchaîner les instructions

| Syntaxe | Effet |
|---|---|
| `a ; b` | Exécute `a`, puis `b`, quel que soit le résultat de `a`. |
| `a && b` | Exécute `b` seulement si `a` a **réussi**. |
| `a \|\| b` | Exécute `b` seulement si `a` a **échoué**. |
| `a & b` | Lance la commande native `a` **en arrière-plan** et exécute `b` aussitôt. |

`&&` et `||` court-circuitent, comme en Java. Un `;` ou un `&` final est permis ; une instruction vide (`;;`) est ignorée.

**Ce que « réussi » veut dire** (FR-04c) :

| Instruction | Réussie quand |
|---|---|
| commande native | son code de sortie est 0 |
| cmdlet | elle n'a signalé aucune erreur (une erreur non bloquante, comme un fichier illisible, compte comme un échec) |
| expression Java | pas d'erreur et — si la valeur est un `Boolean` — la valeur est `true` |

Ainsi `Files.exists(Path.of("build")) || mkdir build` crée le dossier seulement s'il n'existe pas, et
`mvn package && java -jar target/app.jar` lance le jar seulement après une construction réussie. `$?` contient le résultat
de la dernière instruction exécutée ([§7](#variables-automatiques)).

Une erreur dans une instruction est affichée et compte comme un échec ; elle n'arrête pas les instructions suivantes de la
ligne (`commandeinexistante; "suite"` affiche l'erreur, puis `suite`). Une **erreur de syntaxe**, où que ce soit dans la
ligne, empêche l'exécution de toute la ligne.

### Arrière-plan : `&`

`cmd &` lance une commande **native** (ou un pipeline de commandes natives) et rend l'invite aussitôt :
`xeyes &`, `make > build.log 2>&1 &`. Le shell affiche `[1] 12345` (numéro de job et pid) et signale `[1] done …` avant un
prochain prompt. `jobs` liste les jobs en cours ; `jobs | kill` les arrête. Ctrl+C n'arrête que la commande au premier plan.

`&` ne s'applique qu'aux commandes natives — `ls &` est une erreur — et une affectation ne peut pas être mise en arrière-plan.
`&` n'est l'opérateur que si un blanc, un `;` ou la fin de ligne le suit : `curl http://x?a=1&b=2` passe l'URL entière.

### Redirections

| Syntaxe | Effet |
|---|---|
| `> fichier` | La sortie du pipeline va dans le fichier (écrasé). |
| `>> fichier` | La sortie est ajoutée à la fin. |
| `2> fichier`, `2>> fichier` | Le flux d'erreurs (de toutes les étapes) va dans le fichier. |
| `2>&1` | Le flux d'erreurs de **cette étape** rejoint sa sortie et descend le pipeline. |

- `>` et `>>` s'appliquent à **tout le pipeline** et doivent venir à la fin : `ls > f | where …` est une erreur de syntaxe.
  `2>&1` suit l'étape à laquelle il s'applique : `cmd 2>&1 | where { $_.contains("erreur") }`.
- Les objets écrits dans un fichier le sont sous leur forme affichée (le même tableau que vous verriez), en UTF-8.
  Une commande native redirigée avec `>` écrit le fichier elle-même, octet pour octet.
- Une affectation ne peut pas rediriger sa sortie (`$x = ls > f` est une erreur), ni un groupe `( … )`.
- Le nom du fichier est un argument ordinaire : mots non quotés (avec `~`) ou chaîne. Il n'y a **pas de redirection
  d'entrée** : `<` n'est pas un opérateur (alimentez un programme avec `cat fichier | programme`).

---

## 2. Appeler une commande

### Quelle commande s'exécute

Le premier mot d'une étape est cherché dans cet ordre (FR-13) :

1. une **commande interne** du shell : `cd`, `pwd`, `which`, `help`, `import`, `mod-load`, `mod-list`, `jobs`,
   `history`, `exit` ;
2. une **cmdlet**, commande fournie avec PowerJ ou par un module : `ls`, `grep`, `where`, `map`, `sort`, `env`…
   (`help` les liste avec un résumé) ;
3. un **programme natif** trouvé par le `PATH` de la session (et `PATHEXT` sous Windows), ou un chemin explicite ;
4. sinon l'erreur `unknown command: nom`.

`^nom` saute les étapes 1 et 2 et lance toujours le programme : `^ls`, `^sort data.txt`. `which nom…` indique ce que
représente un nom (`ls → cmdlet`, `git → native /usr/bin/git`, `cd → built-in command`). Un nom qui contient un `/` (ou
`\` ou `:` sous Windows) est un chemin vers un programme, relatif au dossier courant : `./build.sh`, `.\tool.exe`.

Il n'y a pas d'alias. Le nom d'une cmdlet est un mot court en minuscules (`ls`, `to-json`) ; il n'y a pas de forme longue.

Une ligne peut aussi **commencer par une expression Java** au lieu d'une commande
([§2.4](#24-une-expression-java-comme-étape)). Le shell décide en regardant le premier mot.

### 2.1 Cmdlets

Une cmdlet produit des objets Java typés (le plus souvent des records) et reçoit les objets de l'étape précédente. Ses
options suivent la convention Unix (FR-18) :

| Forme | Exemple |
|---|---|
| option courte | `-r` |
| options courtes groupées | `-ra` (= `-r -a`) |
| option courte avec valeur | `-p 16`, `-p16` |
| option longue | `--recurse` |
| option longue avec valeur | `--filter *.java`, `--filter=*.java` |
| argument positionnel | `ls src`, `grep TODO src` |
| fin des options | `ls -- -fichier-qui-commence-par-un-tiret` |

Les détails qui comptent :

- **Les noms longs ignorent la casse et peuvent être abrégés** tant qu'ils restent non ambigus (`--rec` pour `--recurse`) ;
  une abréviation ambiguë liste les candidats. **Les noms courts respectent la casse.**
- Une option inconnue est une erreur avec une suggestion : `ls: unknown option --recurce, did you mean --recurse?`
- Une option booléenne est un drapeau (`-r`) ; `--recurse=false` est aussi accepté (`true`/`false`, `yes`/`no`, `1`/`0`).
- Les arguments positionnels sont liés dans l'ordre. Une cmdlet dont le dernier positionnel est une liste (`rm a b c`,
  `select name size`) prend tous les arguments restants.
- Un nombre négatif (`-5`) et un `-` seul sont des valeurs positionnelles, pas des options.
- Le lieur d'options travaille sur des valeurs, pas sur la façon dont elles ont été écrites : un `"-r"` entre guillemets
  reste une option. Pour passer un texte qui commence par `-`, mettez `--` avant.
- Les valeurs sont converties vers le type que la cmdlet déclare : `Path`, `int`, `long`, `double`, `boolean`, énumérations
  (par nom, casse ignorée), `Duration`, listes. Un `long` accepte une taille (`--min 10kb`) et une `Duration` une durée
  (`--timeout 30s`). Une valeur non convertible est une erreur avant toute exécution. Une valeur calculée par une
  expression garde son type Java : `ls $dir` reçoit le `Path` lui-même.
- **Toute cmdlet accepte `--on-error stop|continue|silent`** (défaut `continue`), qui dit quoi faire des erreurs *non
  bloquantes* (un fichier illisible, un objet qui ne convient pas) : signaler et continuer, s'arrêter à la première, ou
  masquer.
- `cmdlet --help` montre les options, le type des objets produits et des exemples ; `help cmdlet` aussi.
- Une cmdlet qui travaille sur des fichiers développe elle-même les jokers de ses arguments de chemin, avec ou sans
  guillemets (`ls *.txt`, `cat "*.txt"`, `rm *.tmp`). Un joker qui n'est pas un chemin reste celui de la cmdlet
  (`ps java*`, `ls --filter *.java`).

### 2.2 Programmes natifs

Tout autre mot est cherché dans le `PATH` et lancé comme programme (`git`, `mvn`, `docker`, `notepad`…). **Une commande
native n'est jamais enveloppée dans un objet : sa sortie reste un flux** (FR-37).

| Place du programme dans la ligne | Sa sortie standard | Son erreur standard |
|---|---|---|
| dernière étape à l'invite (`git log`) | va directement au terminal : couleurs, pagination, `vim`, `ssh`, `python` fonctionnent | le terminal |
| suivi d'une étape (`git status \| where …`) | devient un flux de lignes `String` | le **flux d'erreurs** de PowerJ (rouge), jamais mélangé aux lignes |
| affecté (`$l = ipconfig`) | capturé comme une liste de lignes `String` | le flux d'erreurs |
| suivi d'un autre programme (`^cat a \| ^sort`) | octets passés directement au processus suivant, sans décodage | le flux d'erreurs |
| redirigé (`git log > f`) | écrit dans le fichier par le programme | le terminal, ou le fichier de `2>` |

Les arguments sont passés **tels qu'écrits**, après trois développements :

- les **variables et groupes** sont remplacés par le texte de leur valeur ([§3](#3-arguments--ce-qui-peut-suivre-un-nom-de-commande)) ;
- **`~`** au début d'un mot non quoté devient le dossier personnel (`~`, `~/docs` ; pas `~user`, pas entre guillemets) ;
- **jokers** (Linux et macOS) : un mot non quoté contenant `*`, `?` ou `[a-c]` est remplacé par les noms triés des fichiers
  qu'il désigne, comme le fait un shell Unix. Un mot qui ne correspond à rien est passé tel quel ; un texte entre
  guillemets, une variable ou un groupe n'est jamais développé ; un nom qui commence par `.` ne correspond qu'à un motif
  qui commence par `.` ; les jokers respectent la casse sauf si `POWERJ_GLOB_IGNORE_CASE` est définie
  ([§8](#8-variables-denvironnement)). Sous Windows, c'est le programme qui développe ses jokers : les mots passent
  inchangés (FR-40).

Les mots non quotés sont littéraux : **l'antislash n'y est pas un caractère d'échappement**, donc `cd C:\Users` et
`ls D:\photos` fonctionnent tels que tapés. Entre guillemets doubles, les échappements Java s'appliquent et l'antislash se
double : `cd "C:\\Program Files"`. Les apostrophes **ne sont pas** des guillemets dans PowerJ (`'x y'` fait deux mots,
`'x` et `y'`).

Après une commande native, le shell enregistre ce qui s'est passé sans le mettre dans la sortie
([§7](#variables-automatiques)) : `$exit` est le code de sortie, `$last` le détail, `$?` la réussite. **Un code de sortie
non nul n'est pas une erreur du shell** : l'instruction suivante s'exécute, et `&&` / `||` y réagissent.

Autres comportements :

- Le programme démarre dans le **dossier courant du shell** avec l'**environnement de la session**
  ([§8](#8-variables-denvironnement)).
- Les programmes interactifs (`vim`, `ssh`…) lisent seuls le clavier pendant qu'ils tournent ; l'éditeur de ligne attend.
- Sous Windows, un programme du sous-système graphique (`notepad`) est lancé détaché : l'invite revient aussitôt,
  `$last.pid` est renseigné, `$exit` est vide.
- Le texte échangé avec un programme (décodage de sa sortie, objets écrits sur son entrée) utilise l'encodage donné par
  `POWERJ_NATIVE_ENCODING` ([§8](#8-variables-denvironnement)).
- Ctrl+C arrête le programme et ses processus enfants.

### 2.3 Commandes internes

`cd`, `pwd`, `which`, `help`, `import`, `mod-load`, `mod-list`, `jobs`, `history`, `exit` agissent sur le shell
lui-même. Elles ne sont valables qu'en **première étape** (`ls | cd` donne l'erreur `"cd" does not read pipeline
objects`), et ne sont pas disponibles en mode aperçu.

| Commande | Effet |
|---|---|
| `cd chemin`, `cd`, `cd -`, `cd ~`, `cd D:` | Change le dossier courant du shell (aussi la base des chemins relatifs pour les cmdlets, les appels Java et les programmes). `cd` seul va au dossier personnel ; `cd -` au précédent. |
| `pwd` | Le dossier courant, sous forme de `Path`. |
| `which nom…` | Ce que représente chaque nom. |
| `help`, `help cmdlet`, `help List`, `help members $x` | Vue d'ensemble ; une cmdlet ; l'API d'une classe Java ; les propriétés et méthodes d'une valeur. |
| `import java.security.*`, `import` | Ajoute des imports Java pour la session ; seul, les liste. |
| `mod-load chemin`, `mod-list` | Charge un module tiers ; liste les modules. |
| `jobs` | Les jobs en arrière-plan, sous forme d'objets `Job`. |
| `history`, `history --clear`, `!!`, `!n`, `!texte` | L'historique numéroté ; relance la dernière commande, l'entrée *n*, ou la dernière qui commence par *texte* (seulement au début de la ligne). |
| `exit`, `exit 3` | Quitte le shell, avec un code de sortie. |

### 2.4 Une expression Java comme étape

Tout ce qui est du Java peut commencer une étape, et sa valeur devient la sortie de l'étape :

```text
PJ> Math.max(3, 7)
PJ> new StringBuilder("ab").reverse()
PJ> LocalDate.now().plusDays(10).dayOfWeek
PJ> Files.readAllLines(Path.of("notes.txt")) | where { $_.contains("TODO") }
PJ> "today is $(LocalDate.now().dayOfWeek)"
PJ> [long] 5
PJ> (ls)*.name
```

Le shell choisit entre commande et expression d'après le premier mot (FR-46) :

- c'est une **expression** s'il commence par `$`, un guillemet `"` ou une apostrophe `'`, `(`, `{`, `[`, un chiffre,
  `true` / `false` / `null` / `now`, `new Nom`, ou un **nom Java pointé** *immédiatement* suivi de `(`
  (`Math.max(1, 2)`, `Path.of("x")`) ou qui désigne une classe ou un champ statique d'une classe connue (`Math.PI`,
  `DayOfWeek.MONDAY`, `java.lang.Math.PI`) ;
- sinon c'est une **commande**. Ainsi `java -version` et `notepad.exe notes.txt` restent des commandes natives, alors que
  `java.lang.Math.max(1, 2)` est un appel Java.

Dans le doute, mettez l'expression entre parenthèses : `(Math.max(1, 2))`.

Règles pour une expression en début d'étape :

- Toutes les classes Java des imports par défaut s'écrivent par leur nom simple (`java.lang`, `java.math`, `java.text`,
  `java.util` et ses `function`, `stream`, `regex`, `concurrent`, `java.io`, `java.nio.file`, `java.nio.charset`,
  `java.net`, `java.net.http`, `java.time`, `java.time.format`). Les autres demandent `import` ou le nom complet. Seule la
  bibliothèque standard Java est accessible ([FR-52](SPECIFICATION.md)) ; `System.exit` et ses semblables sont
  transformés en la commande `exit`.
- Une valeur ne peut être que la **première** étape : `ls | $x` est une erreur. Pour traiter une liste avec un pipeline,
  mettez la liste en premier : `$l | where { … }`.
- Les accès s'écrivent sans espace : `$l.size()`, pas `$l .size()`.
- Hors parenthèses, une expression s'arrête là où la syntaxe de commande reprend : `|`, `;`, `&&`, `||`, `>`, `>=`.
  `$a > 1` écrit donc `$a` dans un fichier nommé `1`. Pour comparer, utilisez des parenthèses ou un bloc : `($a > 1)`.
  (`$a + 1`, `$a == 3` et `$a * 2` conviennent.)
- Une exception levée par du code Java donne un message d'erreur court
  (`java.lang.NumberFormatException: For input string: "x"`) ; `$debug = true` ajoute la pile d'appels, et `$errors[0]`
  garde l'objet exception.

---

## 3. Arguments : ce qui peut suivre un nom de commande

Tout ce qui suit le nom de la commande, jusqu'au prochain `|`, `;`, `&&`, `||`, `&` ou à une redirection, est un
argument. Les sortes d'arguments :

| Forme | Exemples | Ce que la commande reçoit |
|---|---|---|
| **Mot** (non quoté) | `src`, `C:\Users`, `*.java`, `-r`, `10kb`, `1.5`, `~/docs` | Le texte tel que tapé, `~` développé. Une cmdlet le convertit vers le type attendu. |
| **Chaîne** `"…"` | `"C:\\Program Files"`, `"Hello $name"` | Le texte, après échappements Java et interpolation. Jamais développé comme joker. |
| **Variable** | `$dir`, `$f.path`, `$l[0]`, `$l.size()` | La valeur, avec son type Java. Un programme natif en reçoit le texte. |
| **Appel Java** | `Path.of("a.txt")`, `Math.max(1, 2)` | La valeur. Le nom et `(` doivent se toucher. |
| **Groupe** `( … )` | `(ls)`, `(env HOME).value` | La ou les valeurs du pipeline ou de l'expression ([§6](#6-parenthèses-et-crochets)). |
| **Bloc** `{ … }` | `{ $_.size > 1mb }`, `{ f -> f.dir }` | Une fonction que la cmdlet applique à ses objets ([§5](#5-blocs)). |
| **Référence de méthode** | `FileEntry::name`, `String::toUpperCase` | Une fonction, comme un bloc qui se contente d'appeler la méthode. |

### Comment une ligne est découpée en arguments

Un **mot non quoté se termine** à un blanc, `;`, `|`, `"`, `>`, `(`, `)`, `{`, `}`, `&&`, ou un `&` suivi d'un blanc, d'un
`;` ou de la fin de ligne. `2>` est un opérateur quand il suit un blanc. Tout le reste — `$`, `'`, `[`, `]`, `*`, `?`, `=`,
`,`, `:`, `\`, `-` — fait partie du mot.

Cela a des conséquences qui diffèrent des autres shells :

- **Les arguments ne se collent jamais.** Chaque élément est son propre argument, avec ou sans blancs entre eux :
  `cd $home/docs` fait deux arguments (`$home` et `/docs`), et `a"b"` en fait deux (`a` et `"b"`). Pour construire un
  chemin, utilisez une chaîne : `cd "$home/docs"`.
- **`$` n'est reconnu qu'au début d'un argument.** `--out=$file` passe le texte littéral `--out=$file` ; écrivez
  `"--out=$file"`.
- **Un `>` démarre toujours une redirection**, même au milieu d'un mot : `a>b` est le mot `a` redirigé vers `b`.
  Mettez-le entre guillemets : `"a>b"`.
- Une expression utilisée comme argument s'arrête au premier blanc et n'accepte **aucun opérateur** : `echo $a + 1` reçoit
  trois arguments (`$a`, `+`, `1`). Mettez le calcul dans un groupe : `echo ($a + 1)`.
- Un littéral de liste ou une conversion n'est une expression qu'en début d'étape, ou dans `( )` ou `{ }` ; comme argument,
  écrivez `(…)` : `echo ([1, 2, 3])`.

### Chaînes

Une chaîne entre guillemets doubles utilise les **échappements Java** — `\\`, `\"`, `\n`, `\t`, `\r`, `\uXXXX` — et `\$`
pour un signe dollar littéral. Elle **interpole** aussi :

| Dans la chaîne | Signification |
|---|---|
| `$nom` | La variable. |
| `$nom.prop`, `$l[0]`, `$last.duration` | La variable suivie de propriétés et d'indices entiers. |
| `$(expression)` | Toute expression, ou un pipeline, entre parenthèses : `"max = $(Math.max(4, 9))"`, `"$(ls \| count) files"`. |

Un `$` qui n'est pas suivi d'un nom ni de `(` est un simple signe dollar. Trois pièges :

- `.nom` après une variable est toujours lu comme une propriété : **`"$name.txt"` cherche une propriété `txt`** et échoue.
  Écrivez `"$($name).txt"`, ou `$name + ".txt"` dans un bloc.
- Les appels de méthode ne sont pas lus après `$nom` ; utilisez `$( )` : `"$($l.size()) items"`.
- Une chaîne est évaluée **avant** l'exécution de la commande : `"$_.size"` hors d'un bloc est donc une erreur. Les
  conditions sur l'objet courant vont dans un bloc `{ }`.

Les blocs de texte `"""…"""` fonctionnent comme en Java (les guillemets d'ouverture terminent leur ligne ; l'indentation
commune est retirée) et interpolent de la même façon. Les caractères s'écrivent entre apostrophes (`'a'`, `'\n'`) en début
d'étape ou dans les blocs.

### Nombres et littéraux avec unité

Les entiers, les décimaux (`1.5`) et les suffixes `L` sont des littéraux Java. Les **tailles** sont `512b 2kb 500mb 1gb 1tb`
(multiples de 1024, un `long` en octets) et les **durées** `250ms 30s 5m 2h 7d` (une `Duration`) ; `1.5mb` est permis. Ils
fonctionnent dans les expressions (`$_.size > 1mb`, `now - 7d`) et, comme mots, dans les options (`--min 10kb`). `now` est
l'`Instant` courant.

---

## 4. Pipelines : comment les objets circulent

### Ce qui circule

Entre deux cmdlets, ce sont des **objets Java** qui circulent — pas du texte. `ls` émet des records `FileEntry`, `where`
en laisse passer une partie, `sort` les réordonne, et l'affichage ne transforme en tableau les objets arrivés au bout qu'au
dernier moment. Ce qu'une étape reçoit dépend donc de ce que la précédente a produit :

| De → vers | Ce qui traverse le `\|` |
|---|---|
| cmdlet → cmdlet | les objets eux-mêmes (records, chaînes, nombres, n'importe quoi) |
| expression → n'importe quoi | la valeur, déroulée si c'est une collection (voir plus bas) |
| natif → cmdlet | les lignes de la sortie du programme, comme objets `String` |
| cmdlet → natif | les objets sous leur **forme affichée** (un tableau pour des records, le texte pour des chaînes), écrits sur l'entrée du programme |
| natif → natif | les octets bruts, d'un processus à l'autre |

```text
PJ> ls -r --filter *.java | where { $_.size > 10kb } | map { f -> f.name } | sort
PJ> git status --porcelain | where { $_.startsWith(" M ") }
PJ> ls | ^more
```

Dans la deuxième ligne, `$_` est une `String` (une ligne de `git`) : on lui applique donc des méthodes de `String`. Dans la
première, `$_` est un `FileEntry` et `size`, `name`, `dir`… sont ses propriétés. `help members $f` (ou `help FileEntry`)
liste ce qu'offre un objet ; la complétion par Tab connaît le type produit par la cmdlet précédente
(`ls | where { $_.` propose `name`, `size`…).

### Déroulage

Quand une étape produit une collection, ses **éléments** sont envoyés un par un : un `Iterable` (`List`, `Set`…), un
tableau, un flux (`Stream`, `IntStream`…), un `Iterator`, un `Optional` (rien s'il est vide). `String`, `Map` et `Path` ne
sont jamais déroulés. En tête de pipeline, cela inclut les variables :

```text
PJ> $l = List.of("apple", "banana")      # une affectation garde la liste comme un seul objet
PJ> $l | where { $_.length() > 5 }       # une valeur en tête de pipeline est déroulée
banana
```

La cmdlet `collect` rassemble tout un pipeline en **une seule** liste, qui n'est *pas* déroulée quand elle passe à l'étape
suivante : `ls | collect | map { l -> l.size() }` affiche un seul nombre. Le déroulage est paresseux pour les flux et les
itérateurs.

### Flux continu, annulation et arrêt anticipé

- Chaque étape s'exécute en parallèle dans son propre thread virtuel, reliée à la suivante par une **file bornée**
  (256 objets) : un producteur rapide attend un consommateur lent, si bien que `ls -r /` vers une étape lente ne remplit
  pas la mémoire. La dernière étape s'exécute dans le thread du shell.
- Les résultats apparaissent **au fur et à mesure** ; on n'attend pas la fin de l'étape précédente.
- Une étape qui en a assez arrête celles d'avant : `ls -r / | first 5` termine le parcours après cinq fichiers, et
  `ls -r C:\ | ^more` suivi de `q` arrête le `ls`.
- **Ctrl+C** annule toutes les étapes, y compris les processus natifs et leurs enfants. Un second Ctrl+C abandonne un
  calcul qui ignore le premier.

### Étapes qui ne peuvent pas recevoir d'objets

- Une **commande interne** ou une cmdlet qui ne lit pas son entrée ne peut pas venir après un `|` :
  `"ls" does not read pipeline objects`.
- Un **bloc seul** n'est pas une étape — écrivez `where { … }` ou `map { … }`.
- Une **valeur** ne peut être que la première étape.
- Une cmdlet qui attend un type donné ignore les autres par une erreur non bloquante :
  `object ignored: String received, FileEntry expected`.

### Erreurs dans un pipeline

| Sorte | Exemples | Effet |
|---|---|---|
| **Non bloquante** | un fichier illisible dans `ls -r`, un bloc de `where` qui échoue sur un objet | signalée sur le flux d'erreurs, l'objet est ignoré, le pipeline continue ; l'instruction compte comme échouée |
| **Bloquante** | erreur de syntaxe, commande inconnue, option incorrecte, exception Java | tout le pipeline s'arrête |

Le flux d'erreurs est séparé des objets : il n'arrive donc jamais dans `where` ; `2>&1` sur une étape le fusionne
volontairement, `2> fichier` le capture. `--on-error` change le traitement cmdlet par cmdlet. Les exceptions Java récentes
sont dans `$errors`.

### Capturer le résultat

`$x = pipeline` stocke ce que le pipeline a produit : **rien → `null`, un objet → l'objet lui-même, plusieurs → une
liste**. La sortie d'une commande native est stockée sous forme de lignes `String`. `(pipeline)` fait de même dans une
expression ([§6](#6-parenthèses-et-crochets)).

### La fin du pipeline

Les objets qui arrivent au bout sont affichés selon leur type (FR-30) : un tableau pour des records successifs du même
type, une liste `nom : valeur` pour les records larges, un tableau clé/valeur pour une `Map`, le texte pour les scalaires,
`toString()` pour le reste. Les tailles, dates et durées apparaissent sous forme lisible (`14.2 KB`). La redirection vers
un fichier écrit le même texte.

### Un exemple détaillé

```text
PJ> ls -r ~/dev --filter *.java | where { f -> f.size > 10kb && !f.dir } | group ext | sort count -d | first 3
```

| Étape | Reçoit | Produit |
|---|---|---|
| `ls -r ~/dev --filter *.java` | rien (première étape) | des records `FileEntry`, au fil du parcours |
| `where { f -> … }` | un `FileEntry` à la fois | les mêmes objets, quand le bloc renvoie `true` |
| `group ext` | les fichiers filtrés | un `Group(key, count, items)` par extension |
| `sort count -d` | les groupes | les groupes, triés par `count` décroissant |
| `first 3` | les groupes triés | les trois premiers ; quand elle les a, elle arrête les étapes précédentes |

---

## 5. Blocs

Un bloc, écrit `{ … }`, est une **fonction écrite en ligne**, confiée à une cmdlet (`where`, `map`, `sort`, `select`,
`group`, `range`, `join`…) qui l'applique à ses objets. Un bloc entre accolades est aussi ce que l'on passe à une méthode
Java qui prend une interface fonctionnelle (`Predicate`, `Function`, `Comparator`, `Runnable`…).

### Les trois formes

| Forme | Exemple | Signification |
|---|---|---|
| Bloc `$_` | `{ $_.dir }` | Pas de paramètre déclaré : l'objet reçu est `$_`. Filtres courts tapés au clavier. |
| Lambda | `{ f -> f.size > 1mb }` | Un paramètre nommé, **sans `$`**, comme en Java. Préférable dès que l'expression s'allonge. |
| Lambda à plusieurs paramètres | `{ (a, b) -> a.length() - b.length() }` | Pour les comparateurs et réductions. `{ () -> "x" }` pour aucun. |

Une lambda peut aussi s'écrire **sans accolades entre les parenthèses d'un appel Java** :
`$l.stream().filter(s -> s.length() > 4)`. Comme argument d'une **cmdlet**, les accolades sont obligatoires :
`where f -> f.dir` est une erreur de syntaxe (le `>` de `->` serait lu comme une redirection). Une référence de méthode
(`String::length`, `FileEntry::name`, `ArrayList::new`) remplace un bloc qui ne fait qu'appeler une méthode :
`ls | map FileEntry::name`.

Laquelle choisir ([FR-33b](SPECIFICATION.md)) :

- `$_` pour les filtres d'une ligne qui mentionnent l'objet une ou deux fois ;
- une **lambda nommée** quand l'expression grandit, mentionne l'objet plusieurs fois, prend deux paramètres, ou se trouve
  **dans un autre bloc** (dans le bloc interne, `$_` désignerait l'objet interne : nommez donc l'objet externe) :
  `where { f -> List.of("md", "txt").stream().anyMatch(e -> f.name.endsWith("." + e)) }` ;
- une **référence de méthode** quand elle suffit.

Les variables du shell gardent leur `$` dans une lambda : `$min = 1kb; ls | where { f -> f.size > $min }`.

### La forme courte de `where`

`where size > 1mb` équivaut à `where { $_.size > 1mb }`. Dans cette forme seulement, le `>` après le nom de propriété est
une comparaison et non une redirection. Elle accepte `== != < <= > >=` ; la valeur est un nombre, une taille, une durée,
`true`, `false`, `null` ou un texte (entre guillemets s'il contient un espace).

### Ce que contient un bloc

**Une seule expression Java**, pas une suite d'instructions. Pas de `;`, pas d'affectation (`=` est rejeté :
*to compare, use ==*), pas de `if`/`for` (les structures de contrôle sont prévues pour la v2).

| Catégorie | Opérateurs |
|---|---|
| Égalité | `==  !=` — comparent les **valeurs** (`Objects.equals`), les nombres par valeur (`1 == 1L`) |
| Ordre | `<  <=  >  >=` — nombres ; `Comparable` comme les dates, durées et chaînes |
| Logique | `&&  \|\|  !` — court-circuit, **les opérandes doivent être des booléens** |
| Arithmétique | `+  -  *  /  %` — règles Java ; `+` concatène si l'un des côtés est une `String` ; `Instant ± Duration` fonctionne |
| Conditionnel | `condition ? a : b` |
| Conversion | `[long] x`, `[int] 3.9` (la forme Java `(long) x` serait lue comme un groupe) |

Plus tout ce qu'offre une valeur :

- **propriétés** : `$_.name` (sans parenthèses) est résolu, casse ignorée, comme composant de record, puis comme accesseur
  (`getName()`, `isName()`), puis comme champ public, puis — sur une `Map` — comme clé `"name"` ; elles s'enchaînent :
  `$_.path.parent` ;
- **appels de méthode** : `$_.name.endsWith(".java")`, `Files.size($_.path)`, `Pattern.compile("a+").matcher($_).find()` ;
- **membres statiques et constructeurs** des classes Java : `Math.max(1, 2)`, `List.of("png", "jpg")`,
  `new StringBuilder()` ;
- **indexation** : `$l[0]`, `$l[-1]`, `$s[2]`, `$map["key"]` (les apostrophes font un `char`, pas une clé) ;
- **étalement** `*.` : applique une propriété ou une méthode à **chaque élément** d'une liste : `$f*.name`,
  `$f*.name*.toUpperCase()`. Un `.` simple s'applique toujours à l'objet lui-même (`$f.size()` est le nombre d'éléments, et
  `$f.name` sur une liste est une erreur qui suggère `*.name`) ;
- **littéraux** (chaînes, nombres, unités, `now`, `[1, 2, 3]`), **variables** (`$x`, paramètres de lambda, `$_`) ;
- **groupes contenant des commandes** : `(cat a.txt)`, `(ls | collect).size()` — la façon de lancer une commande depuis un
  bloc ([§6](#6-parenthèses-et-crochets)).

Les comparaisons respectent la casse, comme en Java. Les besoins que d'autres shells couvrent par des opérateurs spéciaux
passent ici par des méthodes Java :

| Besoin | Écrire |
|---|---|
| contient / commence / finit | `$_.contains("b")`, `$_.name.startsWith("Pa")`, `$_.name.endsWith(".java")` |
| expression régulière | `$_.matches("^[a-m].*")` (texte entier) |
| insensible à la casse | `$_.toLowerCase().contains("readme")`, `$_.equalsIgnoreCase("ok")` |
| appartenance | `List.of("png", "jpg").contains($_.ext)` |

### Comment le bloc est appliqué

- La condition de `where` doit renvoyer un **booléen**. Toute autre valeur — `where { f -> f.name }`, `null` — est une
  erreur non bloquante (`the block must return a boolean, got String`) et l'objet est ignoré.
- Une erreur pendant l'évaluation du bloc pour un objet (`$_.size > now`) est non bloquante : cet objet est ignoré, les
  autres continuent. `--on-error silent` masque ces signalements, `--on-error stop` s'arrête au premier.
- Un résultat `null` de `map` n'émet rien, et un résultat qui est une collection est déroulé (comme un `flatMap`).
- Un bloc qui reçoit un mauvais nombre d'arguments le dit (`the lambda { f -> … } declares 1 parameter, 2 received`).
- Une cmdlet peut lire une paire `nom={ bloc }` (`select kb={ $_.size / 1kb }`) : le mot `kb=` et le bloc sont deux
  arguments que la cmdlet réunit. En tête de pipeline, `select content={ (cat a.txt) }` évalue ses blocs une fois, sans
  objet courant.
- Le texte du bloc est analysé à la lecture de la ligne : une erreur de syntaxe est donc signalée avant toute exécution.

---

## 6. Parenthèses et crochets

Les parenthèses `( … )` ont quatre rôles ; le shell les distingue d'après leur contenu et leur place.

**1. Une commande ou un pipeline dont on prend la valeur** (une *sous-expression*). Le contenu est un pipeline complet,
exécuté d'abord ; son résultat remplace le groupe, selon la règle de capture — rien → `null`, un objet → lui-même,
plusieurs → une liste :

```text
PJ> (ls)*.name                               # noms de tous les fichiers, toujours une liste
PJ> (ls -r | where size > 1mb).size()        # nombre de gros fichiers
PJ> (env PATH).value.split(":")              # PATH sous forme de tableau de dossiers (";" sous Windows)
PJ> $n = (cat notes.txt -t).length()
PJ> select content={ (cat a.txt) }
```

Le groupe contient **un seul pipeline** — `(ls ; pwd)` et `(ls &)` sont des erreurs de syntaxe — et ne peut pas rediriger
sa sortie (`(ls > f)` est une erreur). Un `()` vide est une erreur.

**2. Le simple groupement d'une expression**, pour imposer un ordre ou la protéger de la syntaxe de commande :
`(1 + 2) * 3`, `($a > 1 && $b)`, `(Math.max(1, 2))`.

**3. Les arguments d'un appel Java**, qui doivent toucher le nom : `Math.max(3, 7)`, `$l.get(0)`, `new File("x")`.
Une espace avant `(` en fait un argument ou un groupe distinct.

**4. L'interpolation dans une chaîne** : `"$(expression ou pipeline)"` ([§3](#chaînes)). Hors d'une chaîne, `$(` n'est pas
une substitution de commande ; utilisez un groupe.

Comment le contenu est lu : s'il commence comme une expression (`$x`, un littéral, un nom Java…), c'est une expression, qui
peut être suivie d'un pipeline (`($l | where { … })`) ; sinon c'est une commande. Les textes entre guillemets et les
groupes imbriqués à l'intérieur ne le terminent pas prématurément.

**Comme argument d'un programme natif**, un groupe donne **un** argument : le texte de la valeur. Une valeur unique
convient (`^echo (env HOME).value`, `git add ($f.path)`) ; une liste devient le texte `[a, b]`. Passez un à un les
éléments voulus.

Les **crochets** ont trois usages : un littéral de liste `[1, 2, 3]`, une conversion `[long] 5` (le contenu des crochets
est un type suivi d'un opérande), et un indice `$l[0]` (collé à la valeur). Un littéral de liste ou une conversion en début
d'étape ou dans un bloc est une expression ; comme argument, entourez-le de parenthèses.

---

## 7. Variables

### Vos variables

`$nom = valeur` stocke le résultat d'un pipeline ou d'une expression, pour le reste de la session :

```text
PJ> $dir = Path.of("/home/me/dev")
PJ> $files = ls -r $dir --filter *.java
PJ> $files.size()
```

- Les noms sont faits de lettres, de chiffres et de `_`, et **respectent la casse**. Les espaces autour de `=` sont
  facultatifs.
- L'affectation doit commencer une instruction (aussi après `;`, `&&`, `||` ou `&`) ; une étape de pipeline ne peut pas
  affecter. Tout le pipeline à droite est capturé (`$x = ls | where …`).
- Ce qui est stocké est la valeur telle quelle, avec la règle de capture du [§4](#capturer-le-résultat). Une liste reste
  un seul objet jusqu'à ce qu'on la place en tête d'un pipeline.
- On ne peut pas affecter une propriété ni un élément (`$x.y = 1` est une erreur).
- Un nom jamais défini est une erreur : `unknown variable: $x`.
- Les variables définies dans `profile.pj` sont présentes au démarrage du shell ; `powerj -c` et les fichiers de script
  n'ont pas de profil.
- Lire se fait avec `$nom`, éventuellement suivi d'accès collés : `$f.path`, `$l[0]`, `$x.size()`, `$f*.name`.

Deux variables sont utilisées par le shell quand **vous** les définissez : `$debug = true` affiche la pile d'appels Java
complète des erreurs inattendues (`powerj --debug` aussi), et `$profile` contient le chemin de `profile.pj` (défini par le
shell au démarrage).

### Variables automatiques

Le shell les maintient. Elles priment sur une variable du même nom que vous affecteriez.

| Variable | Valeur | Notes |
|---|---|---|
| `$_` | L'**objet courant** : l'objet auquel un bloc est en train d'être appliqué. | N'existe que **dans un bloc `{ }`** appliqué aux objets d'un pipeline (ou à l'argument qu'une méthode Java lui donne). Ailleurs : `$_ only exists in a { } block applied to the objects of a pipeline`. |
| `$?` | `true` si la **dernière instruction a réussi** (règles du [§1](#enchaîner-les-instructions)). | Vaut `true` au départ. Un programme natif en échec la met à `false` ; une cmdlet qui a signalé une erreur aussi. |
| `$exit` | Le code de sortie de la **dernière commande native**, ou `null` avant la première, ou pour un programme détaché/en arrière-plan. | Raccourci de `$last.exitCode`. |
| `$last` | La **dernière commande native** : `command` (chemin absolu), `args`, `pid`, `exitCode`, `duration`. | Jamais injectée dans la sortie. |
| `$pwd` | Le dossier courant, un `Path`. | Suit `cd`. |
| `$home` | Le dossier personnel **au démarrage**, un `Path`. | `~` suit `HOME` à chaque usage ; `$home` ne change pas. |
| `$errors` | Les exceptions Java récentes, **la plus récente d'abord**, jusqu'à 20. | `$errors[0].cause`, `$errors[0].class.name`, `$errors[0].stackTrace`. |
| `$perf` | Le rapport de la dernière ligne mesurée en mode perf (Alt+P). | `null` avant toute mesure. |

#### `$_` en détail

- Dans `where { $_.size > 1mb }` et `map { $_.name }`, `$_` est chaque objet à mesure qu'il arrive. Son type est celui de
  l'entrée de l'étape : un `FileEntry` après `ls`, une `String` après une commande native, ce qu'une expression a produit.
- Chaque appel de bloc a **son propre** `$_`. Dans un bloc imbriqué dans un appel Java (`anyMatch(e -> …)`), le `$_` du bloc
  interne est l'argument interne — nommez l'objet externe par un paramètre de lambda si vous avez besoin des deux.
- Dans une lambda à un paramètre, `$_` contient aussi ce paramètre ; utilisez le nom, c'est plus lisible.
- `$_` est lié pour la durée d'un appel, et `$a` / `$b` n'existent pas (utilisez `{ (a, b) -> … }`).
- Dans une chaîne, `"$_.name"` fonctionne dans un bloc et échoue en dehors ([§3](#chaînes)).
- En tête de pipeline, `select name={ … }` n'a pas d'objet courant.

```text
PJ> ls | where { $_.size > 1mb } | map { $_.name }
PJ> $r = ls | where { $_.dir } ; $r*.name
PJ> mvn -q verify ; $exit ; $?
PJ> sh -c "exit 3" ; $exit ; $last.duration       # 3, puis la durée d'exécution du programme
```

### Les variables d'environnement ne sont pas des variables `$`

`$HOME`, `$PATH`, `$env:X` ne lisent **pas** l'environnement : `$HOME` est une variable inconnue (et les variables du shell
respectent la casse : le dossier personnel au démarrage est `$home`). Les variables d'environnement se lisent et se
modifient avec la cmdlet `env` — voir la section suivante.

---

## 8. Variables d'environnement

### L'environnement de la session

Au démarrage, le shell fait une **copie de l'environnement de son propre processus**. Cette copie est *l'environnement de
la session* :

- `env` l'affiche ; `env NOM` affiche une variable sous forme de record `EnvVar(name, value)` ;
- `env --set NOM=valeur`, `env --unset NOM`, `env --append NOM valeur`, `env --prepend NOM valeur` la modifient. Le
  changement s'applique **dès la commande suivante** et dure jusqu'à la sortie du shell ; pour le rendre permanent, mettez
  la commande dans `profile.pj`. Sous Windows, les noms ignorent la casse ;
- tout **programme natif** démarre avec une copie de l'environnement de la session, dans le dossier courant de la session ;
  c'est ainsi que `env --set MAVEN_OPTS=-Xmx2g` parvient à `mvn`. Le shell ne maintient pas `PWD` / `OLDPWD` ;
- le shell lit lui-même ses réglages dans l'environnement de la **session** (tableau ci-dessous) : `env --set` s'y applique
  donc aussi, sauf pour ceux qui ne sont lus qu'au démarrage ;
- `System.getenv()` dans une expression Java renvoie l'environnement avec lequel le processus a **démarré**, pas celui de
  la session (la JVM ne peut pas modifier le sien) : utilisez `env`.

```text
PJ> env --set GREETING=hello
PJ> (env GREETING).value                    # la valeur, sous forme de String
PJ> env --append PATH ~/bin                 # ":" sous Linux/macOS, ";" sous Windows
PJ> env | where { $_.name.startsWith("JAVA") }
PJ> "home is $((env HOME).value)"           # dans une chaîne, $( ) contient une expression
PJ> sh -c "echo \$GREETING"                 # le programme la voit : \$ garde le signe dollar pour sh
```

`$GREETING` seul serait une variable PowerJ, pas la variable d'environnement : PowerJ ne développe jamais lui-même les
variables d'environnement, ce sont les programmes qui le font.

### Variables lues par le shell

| Variable | Lue | Effet |
|---|---|---|
| `HOME`, puis `USERPROFILE`, puis le `user.home` de la JVM | à chaque `~` | Le dossier personnel pour `~` et pour les chemins protégés de `rm` / `mv`. `env --set HOME=D:\me` s'applique aussitôt. |
| `PATH` | à chaque recherche de commande | Où les programmes natifs sont cherchés ; séparateur `:` (Unix) ou `;` (Windows). `env --append PATH …` rend un nouvel outil visible à la commande suivante et à `which`. |
| `PATHEXT` | à chaque recherche, Windows seulement | Extensions d'exécutables essayées, par défaut `.COM;.EXE;.BAT;.CMD`. |
| `POWERJ_HOME` | au démarrage, depuis l'environnement du processus | Le dossier de configuration (par défaut `~/.powerj`, `%USERPROFILE%\.powerj`) : historique, `config.properties`, `profile.pj`, `modules/`, `logs/`. |
| `POWERJ_LANG` | au démarrage, depuis l'environnement du processus | Langue des messages, `en` ou `fr`. Priorité : `-Dpowerj.language`, puis cette variable, puis `language` dans `config.properties`, puis la langue du système. |
| `POWERJ_NATIVE_ENCODING` | à chaque lancement de programme | Encodage du texte échangé avec les programmes natifs : un nom de charset Java (`UTF-8`, `cp850`, `windows-1252`) ou `auto` (défaut : la page de code de sortie de la console Windows, UTF-8 ailleurs). |
| `POWERJ_NATIVE_ENCODING_<NOM>` | à chaque lancement de programme | Idem, pour un seul programme ; `<NOM>` est le nom de l'exécutable en majuscules, sans extension, les autres caractères devenant `_` : `POWERJ_NATIVE_ENCODING_GIT=UTF-8`. Prime sur la variable globale. |
| `POWERJ_NATIVE_ENCODING_STDIN` | au démarrage, mode non interactif | Encodage des lignes lues sur l'entrée standard dans `powerj -c …` quand elle est alimentée par un tube (sinon `POWERJ_NATIVE_ENCODING`, depuis l'environnement du processus). |
| `POWERJ_GLOB_IGNORE_CASE` | à chaque développement de joker | `true` / `1` : les jokers ignorent la casse (`false`, `0` ou absente : ils la respectent ; toute autre valeur est une erreur). Sans effet sous Windows, dont le comparateur ignore déjà la casse. |
| `POWERJ_PERF_AUTOEXIT` | après chaque ligne mesurée | `1` : le mode perf (Alt+P) se désactive de lui-même après une ligne. |
| `POWERJ_PERF_JFR_SAMPLEPERIOD` | quand une ligne est mesurée | Période d'échantillonnage du profileur en mode perf : `10ms` (défaut), `2ms`, `500us` ; minimum `1ms`. |
| `POWERJ_PERF_JFR_ALLOCTHROTTLE` | quand une ligne est mesurée | Limite des allocations échantillonnées : `100/s`, `5/ms`, `600/m`, ou `off`. |
| `NO_COLOR` | au démarrage du shell interactif | Toute valeur non vide désactive les couleurs. |
| `COLUMNS` | au démarrage, mode non interactif sur un terminal | Largeur utilisée pour la mise en page des tableaux (défaut 120). Dans le shell interactif, la largeur vient du terminal. |

Les trois variables `POWERJ_PERF_*` et `POWERJ_NATIVE_ENCODING*` se définissent d'ordinaire une fois pour toutes dans
`profile.pj` avec `env --set`.

---

## 9. Saisie à l'invite

L'invite est `PJ <dossier courant>> `. L'édition est de type Emacs (flèches, Début/Fin, Ctrl+←/→ par mot, Ctrl+W, Ctrl+K,
Ctrl+U).

- **Continuation.** Si un `"`, `(`, `{` ou `[` reste ouvert, ou si la ligne se termine par `|`, `&&` ou `||`, l'invite
  devient `>> ` et le shell attend la suite. Un antislash en fin de ligne n'est *pas* une continuation, si bien que
  `cd C:\` est valide. Une commande peut donc s'écrire sur plusieurs lignes :

  ```text
  PJ> ls -r --filter *.java |
  >>   where { f ->
  >>       f.size > 10kb && !f.dir } |
  >>   map { f -> f.name }
  ```

- **Historique.** ↑/↓ le parcourent (avec un préfixe tapé, seules les entrées correspondantes) ; Ctrl+R cherche en
  arrière. Une ligne qui commence par une espace n'est pas enregistrée, pas plus que les doublons consécutifs. `!!`, `!n`,
  `!texte` tout au début d'une ligne relancent une entrée et affichent la commande développée ; ailleurs `!` est la
  négation (`where { !$_.dir }`).
- **La complétion par Tab** propose les commandes (étiquetées `[pj]`, `[internal]`, `[native]`), les options avec leur
  description, les chemins, les variables, les propriétés de `$_` et des paramètres de lambda, et les classes et méthodes
  Java avec leurs signatures. Elle n'exécute rien.
- **Les couleurs** montrent comment un mot a été lu : cmdlets en vert, programmes natifs en cyan, commandes inconnues en
  rouge, options en gris, chaînes en jaune, variables en magenta.
- **L'aperçu (Alt+V)** montre les premiers résultats de la ligne en dessous pendant la frappe, en n'exécutant que ce qui ne
  change rien. **Le mode perf (Alt+P)** mesure chaque étape des lignes que vous lancez. Voir le README et FR-66 / FR-76.
- **Ctrl+C** efface la ligne en cours de saisie, ou annule la commande en cours ; **Ctrl+D** sur une ligne vide, ou
  `exit`, quitte.
- **Commentaires** (`#`) : ils n'existent que dans les fichiers de script. Dans `profile.pj` et dans un fichier `.pj`, une
  ligne dont le premier caractère non blanc est `#` est ignorée, de même que les lignes vides. À l'invite, `#` est un
  caractère ordinaire.
- **Usage non interactif.** `powerj -c "ligne"` exécute une ligne ; `powerj script.pj` exécute un fichier (une commande peut
  s'étendre sur plusieurs lignes avec la même règle de continuation) ; les lignes envoyées dans `powerj -c` par un tube
  alimentent la première étape sous forme d'objets `String`. Il n'y a ni invite, ni historique, ni couleurs, et une erreur
  bloquante termine le script. Le code de sortie est celui de `exit n`, sinon 0 en cas de succès, le code de la commande
  native en échec, ou 1 pour une erreur de PowerJ.

---

## 10. Ce qui n'existe pas

Savoir ce qui n'est *pas* là évite beaucoup de tâtonnements (les structures de contrôle et les fonctions sont prévues pour
la v2) :

- pas de `if`, `for`, `foreach`, `while`, de fonctions, pas de substitution de commande `$(…)` hors d'une chaîne, pas
  d'apostrophes inverses, pas de here-documents ;
- pas de redirection d'entrée `<`, pas de `|&`, pas de `&` pour les cmdlets, pas de `fg` / `bg` ;
- pas d'alias, pas de développement `$VAR` des variables d'environnement ;
- pas d'opérateurs bit à bit dans les blocs, pas d'affectation dans les blocs ;
- pas d'affectation de propriété (`$x.y = 1`) ;
- pas de compatibilité de syntaxe avec PowerShell ou bash : `-and`, `-match`, `-like` sont ici des opérateurs et méthodes
  Java ([table de correspondance](SPECIFICATION.md#124-powershell--powerj-mapping)).

---

## 11. Erreurs fréquentes

| Vous avez tapé | Ce qui se passe | Écrire plutôt |
|---|---|---|
| `ls \| where f -> f.dir` | `"->" outside a block: … a lambda is written in braces` | `ls \| where { f -> f.dir }` |
| `ls \| { $_.name }` | `a block alone is not a pipeline stage: write map { … } … or where { … }` | `ls \| map { $_.name }` |
| `$a > 1` | écrit `$a` dans un fichier nommé `1` | `($a > 1)` |
| `where { $_.size = 3 }` | `"=" in an expression: to compare, use ==` | `where { $_.size == 3 }` |
| `where { f -> f.name }` | non bloquant : `the block must return a boolean, got String`, objets ignorés | `where { f -> !f.name.isEmpty() }` |
| `$_` à l'invite | `$_ only exists in a { } block …` | le mettre dans un bloc |
| `ls \| $x` | `a value can only be the first stage of a pipeline` | `$x \| …` |
| `ls \| cd` | `"cd" does not read pipeline objects` | `cd` est une première étape |
| `ls > out.txt \| where …` | `">" redirects the output of the whole pipeline: put it at the end` | `ls \| where … > out.txt` |
| `ls &` | `& only applies to native commands` | lancer le programme natif, ou pas de `&` |
| `$x = sleep 5 &` | `no "&" with an assignment` | `sleep 5 &` |
| `cd $home/docs` | deux arguments, `cd` refuse | `cd "$home/docs"` |
| `--out=$file` | le texte `--out=$file` est passé | `"--out=$file"` |
| `"$name.txt"` | cherche une propriété `txt` | `"$($name).txt"` |
| `echo $a + 1` | trois arguments | `echo ($a + 1)` |
| `$HOME` | `unknown variable: $HOME` | `(env HOME).value`, ou `$home` |
| `sort < data.txt` | `<` est passé à `sort` comme argument | `cat data.txt \| sort` |
| `x = 3` | `x` est lu comme un nom de commande, puis `unexpected "="; assignment: $name = value` | `$x = 3` |
| `Math` seul | `unknown command: Math` (un nom de classe nu ne démarre pas une expression) | `Math.PI`, `Math.max(1, 2)` |

Quand une ligne ne se comporte pas comme prévu, `which nom` indique ce qu'un mot désigne, `help members $x` montre ce
qu'offre une valeur, et `$debug = true` affiche la pile d'appels d'une erreur inattendue.
