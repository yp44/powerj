# PowerJ

Shell interactif orienté objet écrit en Java 27 : les commandes renvoient des **records Java** dont on extrait les attributs dans un pipeline, avec des noms de commandes courts façon Unix et un mélange transparent avec les commandes natives.

```text
PJ C:\dev> ls -r --filter *.java | where { $_.size > 10kb }
PJ C:\dev> git status --porcelain | where { $_ like ' M *' }
```

- Spécification : [docs/SPECIFICATION.md](docs/SPECIFICATION.md)
- Build : Maven 3.9, Java 27 — distribution `powerj.exe` via jlink + jpackage.
