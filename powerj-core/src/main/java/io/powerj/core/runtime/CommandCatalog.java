package io.powerj.core.runtime;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.nativeexec.NativeCommands;

/**
 * What the assistance (completion, highlighting) asks of the interpreter: which commands exist, what a name stands
 * for, and the session whose state it reads. Nothing here runs anything, so the assistance depends on this view and
 * not on the {@link Interpreter} that executes.
 */
public interface CommandCatalog {

    /** The session that completion reads (variables, current directory, imports). */
    Session session();

    /** Names of the built-in commands. */
    Set<String> builtinNames();

    /** Every cmdlet that is registered. */
    Collection<CmdletRegistry.Registered> cmdlets();

    /**
     * Every cmdlet that a name qualified by its module reaches ({@code greet:greet}, FR-17): those of {@link #cmdlets()} and
     * those that a namesake shadows.
     */
    default Collection<CmdletRegistry.Registered> qualifiedCmdlets() {
        return cmdlets();
    }

    /** The cmdlet that runs under this name, if a built-in command does not take the name first (FR-13). */
    Optional<CmdletRegistry.Registered> cmdlet(String name);

    /** What a command name typed as text stands for, as execution would resolve it (FR-13); launches nothing. */
    Resolved resolve(String name);

    /**
     * The names of the programs of the {@code PATH}, with the cache that completion fills and that the error of an unknown
     * command reads. A catalog that does not share one gives each caller its own.
     */
    default NativeCommands nativeCommands() {
        return new NativeCommands();
    }

    /** Kind of a command, for highlighting. */
    CommandKind commandKind(String name);
}
