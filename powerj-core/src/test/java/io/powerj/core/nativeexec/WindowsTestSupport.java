package io.powerj.core.nativeexec;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

/**
 * Windows calls that only the tests of {@link WindowsSpawn} make (issue #128): the console, its Ctrl+C, the handles of this
 * process. They are here and not in {@code WindowsSpawn} so that the production class binds only what it uses: a missing
 * function would otherwise make the whole launcher unavailable. Loaded on Windows only.
 */
@SuppressWarnings("restricted")
final class WindowsTestSupport {

    /** Flag of {@code CreateProcessW}: a console of its own for the new process. */
    static final int CREATE_NEW_CONSOLE = 0x10;
    static final int CTRL_C_EVENT = 0;
    static final int ERROR_BROKEN_PIPE = 109;

    private static final int HANDLE_FLAG_INHERIT = 1;
    private static final int STD_OUTPUT = -11;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
    private static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
    private static final VarHandle LAST_ERROR = CAPTURE.varHandle(PathElement.groupElement("GetLastError"));

    private static final MethodHandle READ_FILE = call("ReadFile",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
            Linker.Option.captureCallState("GetLastError"));
    private static final MethodHandle GET_HANDLE_INFORMATION = call("GetHandleInformation",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GET_STD_HANDLE = call("GetStdHandle", FunctionDescriptor.of(ADDRESS, JAVA_INT));
    private static final MethodHandle CURRENT_PROCESS = call("GetCurrentProcess", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle HANDLE_COUNT = call("GetProcessHandleCount",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle SET_CTRL_HANDLER = call("SetConsoleCtrlHandler",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle GENERATE_CTRL_EVENT = call("GenerateConsoleCtrlEvent",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle CONSOLE_WINDOW = call("GetConsoleWindow", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle CONSOLE_PROCESSES = call("GetConsoleProcessList",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    private WindowsTestSupport() {
    }

    /** Reads one byte, blocking. Returns 0 if a byte came, else the Win32 error ({@link #ERROR_BROKEN_PIPE}: no writer left). */
    static int readOneByte(long handle) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(CAPTURE);
            int ok = (int) READ_FILE.invoke(capture, MemorySegment.ofAddress(handle), arena.allocate(1), 1,
                    arena.allocate(JAVA_INT), MemorySegment.NULL);
            return ok != 0 ? 0 : (int) LAST_ERROR.get(capture, 0L);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** The number of handles the JVM holds open. */
    static int handleCount() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment count = arena.allocate(JAVA_INT);
            int ignored = (int) HANDLE_COUNT.invoke((MemorySegment) CURRENT_PROCESS.invoke(), count);
            return count.get(JAVA_INT, 0);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** {@code SetConsoleCtrlHandler}; a NULL routine with {@code add} turns Ctrl+C processing on or off for the process. */
    static boolean setCtrlHandler(MemorySegment routine, boolean add) {
        try {
            return (int) SET_CTRL_HANDLER.invoke(routine, add ? 1 : 0) != 0;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** {@code GenerateConsoleCtrlEvent}: to every process of the console of the caller when the group is 0. */
    static boolean ctrlEvent(int event, int group) {
        try {
            return (int) GENERATE_CTRL_EVENT.invoke(event, group) != 0;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** The address of the window of the console of this process, 0 if it has none. */
    static long consoleWindow() {
        try {
            return ((MemorySegment) CONSOLE_WINDOW.invoke()).address();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** The ids of the processes attached to the console of this process (this one included). */
    static int[] consoleProcessIds() {
        try (Arena arena = Arena.ofConfined()) {
            int room = 256;
            MemorySegment ids = arena.allocate(4L * room, 4);
            int count = (int) CONSOLE_PROCESSES.invoke(ids, room);
            int[] result = new int[Math.min(count, room)];
            for (int i = 0; i < result.length; i++) {
                result[i] = ids.getAtIndex(JAVA_INT, i);
            }
            return result;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** {@code true} if the standard output handle of this process can be inherited by a child it starts. */
    static boolean standardOutputInheritable() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment handle = (MemorySegment) GET_STD_HANDLE.invoke(STD_OUTPUT);
            MemorySegment flags = arena.allocate(JAVA_INT);
            int ok = (int) GET_HANDLE_INFORMATION.invoke(handle, flags);
            return ok != 0 && (flags.get(JAVA_INT, 0) & HANDLE_FLAG_INHERIT) != 0;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static MethodHandle call(String name, FunctionDescriptor descriptor, Linker.Option... options) {
        return LINKER.downcallHandle(KERNEL32.find(name)
                .orElseThrow(() -> new IllegalStateException("not in kernel32: " + name)), descriptor, options);
    }
}
