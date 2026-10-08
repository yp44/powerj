package com.example.greet;

import java.util.List;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletProvider;

/** Declares the module's cmdlets (discovered by {@code ServiceLoader}). */
public final class GreetProvider implements CmdletProvider {

    @Override
    public List<Cmdlet<?, ?, ?>> cmdlets() {
        return List.of(new Greet());
    }
}
