package io.powerj.core.nativeexec;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * PROTOTYPE of issue #128 (Windows), test code, never run on the machine that wrote it: starts a process with
 * {@code CreateProcessW} through the FFM API, optionally in a new process group ({@code CREATE_NEW_PROCESS_GROUP}: Ctrl+C
 * is disabled for the processes of the group), with the standard streams we choose, and only the handles we choose
 * inherited ({@code PROC_THREAD_ATTRIBUTE_HANDLE_LIST}). Its exit code is read by a thread that waits on the process handle.
 * <p>
 * {@code .bat} and {@code .cmd} files are refused: {@code cmd.exe} reads its command line by other rules, and the JDK has
 * checks for them (CVE-2024-24576) that this prototype does not copy. The launcher would hand them to {@link ProcessBuilder}.
 * <p>
 * All the downcalls use {@code invoke}, not {@code invokeExact}: nothing here could be run before the CI did.
 */
@SuppressWarnings("restricted")
final class WindowsSpawn {

    /** Flag of {@code CreateProcessW}: a console of its own for the new process. */
    static final int CREATE_NEW_CONSOLE = 0x10;
    /** Flag of {@code CreateProcessW}: a new process group, in which Ctrl+C is disabled. */
    static final int CREATE_NEW_PROCESS_GROUP = 0x200;
    static final int CTRL_C_EVENT = 0;
    static final int ERROR_BROKEN_PIPE = 109;
    static final int STILL_ACTIVE = 259;

    private static final int CREATE_UNICODE_ENVIRONMENT = 0x400;
    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x80000;
    private static final int STARTF_USESTDHANDLES = 0x100;
    private static final long PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x00020002L;
    private static final int GENERIC_READ = 0x80000000;
    private static final int GENERIC_WRITE = 0x40000000;
    private static final int FILE_APPEND_DATA = 0x4;
    private static final int FILE_SHARE_READ = 0x1;
    private static final int FILE_SHARE_READ_WRITE = 0x3;
    private static final int CREATE_ALWAYS = 2;
    private static final int OPEN_EXISTING = 3;
    private static final int OPEN_ALWAYS = 4;
    private static final int FILE_ATTRIBUTE_NORMAL = 0x80;
    private static final int HANDLE_FLAG_INHERIT = 1;
    private static final int INFINITE = 0xFFFFFFFF;
    private static final int STD_INPUT = -10;
    private static final int STD_OUTPUT = -11;
    private static final int STD_ERROR = -12;

    // Layouts on 64-bit Windows.
    private static final int STARTUPINFO_SIZE = 104;
    private static final int STARTUPINFOEX_SIZE = 112;
    private static final int SI_FLAGS = 60;
    private static final int SI_STDIN = 80;
    private static final int SI_STDOUT = 88;
    private static final int SI_STDERR = 96;
    private static final int SI_ATTRIBUTES = 104;
    private static final int PROCESS_INFORMATION_SIZE = 24;
    private static final int PI_PROCESS = 0;
    private static final int PI_THREAD = 8;
    private static final int PI_PID = 16;
    private static final int SECURITY_ATTRIBUTES_SIZE = 24;
    private static final int SA_INHERIT = 16;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
    private static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
    private static final VarHandle LAST_ERROR = CAPTURE.varHandle(PathElement.groupElement("GetLastError"));
    private static final Linker.Option CAPTURE_ERROR = Linker.Option.captureCallState("GetLastError");

