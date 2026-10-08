package io.powerj.core.exec;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Code page of the Windows console, read through the FFM API ({@code GetConsoleOutputCP}, or failing that
 * {@code GetOEMCP}), without JNI.
 */
final class WindowsConsole {

    private static final Logger LOG = Logger.getLogger(WindowsConsole.class.getName());

    private WindowsConsole() {
    }

    /** Output code page of the console, or OEM code page if the process has no console. */
    static OptionalInt outputCodePage() {
        if (!Platform.isWindows()) {
            return OptionalInt.empty();
        }
        try {
            int console = call("GetConsoleOutputCP");
            int codePage = console != 0 ? console : call("GetOEMCP");
            return codePage == 0 ? OptionalInt.empty() : OptionalInt.of(codePage);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "Unreadable console code page", t);
            return OptionalInt.empty();
        }
    }

    private static int call(String function) throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", java.lang.foreign.Arena.global());
        Optional<java.lang.foreign.MemorySegment> symbol = kernel32.find(function);
        if (symbol.isEmpty()) {
            return 0;
        }
        MethodHandle handle = linker.downcallHandle(symbol.get(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
        return (int) handle.invokeExact();
    }
}
