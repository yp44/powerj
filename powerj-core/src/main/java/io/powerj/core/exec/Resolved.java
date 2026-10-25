package io.powerj.core.exec;

import java.nio.file.Path;

/**
 * What a command name stands for (FR-13): the first of a built-in command, a cmdlet and a program on the
 * {@code PATH}. {@link CommandResolution} is the one place that decides; execution, {@code which}, highlighting and
 * completion all read its answer.
 */
sealed interface Resolved {

    /** A built-in command, run by the shell itself. */
    record BuiltinCall(String name, Builtin builtin) implements Resolved { }

    /** A cmdlet, built in or from a module. */
    record CmdletCall(CmdletRegistry.Registered cmdlet) implements Resolved { }

    /** A program: found on the {@code PATH}, or by the path written, or forced with {@code ^}. */
    record NativeCall(Path executable) implements Resolved { }

    /**
     * Nothing of that name.
     *
     * @param name           the name looked up, without the {@code ^}
     * @param forcedNative   the {@code ^} prefix asked for a program, so built-in commands and cmdlets were not looked at
     */
    record Unknown(String name, boolean forcedNative) implements Resolved { }
}
