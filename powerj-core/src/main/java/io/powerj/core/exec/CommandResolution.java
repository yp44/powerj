package io.powerj.core.exec;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The rule that gives a command name its meaning (specification FR-13), written once: a built-in command comes first,
 * then a cmdlet, then a program on the {@code PATH}; {@code ^name} skips the first two.
 * <p>
 * Execution, {@code which}, syntax highlighting and completion used to apply the rule each in its own way (completion
 * ignored the precedence of the built-in commands); they now ask this class.
 */
final class CommandResolution {

    private final Map<String, Builtin> builtins;
    private final CmdletRegistry registry;
    private final CommandResolver natives;

    /**
     * @param builtins the built-in commands: a live view, the shell adds its own before the first line runs
     */
    CommandResolution(Map<String, Builtin> builtins, CmdletRegistry registry, CommandResolver natives) {
        this.builtins = builtins;
        this.registry = registry;
        this.natives = natives;
    }

    /**
     * What a name typed as text stands for, with the {@code ^} prefix that forces a program. Looks at the file system
     * only when no built-in command or cmdlet has the name.
     */
    Resolved resolve(String text, Session session) {
        boolean forcedNative = text.length() > 1 && text.startsWith("^");
        return resolve(forcedNative ? text.substring(1) : text, forcedNative, session);
    }

    /** What a name stands for; {@code forcedNative} comes from the parser, which already took the {@code ^} off. */
    Resolved resolve(String name, boolean forcedNative, Session session) {
        if (!forcedNative) {
            Optional<Resolved> inProcess = inProcess(name);
            if (inProcess.isPresent()) {
                return inProcess.get();
            }
        }
        if (name.isEmpty()) {
            return new Resolved.Unknown(name, forcedNative);
        }
        return natives.resolve(name, session).<Resolved>map(Resolved.NativeCall::new)
                .orElseGet(() -> new Resolved.Unknown(name, forcedNative));
    }

    /**
     * The built-in command or the cmdlet of that name, in that order: what is answered without looking at the file
     * system, so it is cheap enough for the parser, which asks on every keystroke.
     */
    Optional<Resolved> inProcess(String name) {
        Builtin builtin = builtins.get(name);
        if (builtin != null) {
            return Optional.of(new Resolved.BuiltinCall(name, builtin));
        }
        return registry.find(name).map(Resolved.CmdletCall::new);
    }

    /** The cmdlet that runs under this name, if a built-in command does not take the name first. */
    Optional<CmdletRegistry.Registered> cmdlet(String name) {
        return inProcess(name).flatMap(resolved ->
                resolved instanceof Resolved.CmdletCall(var cmdlet) ? Optional.of(cmdlet) : Optional.empty());
    }

    /** Names of the built-in commands, in the order they were registered. */
    Set<String> builtinNames() {
        return Collections.unmodifiableSet(builtins.keySet());
    }
}
