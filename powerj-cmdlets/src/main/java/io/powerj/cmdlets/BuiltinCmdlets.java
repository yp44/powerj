package io.powerj.cmdlets;

import java.io.IOException;
import java.nio.file.Path;
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
                new Measure(), new GroupCmdlet(), new Join(), new MakeDirectory(),
                new Touch(), new Copy(), new Move(), new Remove(), new ToJson(), new FromJson(), new ToCsv(),
                new FromCsv(), new Ps(), new Kill(), new Collect(), new Env(), new Perf());
    }

    /** The list of the paths that {@code rm} and {@code mv} refuse to remove (FR-72) is written once, at first launch. */
    @Override
    public void initialize(Path configDirectory) throws IOException {
        ProtectedPaths.writeDefaultIfMissing(configDirectory);
    }

    @Override
    public ResourceBundle messages(Locale locale) {
        return ResourceBundle.getBundle("io.powerj.cmdlets.messages", locale);
    }
}
