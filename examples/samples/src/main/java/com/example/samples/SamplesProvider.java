package com.example.samples;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletProvider;

/** Declares the module's sample cmdlets (discovered by {@code ServiceLoader}). */
public final class SamplesProvider implements CmdletProvider {

    @Override
    public List<Cmdlet<?, ?, ?>> cmdlets() {
        return List.of(new Passthrough(), new EchoOptions());
    }

    /** Translations of the summaries, options and category ({@code messages_en/fr.properties}). */
    @Override
    public ResourceBundle messages(Locale locale) {
        return ResourceBundle.getBundle("com.example.samples.messages", locale);
    }
}
