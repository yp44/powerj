# Example module `greet`

Minimal third-party module for PowerJ (specification §4.3): the `greet --name Yves -c 2` cmdlet.

- `pom.xml`: single dependency, `powerj-api`.
- `module-info.java`: `provides io.powerj.api.CmdletProvider with com.example.greet.GreetProvider`.
- `GreetParams`: options record (`@Option`), `Greeting`: output record, `Greet`: the cmdlet (`@CmdletInfo`).

```bash
./mvnw -pl examples/greet -am package     # → examples/greet/target/greet.jar
cp examples/greet/target/greet.jar ~/.powerj/modules/
```

Or without restarting PowerJ: `mod-load examples/greet/target/greet.jar`.
