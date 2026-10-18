package io.powerj.cmdlets;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;

import io.powerj.api.CmdletContext;
import io.powerj.api.ScriptBlock;

/**
 * Files designated by pipeline objects (specification FR-65, FR-72): a {@code String} (a path), a {@code Path}, a
 * {@code File} or a {@link FileEntry}; with {@code --property <name>}, that property of the objects. Shared by
 * {@code cat} and the file operations, each with its own messages.
 */
final class PipelineFiles {

    /**
     * Message keys of a cmdlet.
     *
     * @param notAFile         object that designates no file ({@code {0}}: the object)
     * @param propertyNotAFile property that is not a file ({@code {0}}: the property, {@code {1}}: the object)
     * @param invalidPath      invalid path ({@code {0}}: the text, {@code {1}}: the reason)
     */
    record Keys(String notAFile, String propertyNotAFile, String invalidPath) {
    }

    private final String propertyName;
    private final ScriptBlock property;
    private final Keys keys;

    /** @param propertyName {@code --property}, or {@code null} for the objects themselves */
    PipelineFiles(String propertyName, CmdletContext<?> context, Keys keys) {
        this.propertyName = propertyName;
        this.property = propertyName == null ? null : compile(propertyName, context);
        this.keys = keys;
    }

    boolean hasProperty() {
        return property != null;
    }

    private static ScriptBlock compile(String name, CmdletContext<?> context) {
        if (!Where.PROPERTY.matcher(name).matches()) {
            throw new IllegalArgumentException(Messages.get("where.property.invalid", name));
        }
        return context.compile("$_." + name);
    }

    /** File designated by a pipeline object, or {@code null} after reporting why there is none. */
    Path file(Object input, CmdletContext<?> context) {
        Object value = input;
        if (property != null) {
            try {
                value = property.invoke(input);
            } catch (CancellationException e) {
                throw e;
            } catch (RuntimeException e) {
                context.error(Messages.get("pipeline.object.skipped", e.getMessage(), Where.abbreviate(input)));
                return null;
            }
        }
        Path file;
        try {
            file = value instanceof String text ? context.currentDirectory().resolve(text)
                    : FileArguments.file(value, context);
        } catch (InvalidPathException e) {
            context.error(Messages.get(keys.invalidPath(), value, e.getMessage()));
            return null;
        }
        if (file == null) {
            context.error(property == null
                    ? Messages.get(keys.notAFile(), Where.abbreviate(input))
                    : Messages.get(keys.propertyNotAFile(), propertyName, Where.abbreviate(input)));
        }
        return file;
    }
}
