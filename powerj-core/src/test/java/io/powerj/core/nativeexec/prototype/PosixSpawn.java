package io.powerj.core.nativeexec.prototype;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * PROTOTYPE of issue #128, not shipped (test code): starts a process with {@code posix_spawn} through the FFM API, in
 * the process group of our choice, with file actions for the redirections, and reads its exit status with
 * {@code waitpid} on a thread of its own. Linux (glibc 2.34 or later) only: the flags, the {@code O_*} values and
 * {@code addclosefrom_np} differ on macOS.
 */
@SuppressWarnings("restricted")
final class PosixSpawn {

    // Linux (glibc) values; macOS: SETPGROUP 0x02, SETSIGDEF 0x04, SETSIGMASK 0x08, CLOEXEC_DEFAULT 0x4000, O_CREAT
    // 0x200, O_TRUNC 0x400, O_APPEND 0x8.
    private static final short POSIX_SPAWN_SETPGROUP = 0x02;
    private static final short POSIX_SPAWN_SETSIGDEF = 0x04;
    private static final short POSIX_SPAWN_SETSIGMASK = 0x08;
    private static final int O_RDONLY = 0;
    private static final int O_WRONLY = 1;
    private static final int O_CREAT = 0100;
    private static final int O_TRUNC = 01000;
    private static final int O_APPEND = 02000;
    private static final int EINTR = 4;
    static final int SIGINT = 2;
    static final int SIGKILL = 9;
    static final int SIGTERM = 15;

    /** No process group of our own: the child stays in the group of the JVM. */
    static final int KEEP_GROUP = -1;
    /** A new group, whose id is the pid of the child. */
    static final int OWN_GROUP = 0;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final ReentrantLock SPAWN_LOCK = new ReentrantLock();
    private static final AtomicInteger LOST_STATUSES = new AtomicInteger();

    private static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO = CAPTURE.varHandle(PathElement.groupElement("errno"));

