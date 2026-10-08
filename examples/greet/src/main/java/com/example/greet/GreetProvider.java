package com.example.greet;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletProvider;

/** Declares the module's cmdlets (discovered by {@code ServiceLoader}). */
public final class GreetProvider implements CmdletProvider {

    @Override
    public List<Cmdlet<?, ?, ?>> cmdlets() {
        return List.of(new Greet());
    }

    /** Translations of the summary, options and category ({@code messages_en/fr.properties}). */
    @Override
    public ResourceBundle messages(Locale locale) {
        return ResourceBundle.getBundle("com.example.greet.messages", locale);
    }
}
