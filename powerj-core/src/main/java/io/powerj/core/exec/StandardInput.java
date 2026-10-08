package io.powerj.core.exec;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Nature of the process's standard input, read through the FFM API ({@code isatty}, {@code GetConsoleMode}). */
public final class StandardInput {

    private static final Logger LOG = Logger.getLogger(StandardInput.class.getName());

    /** {@code STD_INPUT_HANDLE} of the Windows API. */
    private static final int STD_INPUT_HANDLE = -10;

    private StandardInput() {
    }

    /** {@code true} if standard input is a terminal (keyboard), {@code false} if it is a file or a pipe. */
    public static boolean isTerminal() {
        try {
            return Platform.isWindows() ? windowsConsole() : posixTty();
        } catch (Throwable t) {
            LOG.log(Level.FINE, "Nature de l'entrée standard inconnue", t);
            return System.console() != null && System.console().isTerminal();
        }
    }

    private static boolean posixTty() throws Throwable {
        Linker linker = Linker.nativeLinker();
        MemorySegment isatty = linker.defaultLookup().find("isatty").orElseThrow();
        var handle = linker.downcallHandle(isatty, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        return (int) handle.invokeExact(0) == 1;
    }

    private static boolean windowsConsole() throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        var getStdHandle = linker.downcallHandle(kernel32.find("GetStdHandle").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        var getConsoleMode = linker.downcallHandle(kernel32.find("GetConsoleMode").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        var stdin = (MemorySegment) getStdHandle.invokeExact(STD_INPUT_HANDLE);
        try (var arena = Arena.ofConfined()) {
            MemorySegment mode = arena.allocate(ValueLayout.JAVA_INT);
            return (int) getConsoleMode.invokeExact(stdin, mode) != 0;
        }
    }
}
