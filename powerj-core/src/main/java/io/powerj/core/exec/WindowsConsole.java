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
 * Page de code de la console Windows, lue par l'API FFM ({@code GetConsoleOutputCP}, à défaut
 * {@code GetOEMCP}), sans JNI.
 */
final class WindowsConsole {

    private static final Logger LOG = Logger.getLogger(WindowsConsole.class.getName());

    private WindowsConsole() {
    }

    /** Page de code de sortie de la console, ou page OEM si le process n'a pas de console. */
    static OptionalInt outputCodePage() {
        if (!Platform.isWindows()) {
            return OptionalInt.empty();
        }
        try {
            int console = call("GetConsoleOutputCP");
            int codePage = console != 0 ? console : call("GetOEMCP");
            return codePage == 0 ? OptionalInt.empty() : OptionalInt.of(codePage);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "Page de code console illisible", t);
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
