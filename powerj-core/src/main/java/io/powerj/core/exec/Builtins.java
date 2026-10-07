package io.powerj.core.exec;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/** Commandes internes de base : {@code cd}, {@code pwd}, {@code exit} (spécification FR-04, FR-04b). */
final class Builtins {

    private Builtins() {
    }

    static List<Object> cd(List<String> args, Session session) {
        if (args.size() > 1) {
            throw new PjException("cd : un seul argument attendu");
        }
        String target = args.isEmpty() ? "~" : args.getFirst();
        Path path = switch (target) {
            case "-" -> session.previousDirectory()
                    .orElseThrow(() -> new PjException("cd : pas de dossier précédent"));
            case "~" -> session.home();
            case String t when t.startsWith("~/") || t.startsWith("~\\") -> session.home().resolve(t.substring(2));
            case String t when Platform.isWindows() && t.matches("[A-Za-z]:") -> Path.of(t + "\\");
            case String t -> parse(t);
        };
        session.changeDirectory(path);
        return List.of();
    }

    static List<Object> pwd(List<String> args, Session session) {
        if (!args.isEmpty()) {
            throw new PjException("pwd : aucun argument attendu");
        }
        return List.of(session.currentDirectory());
    }

    static List<Object> exit(List<String> args, Session session) {
        int code = switch (args.size()) {
            case 0 -> 0;
            case 1 -> {
                try {
                    yield Integer.parseInt(args.getFirst());
                } catch (NumberFormatException _) {
                    throw new PjException("exit : code retour invalide '" + args.getFirst() + "'");
                }
            }
            default -> throw new PjException("exit : un seul argument attendu");
        };
        session.requestExit(code);
        return List.of();
    }

    private static Path parse(String path) {
        try {
            return Path.of(path);
        } catch (InvalidPathException e) {
            throw new PjException("cd : chemin invalide : " + path);
        }
    }
}
