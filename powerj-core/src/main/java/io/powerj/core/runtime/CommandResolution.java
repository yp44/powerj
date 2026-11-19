package io.powerj.core.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.nativeexec.CommandResolver;
import io.powerj.core.nativeexec.NativeCommands;

/**
 * The rule that gives a command name its meaning (specification FR-13), written once: a built-in command comes first,
 * then a cmdlet, then a program on the {@code PATH}; {@code ^name} skips the first two.
 * <p>
 * Execution, {@code which}, syntax highlighting and completion used to apply the rule each in its own way (completion
 * ignored the precedence of the built-in commands); they now ask this class.
 */
public final class CommandResolution {

    private final Map<String, Builtin> builtins;
    private final CmdletRegistry registry;
    private final CommandResolver natives;
    private final NativeCommands nativeNames = new NativeCommands();

    /**
     * @param builtins the built-in commands: a live view, the shell adds its own before the first line runs
     */
    public CommandResolution(Map<String, Builtin> builtins, CmdletRegistry registry, CommandResolver natives) {
        this.builtins = builtins;
        this.registry = registry;
        this.natives = natives;
    }

    /**
     * What the word being typed stands for, to colour it: as {@link #resolve(String, Session)}, except that a bare word is looked
     * up in the cached names of the {@code PATH} instead of on the file system, which the highlighting would do at every keystroke
     * (audit v2, S-08). A word with a directory part ({@code ./run}, {@code bin/tool}) is still resolved on the file system: that
     * is one path, not the whole {@code PATH}.
     */
    public CommandKind kind(String text, Session session) {
        boolean forcedNative = text.length() > 1 && text.startsWith("^");
        String name = forcedNative ? text.substring(1) : text;
        if (!forcedNative) {
            Optional<Resolved> inProcess = inProcess(name);
            if (inProcess.isPresent()) {
                return kindOf(inProcess.get());
            }
        }
        if (name.isEmpty()) {
            return CommandKind.UNKNOWN;
        }
        if (name.contains("/") || name.contains("\\")) {
            return kindOf(resolve(text, session));
        }
        return nativeNames.has(session.environment(), name) ? CommandKind.NATIVE : CommandKind.UNKNOWN;
    }

    private static CommandKind kindOf(Resolved resolved) {
        return switch (resolved) {
            case Resolved.BuiltinCall _ -> CommandKind.BUILTIN;
            case Resolved.CmdletCall _ -> CommandKind.CMDLET;
            case Resolved.NativeCall _ -> CommandKind.NATIVE;
            case Resolved.Unknown _ -> CommandKind.UNKNOWN;
        };
    }

    /**
     * What a name typed as text stands for, with the {@code ^} prefix that forces a program. Looks at the file system
     * only when no built-in command or cmdlet has the name.
     */
    public Resolved resolve(String text, Session session) {
        boolean forcedNative = text.length() > 1 && text.startsWith("^");
        return resolve(forcedNative ? text.substring(1) : text, forcedNative, session);
    }

    /** What a name stands for; {@code forcedNative} comes from the parser, which already took the {@code ^} off. */
    public Resolved resolve(String name, boolean forcedNative, Session session) {
        if (!forcedNative) {
            Optional<Resolved> inProcess = inProcess(name);
            if (inProcess.isPresent()) {
                return inProcess.get();
            }
        }
        if (name.isEmpty()) {
            return new Resolved.Unknown(name, forcedNative);
        }
        return natives.resolve(name, session.currentDirectory(), session.environment()).<Resolved>map(Resolved.NativeCall::new)
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

    /** Every cmdlet that is registered. */
    List<CmdletRegistry.Registered> cmdlets() {
        return registry.all();
    }

    /** The cmdlet that runs under this name, if a built-in command does not take the name first. */
    public Optional<CmdletRegistry.Registered> cmdlet(String name) {
        return inProcess(name).flatMap(resolved ->
                resolved instanceof Resolved.CmdletCall(var cmdlet) ? Optional.of(cmdlet) : Optional.empty());
    }

    /** The names of the programs of the {@code PATH}, cached: shared by the completion and the hint of an unknown command. */
    public NativeCommands nativeNames() {
        return nativeNames;
    }

    /** Names of the built-in commands, in the order they were registered. */
    public Set<String> builtinNames() {
        return Collections.unmodifiableSet(builtins.keySet());
    }
}