    private static final MethodHandle CREATE_PROCESS = call("CreateProcessW", FunctionDescriptor.of(JAVA_INT,
            ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS), CAPTURE_ERROR);
    private static final MethodHandle CREATE_FILE = call("CreateFileW", FunctionDescriptor.of(ADDRESS,
            ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS), CAPTURE_ERROR);
    private static final MethodHandle CREATE_PIPE = call("CreatePipe",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT), CAPTURE_ERROR);
    private static final MethodHandle READ_FILE = call("ReadFile",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS), CAPTURE_ERROR);
    private static final MethodHandle INIT_ATTRIBUTES = call("InitializeProcThreadAttributeList",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS), CAPTURE_ERROR);
    private static final MethodHandle UPDATE_ATTRIBUTE = call("UpdateProcThreadAttribute", FunctionDescriptor.of(JAVA_INT,
            ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS), CAPTURE_ERROR);
    private static final MethodHandle DELETE_ATTRIBUTES = call("DeleteProcThreadAttributeList",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle CLOSE_HANDLE = call("CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle SET_HANDLE_INFORMATION = call("SetHandleInformation",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle GET_STD_HANDLE = call("GetStdHandle", FunctionDescriptor.of(ADDRESS, JAVA_INT));
    private static final MethodHandle WAIT = call("WaitForSingleObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle EXIT_CODE = call("GetExitCodeProcess",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle TERMINATE = call("TerminateProcess",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
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

    private WindowsSpawn() {
    }

    /** What a standard stream of the child is connected to. */
    record Stdio(Kind kind, Path file, long handle) {

        enum Kind { INHERIT, NULL, FILE, APPEND, HANDLE, STDOUT }

        static final Stdio INHERIT = new Stdio(Kind.INHERIT, null, 0);
        static final Stdio NULL = new Stdio(Kind.NULL, null, 0);
        /** {@code 2>&1}: only for standard error. */
        static final Stdio STDOUT = new Stdio(Kind.STDOUT, null, 0);

        static Stdio file(Path file) {
            return new Stdio(Kind.FILE, file, 0);
        }

        static Stdio append(Path file) {
            return new Stdio(Kind.APPEND, file, 0);
        }

        /** A handle that the caller owns (a pipe end): the child gets it, the caller closes its own copy. */
        static Stdio handle(long handle) {
            return new Stdio(Kind.HANDLE, null, handle);
        }
    }

    /**
     * The process to start. {@code flags}: extra flags of {@code CreateProcessW}. {@code handleList}: inherit only the
     * three standard handles; {@code false} inherits every inheritable handle (the control of the leak test).
     */
    record Spec(Path executable, List<String> args, Map<String, String> env, Path directory, Stdio in, Stdio out,
                Stdio err, int flags, boolean handleList, boolean redirect) {

        static Spec of(Path executable, String... args) {
            return new Spec(executable, List.of(args), System.getenv(), null, Stdio.NULL, Stdio.NULL, Stdio.NULL, 0, true, true);
        }

        Spec env(Map<String, String> env) {
            return new Spec(executable, args, env, directory, in, out, err, flags, handleList, redirect);
        }

        Spec directory(Path directory) {
            return new Spec(executable, args, env, directory, in, out, err, flags, handleList, redirect);
        }

        Spec in(Stdio in) {
            return new Spec(executable, args, env, directory, in, out, err, flags, handleList, redirect);
        }

        Spec out(Stdio out) {
            return new Spec(executable, args, env, directory, in, out, err, flags, handleList, redirect);
        }

        Spec err(Stdio err) {
            return new Spec(executable, args, env, directory, in, out, err, flags, handleList, redirect);
        }

        Spec flags(int flags) {
            return new Spec(executable, args, env, directory, in, out, err, this.flags | flags, handleList, redirect);
        }

        Spec handleList(boolean handleList) {
            return new Spec(executable, args, env, directory, in, out, err, flags, handleList, redirect);
        }

        /**
         * {@code false}: no standard handle is given and nothing is inherited, so that the child gets those of its
         * console (a new one with {@code CREATE_NEW_CONSOLE}), as a program started by a user in a terminal does.
         */
        Spec standardHandles(boolean redirect) {
            return new Spec(executable, args, env, directory, in, out, err, flags, handleList, redirect);
        }
    }

    /** A process started: its pid, and its exit code once the thread that waits on its handle has read it. */
    static final class Spawned {

        private final int pid;
        private final MemorySegment process;
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final Object lock = new Object();
        private boolean closed;

        private Spawned(int pid, MemorySegment process) {
            this.pid = pid;
            this.process = process;
            Thread.ofPlatform().daemon().name("powerj-wait-" + pid).start(this::await);
        }

        int pid() {
            return pid;
        }

        CompletableFuture<Integer> exit() {
            return exit;
        }

        /** {@code TerminateProcess}, with exit code 1 as the JDK does. {@code false} once the handle is closed. */
        boolean terminate() {
            synchronized (lock) {
                if (closed) {
                    return false;
                }
                try {
                    return (int) TERMINATE.invoke(process, 1) != 0;
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
            }
        }

        private void await() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment code = arena.allocate(JAVA_INT);
                int waited = (int) WAIT.invoke(process, INFINITE);
                int ok = (int) EXIT_CODE.invoke(process, code);
                int value = waited == 0 && ok != 0 ? code.get(JAVA_INT, 0) : -1;
                synchronized (lock) {
                    int ignored = (int) CLOSE_HANDLE.invoke(process);
                    closed = true;
                }
                exit.complete(value);
            } catch (Throwable t) {
                exit.completeExceptionally(t);
            }
        }
    }

    static Spawned spawn(Spec spec) throws IOException {
        String name = spec.executable().getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".bat") || name.endsWith(".cmd")) {
            throw new UnsupportedOperationException("batch files are left to ProcessBuilder: " + name);
        }
        List<Long> owned = new ArrayList<>();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment attributes = MemorySegment.NULL;
            boolean initialized = false;
            try {
                MemorySegment security = arena.allocate(SECURITY_ATTRIBUTES_SIZE, 8);
                security.set(JAVA_INT, 0, SECURITY_ATTRIBUTES_SIZE);
                security.set(JAVA_INT, SA_INHERIT, 1);
                boolean redirect = spec.redirect();
                MemorySegment in = MemorySegment.NULL;
                MemorySegment out = MemorySegment.NULL;
                MemorySegment err = MemorySegment.NULL;
                if (redirect) {
                    in = standard(spec.in(), true, STD_INPUT, security, arena, owned);
                    out = standard(spec.out(), false, STD_OUTPUT, security, arena, owned);
                    err = spec.err().kind() == Stdio.Kind.STDOUT ? out
                            : standard(spec.err(), false, STD_ERROR, security, arena, owned);
                }

                MemorySegment startup = arena.allocate(STARTUPINFOEX_SIZE, 8);
                startup.set(JAVA_INT, 0, redirect && spec.handleList() ? STARTUPINFOEX_SIZE : STARTUPINFO_SIZE);
                if (redirect) {
                    startup.set(JAVA_INT, SI_FLAGS, STARTF_USESTDHANDLES);
                    startup.set(ADDRESS, SI_STDIN, in);
                    startup.set(ADDRESS, SI_STDOUT, out);
                    startup.set(ADDRESS, SI_STDERR, err);
                }
                int flags = CREATE_UNICODE_ENVIRONMENT | spec.flags();
                if (redirect && spec.handleList()) {
                    // The same handle twice in the list is an error (2>&1 shares one).
                    Set<Long> inherited = new LinkedHashSet<>();
                    for (MemorySegment handle : List.of(in, out, err)) {
                        inherited.add(handle.address());
                    }
                    MemorySegment list = arena.allocate(8L * inherited.size(), 8);
                    int i = 0;
                    for (long handle : inherited) {
                        list.setAtIndex(ADDRESS, i++, MemorySegment.ofAddress(handle));
                    }
                    MemorySegment size = arena.allocate(JAVA_LONG);
                    MemorySegment capture = arena.allocate(CAPTURE);
                    int ignored = (int) INIT_ATTRIBUTES.invoke(capture, MemorySegment.NULL, 1, 0, size); // asks the size
                    attributes = arena.allocate(size.get(JAVA_LONG, 0), 16);
                    check("InitializeProcThreadAttributeList",
                            (int) INIT_ATTRIBUTES.invoke(capture, attributes, 1, 0, size), capture);
                    initialized = true;
                    check("UpdateProcThreadAttribute", (int) UPDATE_ATTRIBUTE.invoke(capture, attributes, 0,
                            PROC_THREAD_ATTRIBUTE_HANDLE_LIST, list, 8L * inherited.size(), MemorySegment.NULL,
                            MemorySegment.NULL), capture);
                    startup.set(ADDRESS, SI_ATTRIBUTES, attributes);
                    flags |= EXTENDED_STARTUPINFO_PRESENT;
                }

                MemorySegment application = arena.allocateFrom(spec.executable().toString(), StandardCharsets.UTF_16LE);
                MemorySegment commandLine = arena.allocateFrom(
                        WindowsCommandLine.of(spec.executable().toString(), spec.args()), StandardCharsets.UTF_16LE);
                MemorySegment environment = arena.allocateFrom(WindowsCommandLine.environmentBlock(spec.env()),
                        StandardCharsets.UTF_16LE);
                // The block ends with two NULs of its own; allocateFrom adds one more, which does no harm.
                MemorySegment directory = spec.directory() == null ? MemorySegment.NULL
                        : arena.allocateFrom(spec.directory().toString(), StandardCharsets.UTF_16LE);
                MemorySegment info = arena.allocate(PROCESS_INFORMATION_SIZE, 8);
                MemorySegment capture = arena.allocate(CAPTURE);
                int created = (int) CREATE_PROCESS.invoke(capture, application, commandLine, MemorySegment.NULL,
                        MemorySegment.NULL, redirect ? 1 : 0, flags, environment, directory, startup, info);
                if (created == 0) {
                    throw new IOException("CreateProcess error=" + lastError(capture));
                }
                int ignored = (int) CLOSE_HANDLE.invoke(info.get(ADDRESS, PI_THREAD));
                return new Spawned(info.get(JAVA_INT, PI_PID), info.get(ADDRESS, PI_PROCESS));
            } finally {
                if (initialized) {
                    DELETE_ATTRIBUTES.invoke(attributes);
                }
                owned.forEach(WindowsSpawn::close);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    /** The handle for one standard stream: opened here (and closed after the start), or borrowed. */
    private static MemorySegment standard(Stdio stdio, boolean input, int which, MemorySegment security, Arena arena,
                                          List<Long> owned) throws Throwable {
        switch (stdio.kind()) {
            case INHERIT -> {
                MemorySegment handle = (MemorySegment) GET_STD_HANDLE.invoke(which);
                if (handle.address() == 0 || handle.address() == -1) {
                    return open("NUL", input ? GENERIC_READ : GENERIC_WRITE, FILE_SHARE_READ_WRITE, OPEN_EXISTING,
                            security, arena, owned);
                }
                int ignored = (int) SET_HANDLE_INFORMATION.invoke(handle, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT);
                return handle;
            }
            case NULL -> {
                return open("NUL", input ? GENERIC_READ : GENERIC_WRITE, FILE_SHARE_READ_WRITE, OPEN_EXISTING, security,
                        arena, owned);
            }
            case FILE -> {
                return open(stdio.file().toString(), GENERIC_WRITE, FILE_SHARE_READ, CREATE_ALWAYS, security, arena, owned);
            }
            case APPEND -> {
                return open(stdio.file().toString(), FILE_APPEND_DATA, FILE_SHARE_READ_WRITE, OPEN_ALWAYS, security, arena,
                        owned);
            }
            case HANDLE -> {
                MemorySegment handle = MemorySegment.ofAddress(stdio.handle());
                int ignored = (int) SET_HANDLE_INFORMATION.invoke(handle, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT);
                return handle;
            }
            default -> throw new IllegalArgumentException(stdio.kind() + " is not a handle of its own");
        }
    }

    private static MemorySegment open(String name, int access, int share, int disposition, MemorySegment security,
                                      Arena arena, List<Long> owned) throws Throwable {
        MemorySegment capture = arena.allocate(CAPTURE);
        MemorySegment handle = (MemorySegment) CREATE_FILE.invoke(capture,
                arena.allocateFrom(name, StandardCharsets.UTF_16LE), access, share, security, disposition,
                FILE_ATTRIBUTE_NORMAL, MemorySegment.NULL);
        if (handle.address() == -1) {
            throw new IOException("CreateFile " + name + " error=" + lastError(capture));
        }
        owned.add(handle.address());
        return handle;
    }

    // --- small calls the tests need ---

    /** An anonymous pipe whose two ends can be inherited: {@code {read, write}}. */
    static long[] pipe() throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment security = arena.allocate(SECURITY_ATTRIBUTES_SIZE, 8);
            security.set(JAVA_INT, 0, SECURITY_ATTRIBUTES_SIZE);
            security.set(JAVA_INT, SA_INHERIT, 1);
            MemorySegment read = arena.allocate(JAVA_LONG);
            MemorySegment write = arena.allocate(JAVA_LONG);
            MemorySegment capture = arena.allocate(CAPTURE);
            check("CreatePipe", (int) CREATE_PIPE.invoke(capture, read, write, security, 0), capture);
            return new long[] {read.get(JAVA_LONG, 0), write.get(JAVA_LONG, 0)};
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    static void close(long handle) {
        try {
            int ignored = (int) CLOSE_HANDLE.invoke(MemorySegment.ofAddress(handle));
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Reads one byte, blocking. Returns 0 if a byte came, else the Win32 error ({@link #ERROR_BROKEN_PIPE}: no writer left). */
    static int readOneByte(long handle) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(CAPTURE);
            int ok = (int) READ_FILE.invoke(capture, MemorySegment.ofAddress(handle), arena.allocate(1), 1,
                    arena.allocate(JAVA_INT), MemorySegment.NULL);
            return ok != 0 ? 0 : lastError(capture);
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

    private static void check(String what, int result, MemorySegment capture) throws IOException {
        if (result == 0) {
            throw new IOException(what + " error=" + lastError(capture));
        }
    }

    private static int lastError(MemorySegment capture) {
        return (int) LAST_ERROR.get(capture, 0L);
    }

    private static MethodHandle call(String name, FunctionDescriptor descriptor, Linker.Option... options) {
        return LINKER.downcallHandle(KERNEL32.find(name)
                .orElseThrow(() -> new IllegalStateException("not in kernel32: " + name)), descriptor, options);
    }
}
