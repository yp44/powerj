# Recette — Étape 7 : modules tiers

**Objectif :** vérifier qu'un module tiers (un jar) ajoute ses cmdlets à PowerJ, au démarrage ou à chaud, sans exposer ses classes en Java, et que les conflits de noms sont signalés.

## Récupérer les livrables

Comme aux étapes précédentes : onglet **Actions**, dernière exécution du workflow **CI**, artefact `powerj-windows-x64-installer` ou `powerj-windows-x64-portable`.

En plus : l'artefact **`greet-module`** contient `greet.jar`, le module d'exemple (source : `examples/greet`, §4.3 de la spécification).

## Où placer un module

- Dossier : `%USERPROFILE%\.powerj\modules\` (ou `$POWERJ_HOME\modules\` si la variable `POWERJ_HOME` est définie). Le créer s'il n'existe pas.
- Un jar seul s'y dépose directement. Un module qui a des dépendances se place dans un **sous-dossier** avec ses dépendances (`modules\docker\docker.jar`, `modules\docker\lib1.jar`…).
- Les modules sont chargés au démarrage ; `mod-load chemin\vers\module.jar` en charge un sans redémarrer.

## Scénario

| # | Action | Résultat attendu |
|---|---|---|
| 1 | Copier `greet.jar` dans `%USERPROFILE%\.powerj\modules\`, lancer PowerJ | Démarrage normal, aucun avertissement. |
| 2 | `greet --name Yves -c 2` | Tableau de deux objets `name message at` : `Yves  Bonjour Yves !  <date>`. |
| 3 | `gr` puis Tab ; `greet --` puis Tab Tab | `greet` ; puis `--name`, `--count` avec leur description. |
| 4 | `greet -n Yves \| where { g -> g.message.contains("Yves") }` | Un objet ; `greet` est coloré en vert (cmdlet). |
| 5 | `greet -n Yves -c 3 \| map { g -> g.message }` | Trois lignes `Bonjour Yves !`. |
| 6 | `help greet` | Aide générée : options `-n, --name` (obligatoire), `-c, --count`, `Sortie : Greeting (name, message, at)`, `Module : com.example.greet`, exemple. |
| 7 | `mod-list` | Tableau `name version cmdlets source` : `io.powerj.cmdlets … [ls, where, map, env] (intégré)` et `com.example.greet 0.1.0-SNAPSHOT [greet] C:\Users\…\greet.jar`. |
| 8 | `which greet` | `greet → cmdlet (com.example.greet)`. |
| 9 | `new com.example.greet.Greeting("a", "b", null)` | Erreur `classe introuvable : com.example.greet.Greeting` : les classes d'un module ne sont pas exposées en Java. |
| 10 | `greet` | Erreur `greet : option obligatoire manquante : --name`. |
| 11 | Retirer `greet.jar` du dossier, relancer ; `mod-load C:\chemin\vers\greet.jar` | Affiche `greet` ; la commande `greet -n A` fonctionne aussitôt. |
| 12 | `mod-load C:\chemin\vers\greet.jar` une seconde fois | `greet.jar : module com.example.greet déjà chargé`, puis `mod-load : greet.jar non chargé`. |
| 13 | Copier `greet.jar` **deux fois** dans le dossier (`greet.jar` et `greet-copie.jar`), relancer | Sous la bannière : `greet-copie.jar : module com.example.greet déjà chargé` ; `greet` fonctionne. |
| 14 | `mod-load C:\Windows\notepad.exe` | `notepad.exe : un module est un fichier .jar (ou un dossier de jars)`. |
| 15 | `greet:greet -n Q` | Fonctionne : nom qualifié `module:nom`, utile quand deux modules déclarent le même cmdlet (FR-17). |

## Écrire son propre module

Le dossier `examples/greet` du dépôt est un modèle complet : un `pom.xml` qui ne dépend que de `powerj-api`, un `module-info.java` qui déclare `provides io.powerj.api.CmdletProvider with …`, un record d'options annoté `@Option`, un record de sortie, la classe du cmdlet annotée `@CmdletInfo`. `mvnw -pl examples/greet -am package` produit `examples/greet/target/greet.jar`.

## Limites connues de l'étape 7

- Pas de déchargement ni de rechargement d'un module : pour une nouvelle version, remplacer le jar et relancer PowerJ.
- Collision de noms : le premier module chargé (ordre alphabétique des fichiers au démarrage) garde le nom court ; l'autre cmdlet n'est accessible que par `module:nom`, qui n'est pas proposé par Tab.
- `powerj-api` est un artefact Maven séparé, mais pas encore publié sur un dépôt public : pour compiler un module hors de ce dépôt, l'installer localement avec `mvnw -pl powerj-api -am install`.
