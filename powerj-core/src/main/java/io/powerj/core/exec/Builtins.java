package io.powerj.core.exec;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/** Basic built-in commands: {@code cd}, {@code pwd}, {@code exit} (specification FR-04, FR-04b). */
final class Builtins {

    private Builtins() {
    }

    static List<Object> cd(List<Object> args, Session session) {
        if (args.size() > 1) {
            throw new PjException(Messages.get("cd.oneArgument"));
        }
        String target = args.isEmpty() ? "~" : Values.text(args.getFirst());
        Path path = switch (target) {
            case "-" -> session.previousDirectory()
                    .orElseThrow(() -> new PjException(Messages.get("cd.noPrevious")));
            case "~" -> session.home();
            case String t when t.startsWith("~/") || t.startsWith("~\\") -> session.home().resolve(t.substring(2));
            case String t when Platform.isWindows() && t.matches("[A-Za-z]:") -> Path.of(t + "\\");
            case String t -> parse(t);
        };
        session.changeDirectory(path);
        return List.of();
    }

    static List<Object> pwd(List<Object> args, Session session) {
        if (!args.isEmpty()) {
            throw new PjException(Messages.get("pwd.noArguments"));
        }
        return List.of(session.currentDirectory());
    }

    static List<Object> exit(List<Object> args, Session session) {
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

    private static Path parse(String path) {
        try {
            return Path.of(path);
        } catch (InvalidPathException e) {
            throw new PjException(Messages.get("cd.invalidPath", path));
        }
    }
}
