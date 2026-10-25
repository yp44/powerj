package io.powerj.core.runtime;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import io.powerj.core.command.CmdletRegistry;

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

    /** The cmdlet that runs under this name, if a built-in command does not take the name first (FR-13). */
    Optional<CmdletRegistry.Registered> cmdlet(String name);

    /** What a command name typed as text stands for, as execution would resolve it (FR-13); launches nothing. */
    Resolved resolve(String name);

    /** Kind of a command, for highlighting. */
    CommandKind commandKind(String name);
}
