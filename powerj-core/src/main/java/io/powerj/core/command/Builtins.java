package io.powerj.core.command;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import io.powerj.core.error.PjException;
import io.powerj.core.format.Values;
import io.powerj.core.i18n.Messages;
import io.powerj.core.nativeexec.Platform;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.ShellIo;

/**
 * Built-in commands: {@code cd}, {@code pwd}, {@code exit} (specification FR-04, FR-04b), {@code which} (FR-13),
 * {@code import} (FR-47) and {@code mod-load}, {@code mod-list} (FR-77). The last three need a part of the
 * interpreter; they are built from it.
 */
public final class Builtins {

    private Builtins() {
    }

    public static List<Object> cd(List<Object> args, Session session) {
        if (args.size() > 1) {
            throw new PjException(Messages.get("cd.oneArgument"));
        }
        if (args.isEmpty()) {
            session.changeDirectory(session.home()); // like cd ~ (the ~ of an argument is already expanded, FR-62)
            return List.of();
        }
        String target = Values.text(args.getFirst());
        Path path = switch (target) {
            case "-" -> session.previousDirectory()
                    .orElseThrow(() -> new PjException(Messages.get("cd.noPrevious")));
            case String t when Platform.isWindows() && t.matches("[A-Za-z]:") -> Path.of(t + "\\");
            case String t -> parse(t);
        };
        session.changeDirectory(path);
        return List.of();
    }

    public static List<Object> pwd(List<Object> args, Session session) {
        if (!args.isEmpty()) {
            throw new PjException(Messages.get("pwd.noArguments"));
        }
        return List.of(session.currentDirectory());
    }

    public static List<Object> exit(List<Object> args, Session session) {
        int code = switch (args.size()) {
            case 0 -> 0;
            case 1 -> {
                try {
                    yield Integer.parseInt(Values.text(args.getFirst()));
                } catch (NumberFormatException _) {
                    throw new PjException(Messages.get("exit.invalidCode", Values.text(args.getFirst())));
                }
            }
            default -> throw new PjException(Messages.get("exit.oneArgument"));
        };
        session.requestExit(code);
        return List.of();
    }

    /** {@code which name…}: what each name stands for, as execution would resolve it (FR-13). */
    public static Builtin which(Supplier<CommandResolution> resolution) {
        return (names, session) -> {
            if (names.isEmpty()) {
                throw new PjException(Messages.get("which.nameExpected"));
            }
            List<Object> lines = new ArrayList<>();
            for (Object value : names) {
                String name = Values.text(value);
                lines.add(switch (resolution.get().resolve(name, session)) {
                    case Resolved.BuiltinCall _ -> Messages.get("which.builtin", name);
                    case Resolved.CmdletCall(var cmdlet) -> Messages.get("which.cmdlet", name, cmdlet.module());
                    case Resolved.NativeCall(var path) -> Messages.get("which.native", name, path);
                    case Resolved.Unknown(var program, var forcedNative) -> throw new PjException(Messages.get(
                            forcedNative ? "which.programNotFound" : "which.notFound", forcedNative ? program : name));
                });
            }
            return lines;
        };
    }

    /** {@code import java.security.*}, {@code import javax.crypto.Cipher}; without arguments: active imports (FR-47). */
    public static List<Object> importClasses(List<Object> args, Session session) {
        if (args.isEmpty()) {
            return List.copyOf(session.java().imports());
        }
        for (Object arg : args) {
            session.java().addImport(Values.text(arg));
        }
        return List.of();
    }

    /** {@code mod-load <path.jar>}: loads a module at runtime. */
    public static Builtin modLoad(ModuleLoader modules, ShellIo io) {
        return (args, session) -> {
            if (args.isEmpty()) {
                throw new PjException(Messages.get("module.load.pathExpected"));
            }
            List<Object> loaded = new ArrayList<>();
            for (Object arg : args) {
                String text = Values.text(arg);
                Path path = session.currentDirectory().resolve(text); // ~ already expanded (FR-62)
                ModuleLoader.Result result = modules.load(path);
                result.warnings().forEach(io.errors());
                if (result.cmdlets().isEmpty() && !result.warnings().isEmpty()) {
                    throw new PjException(Messages.get("module.load.notLoaded", path.getFileName()));
                }
                loaded.addAll(result.cmdlets());
            }
            return loaded;
        };
    }

    /** {@code mod-list}: loaded modules and their cmdlets. */
    public static Builtin modList(CmdletRegistry registry) {
        return (args, _) -> {
            if (!args.isEmpty()) {
                throw new PjException(Messages.get("module.list.noArguments"));
            }
            return List.copyOf(registry.modules());
        };
    }

    private static Path parse(String path) {
        try {
            return Path.of(path);
        } catch (InvalidPathException e) {
            throw new PjException(Messages.get("cd.invalidPath", path));
        }
    }
}
