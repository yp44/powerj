package com.example.greet;

import java.util.List;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletProvider;

/** Déclare les cmdlets du module (découvert par {@code ServiceLoader}). */
public final class GreetProvider implements CmdletProvider {

    @Override
    public List<Cmdlet<?, ?, ?>> cmdlets() {
        return List.of(new Greet());
    }
}
