package com.example.greet;

import java.time.Instant;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;

/** {@code greet --name Yves -c 3}: greets someone, {@code count} times. */
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
