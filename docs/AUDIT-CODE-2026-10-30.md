# Audit de PowerJ — état du code au 30/10/2026

> Rapport rédigé en français, comme `REVUE-ARCHITECTURE.md` dont il est la suite. Il porte sur le commit `8d5626c` (`main`, PR #76).
> La revue du 24/10 a produit cinq phases de refactoring ; cet audit vérifie **ce qui a réellement été corrigé**, cherche **ce qui reste ou ce qui est nouveau**, et traite à part la question de la **mutualisation entre modules**.

## 0. Méthode et limites (à lire avant les constats)

- **Lecture directe** du code de production des zones à risque : `powerj-fs` (entier), `powerj-api` (entier), `core` (runtime, command, java, assist, une partie de format/lang), `cmdlets` (fichiers, JSON, records), `shell` (config, exécution concurrente), `fx` (formatage, filtres), poms, CI, ADR, docs.
- **Mesures par script** (dans le scratchpad de la session, non committées) : détection de blocs dupliqués par fenêtres de lignes normalisées sur tout `src/main` et `src/test`, longueur et complexité approchée des 1 812 méthodes de production.
- **Le projet a été compilé et testé avec OpenJDK 25.0.4** (installé par `apt` ; la première version de ce rapport, écrite avant cette installation, était purement statique). Résultat de `mvn test` sur `api`, `fs`, `core`, `cmdlets`, `shell` : **BUILD SUCCESS, 0 échec, 0 test sauté** sur Linux. Le détail de `mvn verify` (Spotless, enforcer, planchers JaCoCo, ArchUnit, `fx`) est au § 4.7.
- **Ce qui a été exécuté** : A-02 (sonde jetable, supprimée après usage), la comparaison `humanBytes`/durée n'a *pas* été rejouée (lecture). Une expérience sur JDK 21 a servi au § 3.2 (helper `ResourceBundle`).
- **Ce qui reste établi par lecture seule** : tous les autres constats. Les chiffres de lint, d'Error Prone et de mutation sont ceux de `docs/quality/*.md`, non rejoués.
- Chaque constat porte un **niveau de confiance** : *confirmé à l'exécution*, *confirmé par lecture* (code sans ambiguïté, non exécuté), *probable* (déduit), *à décider* (choix de conception, pas un défaut).

## 1. Synthèse

PowerJ reste, cinq jours après la revue précédente, un projet **nettement au-dessus de la moyenne** : 28 000 lignes de production pour 28 600 de test (ratio 1,02, contre 0,69), 2 271 tests exécutés (`mvn verify`, 0 échec), des règles d'architecture exécutées (ArchUnit, une seule violation gelée), une surface d'API figée par un instantané, une CI épinglée par SHA, des parseurs JSON/CSV validés contre des suites de conformité. Les quatre défauts 🔴 de la revue du 24/10 et la plupart des 🟠 que j'ai relus sont **vraiment corrigés** (§ 2).

Ce que cet audit ajoute :

| # | Constat | Gravité | Confiance |
|---|---|---|---|
| **A-01** | Le contrat de sortie de l'API est relu en ordre dispersé : `@Display` deux fois (terminal, grille), `@Bytes` trois fois (terminal, grille, `cmdlets`), deux `RecordShape` quasi identiques ; la mise en forme d'une durée **diverge déjà** entre terminal et grille | 🟠 | confirmé |
| **A-02** | `JavaClasses.find("")` et `find(".")` lèvent une exception que la complétion avale : **aucun nom commençant par un point (fichier ou dossier caché) n'est complété s'il n'est pas entre guillemets** — `ls .h`, `cat .gi`, `ls ~/.h`, `ls ./.h` | 🟠 | **confirmé à l'exécution** |
| **A-03** | `FileWalker` n'attrape pas `DirectoryIteratorException` alors que sa Javadoc promet que « the walk goes on » ; `FileTrees` la traite — la dérive est la conséquence de A-04 | 🟠 | confirmé |
| **A-04** | `FileTrees.Work` recopie `FileWalker.ParallelWalk` (≈ 110 lignes de moteur concurrent) — déjà signalé (C-27), **toujours là** | 🟠 | confirmé |
| **A-05** | `Platform` (core) et `HostOs` (cmdlets) détectent l'OS séparément et **différemment** (sensibilité à la casse) ; la complétion de chemins traite macOS comme sensible à la casse, `ProtectedPaths` comme insensible | 🟡 | confirmé |
| **A-06** | `Language` : état global mutable **dans l'API publique** (setter accessible à tout module tiers) ; c'est la seule violation gelée d'ArchUnit | 🟡 | confirmé |
| **A-07** | `NativeRunner` répète trois fois la construction de `ProcessBuilder` ; `OptionBinder.bind` (90 lignes), `ModuleLoader.define` (91), `Highlights.of` (95, CC≈40) restent les points durs | 🟡 | confirmé |

Il n'y a **aucun défaut critique** nouveau. Le risque principal n'est pas un bug mais la **dérive** : chaque duplication ci-dessous a déjà produit une divergence (A-01, A-03, A-05), ce qui est la preuve qu'elle coûte.

**Notes d'ensemble** (appréciation qualitative) :

| Axe | Note | Pourquoi |
|---|---|---|
| Architecture modulaire | A | JPMS réel, `cmdlets ⇏ core`, règles exécutées, exports qualifiés |
| Qualité du code | B+ | méthodes courtes, types scellés, mais quelques fonctions à complexité 25–40 |
| Tests | A− | très nombreux, caractérisation, mutation, conformité ; 69 `assume*` qui sautent en silence |
| Mutualisation / duplication | B− | bonne tenue *dans* un module, dérive *entre* modules (§ 3) |
| Sécurité | B | garde-fous honnêtement présentés comme tels ; restes connus (§ 4.5) |
| Build / CI | A− | épinglage par SHA, timeouts, enforcer, SBOM, 3 OS ; lints encore en avertissements |
| Documentation | B+ | excellente mais volumineuse ; quelques résidus périmés (§ 4.7) |

---

## 2. Ce qui est bien fait (et vérifié dans le code actuel)

Seuls les points que j'ai moi-même relus sont listés ; ce qui est dit dans `REVUE-ARCHITECTURE.md` sans que je l'aie revérifié n'est pas repris.

1. **Le graphe de modules est sain et défendu par des tests.** `api` et `fs` n'ont aucune dépendance ; `cmdlets` ne voit que `api` et `fs` ; `fx` que `api`. `ArchitectureTest` compte 18 règles (cycles, couches du core, `fx.model` sans UI, un seul lecteur du processus…) et `static fields are final` n'a qu'**une** exception gelée (`Language.current`).
2. **`Session` est maintenant correctement synchronisée** : `SessionState` (champs `volatile` + map synchronisée avec `environmentSnapshot()`), `Variables` (`ConcurrentHashMap` avec sentinelle pour `null`), `ShellStatistics` (verrou documenté). C'était C-13.
3. **Le point d'étranglement `JavaInvoker.call` + `CallPolicy`** est le bon endroit pour le garde-fou `System.exit` : un seul appel à `Method.invoke`, qui passe par la politique, y compris via `Method.invoke` imbriqué. La Javadoc dit honnêtement ce que ce n'est pas (un bac à sable).
4. **`Pipe` / `MeasuredPipe`** : le décorateur retire la branche `if (measured)` du chemin chaud. `Supervisor` renvoie un `Outcome` scellé ; `Pipe.abort` est idempotent.
5. **`LatestWinsRunner` (shell)** remplace le moniteur écrit à la main de `Previewer` : numéros de génération, interruption de l'évaluation périmée, `Thread.interrupted()` final pour qu'une interruption tardive n'atteigne pas le publieur. Je n'y ai pas trouvé de course.
6. **Protection contre les regex catastrophiques des deux côtés** : `InterruptibleText` (cmdlets, Ctrl+C) et `RegexFilter` avec budget de caractères (fx, thread JavaFX). C-18 est réglé.
7. **`ModuleLoader` + `ContentFingerprint`** : le consentement natif est lié au SHA-256 du contenu de toute la couche, avec séparateur nom/contenu pour qu'aucun fichier ne puisse en imiter un autre. C-24 est réglé.
8. **CI** : actions épinglées par SHA de commit avec le tag en commentaire, `permissions: contents: read`, `concurrency`, `timeout-minutes` partout, trois systèmes (Linux, macOS, Windows), tests de fumée du lanceur empaqueté.
9. **Build** : `dependencyConvergence`, `requirePluginVersions` (sans SNAPSHOT), `banDuplicatePomDependencyVersions`, Spotless, JaCoCo par module *et* agrégé, SBOM CycloneDX, profils `mutation` et `bench` à part.
10. **`FileWalker` / `FileTrees`** sont bien conçus pris isolément : consommateurs appelés dans le thread appelant, contre-pression par file bornée, liens jamais suivis, annulation par interruption, permis de sémaphore qui bornent les descripteurs ouverts.
11. **Hygiène revenue au niveau d'un projet mûr** : noms qualifiés inline passés de 134 à 20, descriptions de poms en anglais (sauf une, § 4.7), `CONTRIBUTING`, `SECURITY`, `CHANGELOG`, ADR, `.editorconfig`, `.gitattributes`.

---

## 3. Mutualisation entre modules (la demande centrale)

**Règle du jeu retenue.** On ne casse aucune frontière : `cmdlets ⇏ core` (ADR 0003), `fx` n'utilise que `api`, `fs` et `api` n'ont aucune dépendance. Une mutualisation n'est donc acceptable que si elle va **vers le bas** du graphe : dans `api` (contrat, partagé par tous y compris les tiers) ou dans `fs` (feuille sans dépendance, déjà requise par `core` et `cmdlets`).

```text
api ◄── core ◄── shell        fs ◄── core
 ▲  ▲                          ▲
 │  └── fx                     └── cmdlets
 └── cmdlets
```

### 3.1 Candidats, par rentabilité décroissante

| # | Duplication constatée | Où elle vit aujourd'hui | Où la mettre | Gain | Risque |
|---|---|---|---|---|---|
| **M1** | Lecture d'un record de sortie : composants, `@Display` (colonnes par défaut, lu 2 fois), `@Bytes` (taille, lu 3 fois), `@Highlight` (spans) | `core.format.OutputFormatter:302-335`, `fx.model.ObjectReader:160-180`, `cmdlets.RecordShape` + `Properties` + `Sizes`, `core.java.RecordShape`, `core.java.Members:144` | **`powerj-api`** : un type `RecordView` (ou `OutputShape`) mis en cache par `ClassValue` | 4 interprétations de `@Display` → 1 ; plus de dérive entre terminal, grille et `to-csv` | moyen : surface d'API à tenir (instantané `ApiSurfaceTest` à régénérer, ligne au CHANGELOG) |
| **M2** | Mise en forme d'une valeur : `humanBytes`, date `yyyy-MM-dd HH:mm`, durée | `core.format.OutputFormatter:386` et `fx.model.CellFormatter:54` (**copie identique**) ; `Values.duration:22` et `CellFormatter.duration:68` (**divergent**) | **`powerj-api`**, à côté de `@Bytes` : `Bytes.format(long)` ; durée et date dans un `Display`-formatter | supprime la divergence ci-dessous | faible |
| **M3** | Analyse des unités `kb`/`mb`/`gb`, puissances de 1024 | `core.lang.Units:33-48` et `fx.model.FilterParser:136-165, 216-223` | **`powerj-api`** (`Sizes.parse`) ou `fs` | 2 → 1 ; une grille et un `where size > 2.5mb` acceptent la même syntaxe | faible |
| **M4** | Détection de la plateforme, du dossier personnel, sensibilité à la casse des noms | `core.nativeexec.Platform`, `cmdlets.HostOs`, `core.runtime.SessionState:24`, `cmdlets.ProtectedPaths:134-146`, `shell.Environment:63` | **`powerj-fs`** : `Host` (`windows()`, `mac()`, `namesIgnoreCase()`, `home(env, fallback)`) | une seule règle ; corrige A-05 | très faible (fs n'a pas de dépendance) |
| **M5** | Moteur concurrent de parcours : file bornée, `pending`, `stopped`, `fail/signal/deliver`, `submit` | `fs.FileWalker.ParallelWalk:231-386` et `fs.FileTrees.Work:274-538` | **dans `powerj-fs`**, classe package-private `ParallelEngine<T>` | ≈ 110 lignes en moins ; supprime la dérive A-03 | moyen : code concurrent, mais 69 tests de `fs` et la mutation le couvrent |
| **M6** | Construction d'un `ProcessBuilder` (ligne de commande, répertoire, environnement vidé puis rempli, `native.startFailed`) | `NativeRunner.run:110`, `runGroup:221`, `startBackground:350` | méthode privée `ProcessSetup` dans `NativeRunner` | 3 → 1 ; une seule garde sur l'environnement | très faible |
| **M7** | Squelette des cmdlets de fichiers : `FileOps.arguments` → `PipelineFiles` → erreur `fileops.path.expected` → boucle `expand`, puis `process` identique ; options `-n`, `--property` redéfinies avec le même texte | `MakeDirectory`, `Touch`, `Remove`, `Transfer` (`Copy`/`Move`) dans `cmdlets` | classe de base ou assistant dans `cmdlets` (reste dans le module) | ≈ 60 lignes | faible |
| **M8** | `report(WalkError)` : 4 exemplaires en C-27, **2 aujourd'hui** — `FileArguments.report:84` (utilisé par `ls`, `cat`, `grep`) et `FileOps.report` (`cp`, `mv`, `rm`, `mkdir`, `touch`) — avec **deux jeux de clés de messages identiques** (`files.not.found` / `fileops.not.found`, `files.access.denied` / `fileops.access.denied`, dans les deux langues) | `cmdlets` | `cmdlets` : une seule méthode et un seul jeu de clés | −2 clés × 2 langues, plus de dérive possible entre `ls` et `rm` | très faible |
| **M9** | Texte à garde pour regex : `InterruptibleText` (interruption) et `RegexFilter.Budgeted` (budget) | `cmdlets` et `fx` | **`powerj-api`** : `GuardedText(CharSequence, IntPredicate tick)` | 2 → 1 ; une troisième regex (cmdlet tiers) en profite | faible |
| **M10** | Doubles de test d'un `CmdletContext` : `TestContext` ×2 (126 et 105 lignes), `FakeCmdlets`, pilotes `begin/process/end` répétés dans 16 classes de test | `cmdlets/src/test`, `fx/src/test`, `core/src/test` | module `powerj-testing` (jar de test, dépendance `test`) | 230 lignes + pilotes | faible |

> **Dérive déjà observable, pour mémoire.** `CellFormatter` dit « le texte d'une cellule *comme le terminal le montre* » mais formate une durée en `"%d:%02d"` / `"%.3f s"` avec `Language.current()`, alors que le terminal passe par `Values.duration` (clés `duration.minutes/seconds/micros/nanos`, `Locale.ROOT`). Une même durée n'a donc pas le même texte dans `ps` et dans `grid`. Par ailleurs, `humanBytes` formate avec la langue (virgule en français) alors que `Values.duration` fixe `Locale.ROOT` (point) : **deux règles décimales dans le même tableau**.

### 3.2 Ce qu'il ne faut pas mutualiser (et pourquoi)

- **Les sept classes `Messages`** (`api`, `core.i18n`, `core.lang`, `cmdlets`, `shell`, `fx`, `examples/greet`) : la revue précédente concluait « un helper unique suffit » grâce à `ResourceBundle.getBundle(String, Locale, Module)`. **Je l'ai testé sur JDK 21 : cela échoue** (`MissingResourceException`) tant que le module qui porte le bundle n'a pas `opens <package> to io.powerj.api`. Avec le `opens` qualifié, cela fonctionne. Chaque copie fait 19 lignes dont 3 utiles (nom du bundle, `getBundle`, `Language.text`, déjà mutualisé). Le gain est faible et le coût réel : un `opens` dans six `module-info.java` **et** dans celui de tout auteur tiers. **Recommandation : garder les copies**, corriger seulement la Javadoc périmée de `core.i18n.Messages` (« package `io.powerj.core.exec` »).
- **Les trois `switch (Outcome)`, `Previewer` / `Debouncer` de `fx`** : la Phase 4 les a examinés et écartés pour de bonnes raisons (ils se ressemblent sans être pareils). Je confirme.
- **Promouvoir `lang` en module** : toujours prématuré tant qu'il n'a pas de second consommateur (ADR 0002).

### 3.3 Deux façons de s'y prendre

- **Option A (recommandée)** : étendre `powerj-api` de deux ou trois petits types (M1, M2, M3, M9) et `powerj-fs` de `Host` (M4) et du moteur (M5). Aucun nouveau module, aucune dépendance nouvelle ; ce sont des éléments qui touchent déjà au *contrat de sortie* (`@Bytes`, `@Display`, `@Highlight`, `Row` y sont déjà). Contrepartie : la surface de l'API grossit, donc à décider avant la première version publiée, tant que « l'API peut encore casser ».
- **Option B** : un module feuille `powerj-text` (formatage, unités, JSON-quoting) pour ne pas grossir l'API. Plus propre sur le papier, mais c'est un jar de plus à embarquer dans l'image `jlink` *et* à fournir au zip de `powerj-fx` (qui n'embarque pas `api` aujourd'hui). À réserver si M1–M3 dépassent ~500 lignes.

**Ordre conseillé :** M4 → M6 → M8 → M5 → M2/M3 → M1 → M9 → M7 → M10 (du plus sûr au plus structurant).

---

## 4. Défauts et points à revoir

### 4.1 Corrections à faire (petites, avec test)

**A-02 🟠 La complétion d'un nom qui commence par un point échoue en silence** *(confirmé à l'exécution)*

`Completions.doComplete:134` appelle `isExpressionReceiver(receiver)` (l. 167) pour tout mot qui suit un point. Quand le point commence le nom d'un fichier (`.gitignore`, `~/.config`, `./.git`), le « récepteur » calculé par `LineText.receiverText` est `""` ou `"."`, et atteint `JavaClasses.find(String)` (l. 96) qui :

- pour `""` : passe par `qualified("java.lang.")`, dont la lambda évalue `n.charAt(n.lastIndexOf('.') + 1)` → `StringIndexOutOfBoundsException: Index 10 out of bounds for length 10` ;
- pour `"."` : `".".split("\\.")` renvoie un tableau **vide**, puis `parts[0]` → `ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0`.

`Completions.complete` attrape `RuntimeException` et renvoie `Result.none` : aucun plantage, mais aucune complétion. **Mesuré** (sonde jetable, répertoire courant avec `.hidden`, `.gitignore`, `notes.txt`, `sub/`) :

| Ligne tapée | Candidats |
|---|---|
| `ls no`, `ls ~/no`, `ls ./no` (témoins) | `notes.txt`, `~/notes.txt`, `./notes.txt` ✔ |
| `ls ".h`, `ls "./.h` (entre guillemets) | `.hidden`, `./.hidden` ✔ |
| `ls .`, `ls .h`, `ls ..`, `cd ..`, `cat .gi`, `ls ./.h`, `ls ~/.h`, `ls sub/../.h` | **aucun** ✘ |
| `find("")`, `find(".")` appelés directement | lèvent les deux exceptions ci-dessus |

Le défaut est donc plus large que « `.` et `..` » : **toute complétion de fichier ou de dossier caché hors guillemets** est cassée, ce qui est un usage quotidien. Le seul test existant (`CompletionsTest:200`) utilise des guillemets et passe par une autre branche.
*Correctif :* refuser les noms vides ou sans lettre dans `find`/`qualified` (garde de 2 lignes) ; journaliser au niveau `FINE` ce que `complete` avale, pour que le prochain défaut de ce genre ne soit pas invisible. *Test :* les lignes du tableau ci-dessus, avec un dossier contenant `.hidden`.

**A-03 🟠 `FileWalker` : une erreur de lecture de répertoire en cours d'itération interrompt tout le parcours** *(confirmé par lecture ; aucun test existant ne simule une panne en cours d'itération, ni pour `FileWalker` ni pour `FileTrees`)*

