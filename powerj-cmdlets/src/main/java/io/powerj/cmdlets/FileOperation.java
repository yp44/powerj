package io.powerj.cmdlets;

import java.nio.file.Path;
import java.util.Objects;

import io.powerj.api.Display;

/**
 * Action that a file operation would do, emitted with {@code --dry-run} (specification FR-72).
 *
 * @param action {@code mkdir}, {@code touch}, {@code copy}, {@code move} or {@code remove}
 * @param path   path created, updated, copied, moved or removed
 * @param target destination of a copy or a move; {@code null} otherwise
 */
@Display(columns = {"action", "path", "target"})
public record FileOperation(String action, Path path, Path target) {

    public FileOperation {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(path, "path");
    }
}