    private static final MethodHandle SPAWN = handle("posix_spawn", FunctionDescriptor.of(JAVA_INT,
            ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle ATTR_INIT = handle("posix_spawnattr_init", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle ATTR_DESTROY = handle("posix_spawnattr_destroy",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle ATTR_FLAGS = handle("posix_spawnattr_setflags",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_SHORT));
    private static final MethodHandle ATTR_PGROUP = handle("posix_spawnattr_setpgroup",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle ATTR_SIGMASK = handle("posix_spawnattr_setsigmask",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle ATTR_SIGDEF = handle("posix_spawnattr_setsigdefault",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle SIGEMPTYSET = handle("sigemptyset", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle SIGFILLSET = handle("sigfillset", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle FA_INIT = handle("posix_spawn_file_actions_init",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle FA_DESTROY = handle("posix_spawn_file_actions_destroy",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle FA_DUP2 = handle("posix_spawn_file_actions_adddup2",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle FA_OPEN = handle("posix_spawn_file_actions_addopen",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle FA_CHDIR = handle("posix_spawn_file_actions_addchdir_np",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle FA_CLOSEFROM = handle("posix_spawn_file_actions_addclosefrom_np",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle PIPE = handle("pipe", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle CLOSE = handle("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle KILL = handle("kill", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle GETPGID = handle("getpgid", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle GETPGRP = handle("getpgrp", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle STRERROR = handle("strerror", FunctionDescriptor.of(ADDRESS, JAVA_INT));
    private static final MethodHandle WAITPID = LINKER.downcallHandle(
            LINKER.defaultLookup().find("waitpid").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), Linker.Option.captureCallState("errno"));

    private PosixSpawn() {
    }

    /** What a standard stream of the child is connected to. */
    record Stdio(Kind kind, Path file, int fd) {

        enum Kind { INHERIT, NULL, FILE, APPEND, FD, STDOUT }

        static final Stdio INHERIT = new Stdio(Kind.INHERIT, null, -1);
        static final Stdio NULL = new Stdio(Kind.NULL, null, -1);
        /** {@code 2>&1}: only for standard error. */
        static final Stdio STDOUT = new Stdio(Kind.STDOUT, null, -1);

        static Stdio file(Path file) {
            return new Stdio(Kind.FILE, file, -1);
        }

        static Stdio append(Path file) {
            return new Stdio(Kind.APPEND, file, -1);
        }

        static Stdio fd(int fd) {
            return new Stdio(Kind.FD, null, fd);
        }
    }

    /** The process to start. {@code directory} may be null (the directory of the JVM). */
    record Spec(Path executable, List<String> args, Map<String, String> env, Path directory, Stdio in, Stdio out,
                Stdio err, int group, boolean closeFds) {

        static Spec of(Path executable, String... args) {
            return new Spec(executable, List.of(args), Map.of("PATH", "/usr/bin:/bin"), null, Stdio.NULL, Stdio.NULL,
                    Stdio.NULL, KEEP_GROUP, true);
        }

        Spec group(int group) {
            return new Spec(executable, args, env, directory, in, out, err, group, closeFds);
        }

        Spec in(Stdio in) {
            return new Spec(executable, args, env, directory, in, out, err, group, closeFds);
        }

        Spec out(Stdio out) {
            return new Spec(executable, args, env, directory, in, out, err, group, closeFds);
        }

        Spec err(Stdio err) {
            return new Spec(executable, args, env, directory, in, out, err, group, closeFds);
        }

        Spec env(Map<String, String> env) {
            return new Spec(executable, args, env, directory, in, out, err, group, closeFds);
        }

        Spec directory(Path directory) {
            return new Spec(executable, args, env, directory, in, out, err, group, closeFds);
        }

        Spec closeFds(boolean closeFds) {
            return new Spec(executable, args, env, directory, in, out, err, group, closeFds);
        }
    }

    /** A process started: its pid, and its exit code (128 + signal if a signal ended it) once waitpid has returned. */
    record Spawned(int pid, CompletableFuture<Integer> exit) { }

    /** The number of children whose status {@code waitpid} could not give. */
    static int lostStatuses() {
        return LOST_STATUSES.get();
    }

    static Spawned spawn(Spec spec) throws IOException {
        int pid;
        SPAWN_LOCK.lock();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment attr = arena.allocate(1024, 16);
            MemorySegment actions = arena.allocate(1024, 16);
            MemorySegment mask = arena.allocate(256, 8);
            MemorySegment defaults = arena.allocate(256, 8);
            ok("posix_spawnattr_init", (int) ATTR_INIT.invokeExact(attr));
            try {
                ok("sigemptyset", (int) SIGEMPTYSET.invokeExact(mask));
                ok("sigfillset", (int) SIGFILLSET.invokeExact(defaults));
                ok("setsigmask", (int) ATTR_SIGMASK.invokeExact(attr, mask));
                ok("setsigdefault", (int) ATTR_SIGDEF.invokeExact(attr, defaults));
                short flags = (short) (POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETSIGDEF);
                if (spec.group() >= 0) {
                    ok("setpgroup", (int) ATTR_PGROUP.invokeExact(attr, spec.group()));
                    flags |= POSIX_SPAWN_SETPGROUP;
                }
                ok("setflags", (int) ATTR_FLAGS.invokeExact(attr, flags));
                ok("file_actions_init", (int) FA_INIT.invokeExact(actions));
                try {
                    fileActions(actions, spec, arena);
                    MemorySegment path = arena.allocateFrom(spec.executable().toString());
                    MemorySegment argv = strings(arena, prepend(spec.executable().toString(), spec.args()));
                    List<String> environment = new ArrayList<>();
                    spec.env().forEach((name, value) -> environment.add(name + "=" + value));
                    MemorySegment envp = strings(arena, environment);
                    MemorySegment pidOut = arena.allocate(JAVA_INT);
                    int rc = (int) SPAWN.invokeExact(pidOut, path, actions, attr, argv, envp);
                    if (rc != 0) {
                        throw new IOException("error=" + rc + ", " + strerror(rc));
                    }
                    pid = pidOut.get(JAVA_INT, 0);
                } finally {
                    int ignored = (int) FA_DESTROY.invokeExact(actions);
                }
            } finally {
                int ignored = (int) ATTR_DESTROY.invokeExact(attr);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        } finally {
            SPAWN_LOCK.unlock();
        }
        var exit = new CompletableFuture<Integer>();
        Thread.ofPlatform().daemon().name("proto-waitpid-" + pid).start(() -> waitFor(pid, exit));
        return new Spawned(pid, exit);
    }

    private static void fileActions(MemorySegment actions, Spec spec, Arena arena) throws Throwable {
        if (spec.directory() != null) {
            ok("addchdir_np", (int) FA_CHDIR.invokeExact(actions, arena.allocateFrom(spec.directory().toString())));
        }
        stdio(actions, arena, 0, spec.in());
        stdio(actions, arena, 1, spec.out());
        stdio(actions, arena, 2, spec.err());
        if (spec.closeFds()) {
            ok("addclosefrom_np", (int) FA_CLOSEFROM.invokeExact(actions, 3));
        }
    }

    private static void stdio(MemorySegment actions, Arena arena, int fd, Stdio stdio) throws Throwable {
        int write = O_WRONLY | O_CREAT;
        switch (stdio.kind()) {
            case INHERIT -> { }
            case NULL -> open(actions, arena, fd, "/dev/null", fd == 0 ? O_RDONLY : O_WRONLY);
            case FILE -> open(actions, arena, fd, stdio.file().toString(), fd == 0 ? O_RDONLY : write | O_TRUNC);
            case APPEND -> open(actions, arena, fd, stdio.file().toString(), write | O_APPEND);
            case FD -> ok("adddup2", (int) FA_DUP2.invokeExact(actions, stdio.fd(), fd));
            case STDOUT -> ok("adddup2", (int) FA_DUP2.invokeExact(actions, 1, fd));
        }
    }

    private static void open(MemorySegment actions, Arena arena, int fd, String path, int flags) throws Throwable {
        ok("addopen", (int) FA_OPEN.invokeExact(actions, fd, arena.allocateFrom(path), flags, 0666));
    }

    private static List<String> prepend(String first, List<String> rest) {
        List<String> all = new ArrayList<>(rest.size() + 1);
        all.add(first);
        all.addAll(rest);
        return all;
    }

    private static MemorySegment strings(Arena arena, List<String> values) {
        MemorySegment array = arena.allocate(8L * (values.size() + 1), 8);
        for (int i = 0; i < values.size(); i++) {
            array.setAtIndex(ADDRESS, i, arena.allocateFrom(values.get(i)));
        }
        array.setAtIndex(ADDRESS, values.size(), MemorySegment.NULL);
        return array;
    }

    private static void waitFor(int pid, CompletableFuture<Integer> exit) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(CAPTURE);
            MemorySegment status = arena.allocate(JAVA_INT);
            while (true) {
                int result = (int) WAITPID.invokeExact(capture, pid, status, 0);
                if (result == pid) {
                    exit.complete(decode(status.get(JAVA_INT, 0)));
                    return;
                }
                int errno = (int) ERRNO.get(capture, 0L);
                if (errno != EINTR) {
                    LOST_STATUSES.incrementAndGet();
                    exit.completeExceptionally(new IOException("waitpid(" + pid + "): errno=" + errno));
                    return;
                }
            }
        } catch (Throwable t) {
            LOST_STATUSES.incrementAndGet();
            exit.completeExceptionally(t);
        }
    }

    /** {@code WIFEXITED}: the code; {@code WIFSIGNALED}: 128 + the signal, as {@code java.lang.Process} does. */
    static int decode(int status) {
        int signal = status & 0x7f;
        return signal == 0 ? (status >> 8) & 0xff : 128 + signal;
    }

    // --- small calls the tests need ---

    static int[] pipe() throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment fds = arena.allocate(8, 4);
            ok("pipe", (int) PIPE.invokeExact(fds));
            return new int[] {fds.getAtIndex(JAVA_INT, 0), fds.getAtIndex(JAVA_INT, 1)};
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    static void close(int fd) {
        try {
            int ignored = (int) CLOSE.invokeExact(fd);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** {@code kill(pid, signal)}; a negative pid is a process group. Returns 0, or -1 if there is no such process. */
    static int kill(int pid, int signal) {
        try {
            return (int) KILL.invokeExact(pid, signal);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static int getpgid(int pid) {
        try {
            return (int) GETPGID.invokeExact(pid);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static int getpgrp() {
        try {
            return (int) GETPGRP.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static String strerror(int errno) throws Throwable {
        MemorySegment text = (MemorySegment) STRERROR.invokeExact(errno);
        return text.reinterpret(256).getString(0);
    }

    private static void ok(String what, int rc) throws IOException {
        if (rc != 0) {
            String message;
            try {
                message = strerror(rc);
            } catch (Throwable t) {
                message = t.toString();
            }
            throw new IOException(what + ": error=" + rc + ", " + message);
        }
    }

    private static MethodHandle handle(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(LINKER.defaultLookup().find(name)
                .orElseThrow(() -> new IllegalStateException("not in libc: " + name)), descriptor);
    }
}
