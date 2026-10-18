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
        return List.of(new Ls(), new Grep(), new Cat(), new Where(), new MapCmdlet(), new Sort(), new First(),
                new Last(), new Range(), new Select(), new Count(),
                new Measure(), new GroupCmdlet(), new MakeDirectory(),
                new Touch(), new Copy(), new Move(), new Remove(), new Collect(), new Env());
    }

    @Override
    public ResourceBundle messages(Locale locale) {
        return ResourceBundle.getBundle("io.powerj.cmdlets.messages", locale);
    }
}
