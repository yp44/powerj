# PowerJ

Shell interactif orienté objet écrit en Java 27 : les commandes renvoient des **objets Java** (records de préférence) dont on extrait les attributs dans un pipeline, avec des noms de commandes courts façon Unix, un mélange transparent avec les commandes natives et un **accès direct à toute l'API Java du JRE**.

```text
PJ C:\dev> ls -r --filter *.java | where { $_.size > 10kb }
PJ C:\dev> git status --porcelain | where { $_.startsWith(" M ") }
PJ C:\dev> java.util.List.of("apple", "banana", "orange") | where { $_.contains("b") }
```

- Spécification : [docs/SPECIFICATION.md](docs/SPECIFICATION.md)
- Fiches de recette : [docs/recettes/](docs/recettes/)

## État

Étape 0 (squelette) : REPL minimal — bannière, prompt, `exit [code]`. Voir le plan de développement (§11 de la spécification).

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
