package io.powerj.fx;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletProvider;
import io.powerj.fx.grid.GridCmdlet;

/** Declares the cmdlets of the module (discovered by {@code ServiceLoader}). JavaFX is not started here. */
public final class FxProvider implements CmdletProvider {

    @Override
    public List<Cmdlet<?, ?, ?>> cmdlets() {
        return List.of(new GridCmdlet());
    }

    /** Translations of the summaries, options and category ({@code messages_en/fr.properties}). */
    @Override
    public ResourceBundle messages(Locale locale) {
        return ResourceBundle.getBundle("io.powerj.fx.messages", locale);
    }
}
