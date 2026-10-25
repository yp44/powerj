package io.powerj.core.assist;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import io.powerj.core.assist.Completions.Candidate;
import io.powerj.core.assist.Completions.Result;
import io.powerj.core.i18n.Messages;
import io.powerj.core.nativeexec.NativeCommands;
import io.powerj.core.runtime.CommandCatalog;

/** Suggestions of the name of a command: built-in commands, cmdlets, imported classes and programs (FR-21). */
final class CommandCompleter {

    private final CommandCatalog catalog;
    private final NativeCommands natives;
    private final PackageCompleter packages;

    CommandCompleter(CommandCatalog catalog, NativeCommands natives, PackageCompleter packages) {
        this.catalog = catalog;
        this.natives = natives;
        this.packages = packages;
    }

    Result commands(String line, Frame frame, int identStart, String fragment, boolean preview) {
        String before = line.substring(frame.stageStart(), identStart).strip();
        List<Candidate> candidates = new ArrayList<>();
        if (!before.equals("^")) {
            for (String builtin : preview ? List.<String>of() : new TreeSet<>(catalog.builtinNames())) {
                candidates.add(new Candidate(builtin, builtin + " " + Messages.get("completion.tag.builtin"),
                        Messages.get("completion.builtin"), true));
            }
            for (var cmdlet : catalog.cmdlets()) {
                if (preview && !cmdlet.preview()) {
                    continue;
                }
                candidates.add(new Candidate(cmdlet.name(), cmdlet.name() + " " + Messages.get("completion.tag.cmdlet"),
                        cmdlet.summary(), true));
            }
            if (!fragment.isEmpty() && Character.isUpperCase(fragment.charAt(0))) {
                packages.importedClasses().forEach(c -> candidates.add(new Candidate(c, c, Messages.get("completion.javaClass"), false)));
            }
        }
        for (String name : preview ? Set.<String>of() : natives.names(catalog.session().environment())) {
            candidates.add(new Candidate(name, name + " " + Messages.get("completion.tag.native"),
                    Messages.get("completion.program"), true));
        }
        return new Result(identStart, fragment, candidates);
    }
}
