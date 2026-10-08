# Module d'exemple `greet`

Module tiers minimal pour PowerJ (spécification §4.3) : le cmdlet `greet --name Yves -c 2`.

- `pom.xml` : seule dépendance, `powerj-api`.
- `module-info.java` : `provides io.powerj.api.CmdletProvider with com.example.greet.GreetProvider`.
- `GreetParams` : record des options (`@Option`), `Greeting` : record produit, `Greet` : le cmdlet (`@CmdletInfo`).

```bash
./mvnw -pl examples/greet -am package     # → examples/greet/target/greet.jar
cp examples/greet/target/greet.jar ~/.powerj/modules/
```

Ou sans redémarrer PowerJ : `mod-load examples/greet/target/greet.jar`.