`FileWalker.children:181-203` n'attrape que `IOException`. Or l'itération d'un `DirectoryStream` lève `DirectoryIteratorException` (non contrôlée) quand la lecture échoue au milieu ; `FileTrees.children:484` la traite correctement (« the entries read are kept »). En mode parallèle, l'exception sort de la tâche, `fail()` arrête la marche entière et la relance au thread appelant : `ls -r` sur un répertoire instable (système de fichiers réseau, `/proc`) plante au lieu de signaler l'entrée et de continuer, contrairement au contrat de la Javadoc de `FileWalker`. *Correctif :* copier le `catch (DirectoryIteratorException e)` de `FileTrees` — ou mieux, M5, qui supprime l'écart à la source.

**A-08 🟡 `ConfigFile.set` n'échappe pas la valeur qu'il écrit** *(confirmé, latent)*
`ConfigFile.set` écrit `key + "=" + value` tel quel (l. 78). Les valeurs actuelles (`sha256:<hex>`, `false`) sont sûres ; mais une valeur contenant `\` (chemin Windows) ou un saut de ligne produirait un fichier que `read()` refuse ensuite (c'est exactement le défaut C-11 d'origine). À échapper dès que le premier appelant en écrit une autre.

**A-09 🟡 `PipelineExecutor.label(name, args)` est évalué à chaque commande, même sans mesure** *(confirmé)*
`prepare` (l. 345) calcule `Values.text(arg)` pour chaque argument de chaque cmdlet et builtin, puis `shorten` le réduit à 30 caractères. Si un argument est une grosse collection, c'est un `toString` complet pour rien. Le libellé n'est utile qu'au mode perf : le rendre paresseux (`Supplier<String>`).

**A-10 🟡 `OptionBinder` recompile des regex à chaque appel** *(confirmé)*
`isOption` (l. 171, `word.matches(...)`) et `kebab` (l. 365, `replaceAll`) compilent un `Pattern` par argument et par composant à **chaque** exécution de commande ; `specs(type)` n'est pas mis en cache non plus. Deux `static final Pattern` et un `ClassValue<List<OptionSpec>>` suffisent. Autre détail : `parseBoolean` (l. 239) accepte `oui`/`non` en dur — c'est voulu (cf. `NativeAccessConsent`), mais c'est du français dans le code d'une base censée être anglaise (ADR 0008) et **l'API n'en parle pas** ; `Globs.ignoreCase` n'accepte que `true/false/1/0` : trois analyseurs de booléens différents.

**A-11 🟡 Code mort : `Supervisor.Task` renvoie une `List<Object>` que personne ne lit** *(confirmé)*
`Repl`, `ScriptMode` et `ProfileRunner` passent des lambdas qui retournent `List.of()`, et `Outcome.Success` copie cette liste (`List.copyOf` lève NPE sur un élément `null`, ce qui deviendrait une « erreur interne » si un appelant s'en servait un jour). Simplifier en `record Success()`.

### 4.2 Conception à décider

**A-06 `Language` dans l'API** — la politique de langue (`set`, `resolve`, priorités propriété/variable/fichier) est dans le module que les tiers voient : un cmdlet tiers peut appeler `Language.set(Locale.FRENCH)` et changer la langue de tout le shell. Ce qu'un auteur de cmdlet doit lire, c'est `Language.current()` et `Language.text(...)`. Séparer **lecture** (reste dans `api`) et **politique** (`core.i18n`, exportée au shell) supprime aussi la seule violation gelée d'ArchUnit. Reste à choisir comment les tests changent de langue (un `Locale` par `Session`, déjà noté « non planifié » dans la revue).

**A-05 `Platform` ≠ `HostOs`** — `Platform` compare `toLowerCase(ROOT).startsWith("windows")`, `HostOs` compare `startsWith("Windows")` sans normaliser la casse. Plus gênant : `PathCompleter:51` règle `ignoreCase = Platform.isWindows()`, alors que `HostOs.NAMES_IGNORE_CASE` dit que macOS ignore aussi la casse. Sous macOS, la complétion de chemins est donc sensible à la casse et `rm` ne l'est pas. Se règle avec M4.

**A-12 `Operators.arithmetic`** — l'arithmétique `int`/`long` est celle de Java : `100000 * 100000` donne `1410065408` sans erreur (`Math.multiplyExact` n'est pas utilisé). C'est cohérent avec « Java arithmetic with numeric promotion » et la spec n'en dit rien ; pour un shell qui accepte `1gb * 4`, un dépassement silencieux est surprenant. *À décider et à écrire dans la spec* (promouvoir en `long` puis `BigInteger`, ou lever une erreur).

### 4.3 Concurrence et robustesse (mineurs)

- **`Supervisor.memoryReserve`** (l. 37) est un champ non `volatile` écrit par le thread de la commande. Après un abandon (2ᵉ Ctrl+C), l'ancien thread continue pendant que le suivant démarre : accès concurrent possible (théorique, jamais fatal). Passer en `volatile` ou `AtomicReference`.
- **`Pipe.onAbort`** (l. 29) peut exécuter l'action deux fois (une dans `abort()`, une dans le test `if (aborted)` du même appel). Les actions actuelles (interrompre un thread, détruire un processus) sont idempotentes ; à documenter pour les futures.
- **`MeasuredPipe`** utilise `volatile long` avec `+=` (signalé par Error Prone, `NonAtomicVolatileUpdate`). Correct sous la règle « un écrivain par compteur », qui n'est écrite que dans la Javadoc ; `LongAdder` rendrait le défaut impossible pour un coût négligeable.
- **`JavaClasses.qualifiedNames`** met en cache, sans borne, chaque nom tapé (y compris les échecs). La prévisualisation évaluant la ligne à chaque frappe, cela grossit avec les préfixes tapés. Faible volume, mais une borne (ou `Optional.empty()` non mis en cache) est gratuite.
- **`PipelineExecutor`** — trois wrappers presque identiques `catch (PjException | InterruptedException ...) { throw e; } catch (Exception e) { throw new PjException(...) }` (`runCmdlet`, `invokeBuiltin`, `runSegment`) avec **trois messages différents** : `e` complet, `e.getMessage()` (qui peut être `null` et afficher « null »), `e.toString()` sans le nom de la commande.

### 4.4 Taille et complexité (mesure approchée)

1 812 méthodes de production ; **22 dépassent 50 lignes**, **44 ont une complexité > 15**. Les plus lourdes :

| Méthode | Lignes | CC≈ | Piste |
|---|---|---|---|
| `assist/Highlights.of` | 95 | 40 | machine à états → une méthode par catégorie de jeton |
| `runtime/Operators.arithmetic` | 67 | 37 | quatre `switch` identiques → une table `Operator → IntBinaryOperator/LongBinaryOperator/…` |
| `cmdlets/JsonSplitter.scan` | 81 | 35 | séparer « profondeur » et « chaîne » |
| `command/ModuleLoader.define` | 91 | 29 | extraire `askConsent`, `openPackages`, `collectCmdlets` |
| `command/OptionBinder.bind` | 90 | 24 | un curseur d'arguments + un traitement par forme (`--x=y`, `-xyz`, positionnel) |
| `runtime/PreviewMode.preview` | 84 | 22 | — |
| `cmdlets/Perf.begin` | 52 | 28 | — |

Classes : `ExpressionParser` 728 lignes (l'objectif ≤ 500 n'est pas atteint, issue #56), `GridView` 540, `FileTrees` 539 (tombera à ≈ 400 avec M5), `Lexer` 515, `JavaInvoker` 499. Rien de tout cela n'est urgent.

### 4.5 Sécurité (garde-fous contre l'accident, pas frontière)

Le modèle est bien posé (ADR 0005, `SECURITY.md`). Restes à connaître, tous déjà documentés ou mineurs :

- **Aperçu pendant la frappe** : `PreviewPolicy` autorise `Files.readAllBytes/readString/lines/walk/list/find` sans regarder l'argument ; `Files.readAllBytes(Path.of("/dev/zero"))` tapé complet épuise la mémoire du processus, un FIFO bloque jusqu'à l'échéance. Et `READ_COLLECTION` autorise `next`/`forEach`/`stream` par le **nom** sur tout `Iterator`/`Iterable`, ce qui laisse passer `Scanner.next()`. Déjà noté « à reprendre » (§ 14.4 de la revue) ; la bonne règle porte sur le type *et* la taille de l'argument.
- **`ModuleLoader`** : le contenu haché (`ContentFingerprint`) et le contenu chargé (`defineModulesWithOneLoader`) sont lus à deux moments — fenêtre de substitution entre le calcul de l'empreinte et le chargement. Exploitable seulement par quelqu'un qui écrit déjà dans `~/.powerj/modules`. Tous les packages de tous les modules non automatiques sont ouverts à PowerJ (`addOpens`, l. 163), et `provider.cmdlets()` s'exécute sans délai. Un échec au milieu laisse la couche définie et des cmdlets partiellement enregistrés.
- **Journal** : `Supervisor` écrit la ligne de commande entière dans le journal (`Command abandoned: …`, `Internal error while running: …`). Un secret tapé en argument s'y retrouve. À masquer ou tronquer.
- **`startBackground`** construit `/bin/sh -c "trap '' INT QUIT; exec \"$0\" \"$@\""` : propre (pas d'interpolation), mais le chemin `/bin/sh` est codé en dur.

### 4.6 Tests

Points forts (2 271 tests exécutés, JSONTestSuite ; mutation à 87 % sur `lang`/`runtime` d'après la revue précédente, non rejouée ici) et réserves :

- **69 `assumeTrue/False`** : sur Linux, `mvn test` rapporte **0 test sauté**, donc ces gardes ne coûtent rien ici ; ils sautent sur Windows (56 tests de `core` d'après le pom racine). Un décompte « exécutés / sautés par plateforme » dans la CI dirait ce que chaque OS joue réellement.
- **2 tests sautés dans `fs`** : `anEntryThatCannotBeRemovedIsReportedAndTheRestIsRemoved` (×2). C'est un artefact de mon environnement (le conteneur tourne en `root`, qui peut supprimer ce que le test déclare non supprimable) ; sur un poste normal ils tournent. À noter : ce test est précisément celui qui couvre le chemin d'erreur de `FileTrees`, pas celui de A-03.
- **26 `Thread.sleep`** ; la plupart sont des charges simulées (`Sleeper`, `Duration.ofMinutes(10)` pour bloquer), c'est légitime. Les attentes de 10–25 ms (`ReplTest:120`, `PerfModeTest:153`, `PreviewControlKeysTest:357`, `GridViewUiTest:141`) sont les candidats à un `awaitUntil`.
- **Duplication dans les tests** (fenêtre de 10 lignes) : `InterpreterCharacterizationTest` se duplique sur 152 lignes (64–298), `PerfTest` (core) sur 26, `CmdletContextTest` sur 36, et `PerfModeTest` / `PreviewControlKeysTest` / `PreviewModeTest` (shell) partagent un même bloc d'installation de 12 lignes. Voir M10.
- **`KnownDefectsTest`** (core et cmdlets) : la Javadoc parle encore de tests `@Disabled` « rouges aujourd'hui » ; **aucun n'est désactivé**. Les classes ne contiennent plus que des tests de comportements épinglés. Renommer (`PinnedBehaviorsTest`) et corriger la Javadoc.
- **Couverture : l'exigence de 80 % est globale, pas par module.** `powerj-coverage` impose 80 % de lignes **et** de branches sur l'ensemble de PowerJ (vue de `fx` exclue), 80 % sur le package `io.powerj.cmdlets` et 90 % sur `fx` hors `view`, en comptant les tests de **tous** les modules pour chacun (NFR-05). Mesuré : 95,7 % / 91,2 % pour l'ensemble, 96,1 % / 93,1 % pour `cmdlets`, 99,5 % / 97,5 % pour `fx` hors `view`. Pour qu'un module ne vive pas des tests des autres, chacun a en plus un plancher qui lui est propre (tests du module seuls) ; ce plancher a été fixé à la valeur mesurée à l'introduction, donc **quatre modules ont un plancher sous 80 % sur au moins un compteur** : `api` 70 % de lignes, `core` 76 % de branches, `fs` et `shell` 78 % de branches.
- **Couverture : le cliquet est desserré.** Les planchers par module (`pom.xml`) sont censés être « mesurés, jamais baissés, relevés quand la couverture monte ». Mesure de cet audit (tests du module seuls, `mvn verify`) :

  | Module | Mesuré lignes / branches | Plancher du pom | Écart |
  |---|---|---|---|
  | `core` | 91,5 % / 83,2 % | 84 % / 76 % | +7,5 / +7,2 |
  | `shell` | 93,9 % / 86,9 % | 83 % / 78 % | +10,9 / +8,9 |
  | `fs` | 89,1 % / 88,3 % | 85 % / 78 % | +4,1 / +10,3 |
  | `cmdlets` | 96,0 % / 93,1 % | 93 % / 91 % | +3,0 / +2,1 |
  | `api` | 78,8 % / 100 % | 70 % / 100 % | +8,8 / 0 |

  Une régression de 7 points sur `core` ou 10 sur `shell` passerait donc inaperçue. Relever les planchers (avec une marge d'un point pour `fs`, dont la couverture varie avec le parallélisme) coûte une ligne par module. Mesuré sur Linux en `root` : les branches propres à Windows n'y sont pas jouées.

### 4.7 Build, lints, documentation

**Vérifié à l'exécution** (OpenJDK 25.0.4, Maven 3.9, `mvn -B verify`, sans `-Pdist`) : `BUILD SUCCESS` sur les onze modules — enforcer (`dependencyConvergence`, `requirePluginVersions`), Spotless, 2 271 tests, ArchUnit (35 tests, une violation gelée), planchers JaCoCo par module et agrégés, `powerj-fx` compris. Les tests de fumée du lanceur empaqueté (`-Pdist`, `ci/smoke`) n'ont pas été rejoués.

- **`-Xlint:all` et Error Prone restent en avertissements.** Rejoué : **30 avertissements javac** (cmdlets 5 main + 7 test, core 13 main + 5 test) contre 29 dans `docs/quality/lint-baseline.md`, dont le texte dit « le compte ne peut que baisser » : le nouveau est `StageLimit.Full` (`[serial]`, sans `serialVersionUID`), arrivé avec l'aperçu FR-66. Error Prone n'a pas été rejoué (220 dans le baseline). Le plancher ne tient que par discipline. Une étape intermédiaire est possible sans tout corriger : `-Werror` limité à des catégories à zéro aujourd'hui (`[missing-explicit-ctor]`, `[serial]` hors `PjException`…), ou un contrôle « le nombre ne monte pas » dans la CI.
- **`.mvn/jvm.config` ouvre les paquets internes de `jdk.compiler` pour tous les builds**, alors que seul le profil `quality` en a besoin. Sans effet visible, mais c'est de la configuration de JVM globale qui ne devrait peser que sur un profil.
- **Pas de japicmp** (il n'y a pas encore de version publiée à comparer) : à brancher au premier tag, comme prévu (#57). `ApiSurfaceTest` couvre l'intervalle.
- **Résidus de documentation** : `powerj-dist/pom.xml:16-19` est la dernière description de pom **en français** (ADR 0008) ; `SPECIFICATION.md:167` parle de « JLine 3 » (le pom est en 4.4.7) ; `core.i18n.Messages` cite le package `io.powerj.core.exec` (supprimé)  ; `NativeAccessConsent:25` et `OptionBinder:239` codent `oui` / `non` en dur. La spécification fait 2 019 lignes : elle est à la fois cahier des charges et documentation, comme le notait C-35.
- **Statut `FR-40`** (jokers dans les arguments natifs) : désormais livré (PR #72, #75) ; la revue le marquait « partiel ». Rien à signaler.

---

## 5. Plan d'action proposé

| Lot | Contenu | Effort | Risque |
|---|---|---|---|
| **1. Correctifs rapides** (½ à 1 j chacun, un test d'abord) | A-02 (`find` + test de complétion — **le plus visible pour l'utilisateur**), **planchers JaCoCo : 80 % minimum dans chaque module, puis cliquet resserré** (détail ci-dessous), A-03 (`DirectoryIteratorException`), A-09 (libellé paresseux), A-10 (regex statiques), A-11 (supprimer la liste de `Task`), M6 (`ProcessSetup`), M8 (un seul `report` et un seul jeu de clés) | ≈ 3,5 j | très faible |
| **2. Mutualisation sans API** | M4 (`fs.Host`), M5 (`ParallelEngine`), M7 (squelette fichiers) | ≈ 4 j | faible à moyen (M5 : concurrence) |
| **3. Mutualisation dans l'API** *(décision à prendre avant la 1ʳᵉ version publiée)* | M2 (`Bytes.format` + durée), M3 (unités), M1 (`RecordView`), M9 (`GuardedText`) ; régénérer l'instantané, ligne au CHANGELOG | ≈ 5 j | moyen (surface publique) |
| **4. Socle de test** | M10 (`powerj-testing`), `assume*` comptés en CI, attentes de 10–25 ms | ≈ 2 j | faible |
| **5. Dette de conception** | A-06 (`Language`), A-12 (dépassement entier), A-05 (via M4) | ≈ 3 j | moyen |
| **6. Complexité** | `Operators.arithmetic`, `ModuleLoader.define`, `OptionBinder.bind`, `Highlights.of` | ≈ 4 j | faible, couvert par la mutation |
| **7. Garde-fous de build** | `-Werror` par catégories à zéro, japicmp au premier tag | ≈ 1 j + au tag | faible |

### Détail du lot 1 : planchers JaCoCo par module

Aujourd'hui l'exigence de 80 % est globale (§ 4.6) et quatre modules ont un plancher propre sous 80 % (`api` lignes 70 %, `core` branches 76 %, `fs` et `shell` branches 78 %). Deux niveaux d'ambition, à choisir :

- **Minimum : 80 % partout.** Une ligne par `pom.xml` (`coverage.module.line` / `coverage.module.branch` ≥ `0.80`). Tous les modules le tiennent déjà, **sauf `api` en lignes** (78,8 % mesuré) : il manque environ deux points, soit quelques tests sur `Language`, `Row` ou `Attempt` avant de relever.
- **Recommandé : plancher = valeur mesurée moins une marge**, c'est ce que la convention du pom demande (« relever quand la couverture monte »). Valeurs proposées, mesurées sur Linux avec JDK 25 :

  | Module | Mesuré (lignes / branches) | Plancher actuel | Plancher proposé |
  |---|---|---|---|
  | `api` | 78,8 % / 100 % | 70 % / 100 % | 80 % / 100 % *(après ajout de tests)* |
  | `fs` | 89,1 % / 88,3 % | 85 % / 78 % | 88 % / 86 % *(le parcours parallèle fait varier les branches d'environ deux points)* |
  | `core` | 91,5 % / 83,2 % | 84 % / 76 % | 90 % / 82 % |
  | `cmdlets` | 96,0 % / 93,1 % | 93 % / 91 % | 95 % / 92 % |
  | `shell` | 93,9 % / 86,9 % | 83 % / 78 % | 93 % / 85 % |
  | `fx` (hors `view`) | 99,5 % / 97,5 % | 99 % / 97 % | inchangé |

**Avant de fusionner :** (1) vérifier les valeurs sur le job macOS de la CI, la mesure ci-dessus vient de Linux en `root` (2 tests de `fs` sautés), donc les planchers proposés sont plutôt prudents ; (2) Windows n'est pas concerné, son profil `windows-coverage-floors` désactive déjà le contrôle par module ; (3) le contrôle agrégé de `powerj-coverage` (80 %) ne change pas.

Le lot 1 se règle sans décision. Le lot 3 est le seul qui engage l'API : il vaut mieux le trancher **maintenant**, tant que `ApiSurfaceTest` permet encore de casser.

---

## Annexe — chiffres relevés

| | `api` | `fs` | `core` | `cmdlets` | `shell` | `fx` | autres |
|---|---|---|---|---|---|---|---|
| Fichiers de production | 30 | 6 | 112 | 63 | 37 | 47 | `bench` 5 |
| Lignes de production | 1 038 | 1 285 | 13 212 | 5 706 | 2 908 | 3 553 | 236 |
| Lignes de test | 651 | 841 | 11 618 | 5 801 | 4 513 | 4 490 | `architecture` 682 |
| Tests exécutés (surefire, `mvn verify`, Linux, JDK 25.0.4) | 36 | 89 (2 sautés) | 820 | 691 | 290 | 310 | `architecture` 35 |

Total : 28 030 lignes de production, 28 596 de test, 300 fichiers de production, 2 271 tests exécutés, 229 commits.

**Détection des doublons** (fenêtre de 6 lignes normalisées, production) : 16 groupes, dont `FileTrees`↔`FileWalker` (40 lignes en fenêtres, ≈ 110 lues), `OutputFormatter`↔`CellFormatter` (9), `JsonWriter`↔`RowExporter` (9), `MakeDirectory`↔`Touch` (17), `Copy`↔`Remove`↔`Move` (13/8/7). Le détecteur par fenêtres **sous-estime** les doublons dont les noms de variables diffèrent (cas de M1) : ceux-là ont été trouvés en cherchant les appels à `getRecordComponents`, `@Display`, `@Bytes` dans les six modules.
