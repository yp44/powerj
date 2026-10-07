# PowerJ

Shell interactif orienté objet écrit en Java 27 : les commandes renvoient des **objets Java** (records de préférence) dont on extrait les attributs dans un pipeline, avec des noms de commandes courts façon Unix, un mélange transparent avec les commandes natives et un **accès direct à toute l'API Java du JRE**.

```text
PJ C:\dev> ls -r --filter *.java | where { $_.size > 10kb }
PJ C:\dev> git status --porcelain | where { $_ like ' M *' }
PJ C:\dev> java.util.List.of("apple", "banana", "orange") | where { $_ like 'b*' }
```

- Spécification : [docs/SPECIFICATION.md](docs/SPECIFICATION.md)
- Build : Maven 3.9, Java 27 — distribution `powerj.exe` via jlink + jpackage.
