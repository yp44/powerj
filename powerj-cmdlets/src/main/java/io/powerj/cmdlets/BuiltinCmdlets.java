package io.powerj.cmdlets;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletProvider;

/** Cmdlets built into PowerJ. */
public final class BuiltinCmdlets implements CmdletProvider {

    @Override
    public List<Cmdlet<?, ?, ?>> cmdlets() {
        return List.of(new Ls(), new Where(), new MapCmdlet(), new Collect(), new Env());
    }

    @Override
    public ResourceBundle messages(Locale locale) {
        return ResourceBundle.getBundle("io.powerj.cmdlets.messages", locale);
    }
}
