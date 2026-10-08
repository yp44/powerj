# PowerJ

Shell interactif orienté objet écrit en Java 27 : les commandes renvoient des **objets Java** (records de préférence) dont on extrait les attributs dans un pipeline, avec des noms de commandes courts façon Unix, un mélange transparent avec les commandes natives et un **accès direct à toute l'API Java du JRE**.

```text
PJ C:\dev> ls -r --filter *.java | where { $_.size > 10kb }
PJ C:\dev> git status --porcelain | where { $_.startsWith(" M ") }
PJ C:\dev> java.util.List.of("apple", "banana", "orange") | where { $_.contains("b") }
PJ C:\dev> LocalDate.now().plusDays(10).dayOfWeek
```

```text
C:\> powerj -c "ls -r | where size > 1mb"
C:\> dir /b | powerj -c "where { $_.endsWith(\".txt\") }"
```

- Spécification : [docs/SPECIFICATION.md](docs/SPECIFICATION.md)
- Fiches de recette : [docs/recettes/](docs/recettes/)

## État

Étapes 5 et 5b : accès direct à l'API Java du JRE — appels statiques (`Math.max(3, 7)`, `java.util.List.of(…)`), champs (`Math.PI`), `new`, méthodes d'instance (`$l.stream().toList()`), imports par défaut et `import`, surcharges, varargs et conversions, casts `[long] 5`, lambdas à la Java (`ls -r | where { f -> f.size > 1mb }`, `$l.sort((a, b) -> a.length() - b.length())`), références de méthode (`map FileEntry::name`, `String::length`), cmdlet `map`, blocs de texte `"""`, exceptions Java lisibles (`$errors`, `$debug`), `help java.util.List`, `$( … )` dans les chaînes, second Ctrl+C pour abandonner un calcul bloqué. Étapes précédentes : pipeline `|` et `where`, mode non interactif ; cmdlets `ls` et `env`, objets et propriétés ; commandes natives, `cd`, `;` `&&` `||`, variables, redirections ; édition de ligne et historique. Voir le plan de développement (§11 de la spécification).

## Construire

Prérequis : **JDK 27** (Maven est fourni par le wrapper `mvnw`, version 3.9.11).

```bash
./mvnw verify                 # compilation + tests
./mvnw -Pdist verify          # + image applicative autonome (runtime jlink + lanceur jpackage)
```

L'image est produite dans `powerj-dist/target/jpackage/powerj/` (lanceur `bin/powerj` sous Linux, `powerj.exe` sous Windows), avec une archive zip dans `powerj-dist/target/`.

Sous Windows, l'installeur `.exe` se construit avec `mvnw.cmd -Pdist,installer verify` et nécessite [WiX](https://wixtoolset.org/) (`dotnet tool install --global wix --version 5.0.2`). La CI GitHub Actions le produit à chaque push.

Sans JDK 27 sous la main, on peut vérifier le build avec un JDK plus ancien supportant les fonctionnalités utilisées : `./mvnw -Djava.release=25 verify`.

## Structure

| Module | Rôle |
|---|---|
| `powerj-api` | API publique pour écrire des cmdlets |
| `powerj-core` | Analyse, interprétation, pipeline, interopérabilité Java |
| `powerj-cmdlets` | Cmdlets intégrés (`ls`, `where`, `env`) |
| `powerj-shell` | REPL et point d'entrée |
| `powerj-dist` | Distribution : jlink + jpackage |
