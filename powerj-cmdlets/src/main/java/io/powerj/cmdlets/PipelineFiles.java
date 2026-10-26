package io.powerj.cmdlets;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import io.powerj.api.CmdletContext;
import io.powerj.api.ScriptBlock;
import io.powerj.api.UsageException;

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
    private final ScriptBlock block;
    private final Keys keys;

    /** @param propertyName {@code --property}, or {@code null} for the objects themselves */
    PipelineFiles(String propertyName, CmdletContext<?> context, Keys keys) {
        this(propertyName, null, context, keys);
    }

    /**
     * @param propertyName {@code --property}, or {@code null}
     * @param block        block evaluated for each object to give its path ({@code touch { $_.name }}), or
     *                     {@code null}; a {@code null} result skips the object without error (FR-72)
     */
    PipelineFiles(String propertyName, ScriptBlock block, CmdletContext<?> context, Keys keys) {
        this.propertyName = propertyName;
        this.property = propertyName == null ? null : compile(propertyName, context);
        this.block = block;
        this.keys = keys;
    }

    boolean hasProperty() {
        return property != null;
    }

    private static ScriptBlock compile(String name, CmdletContext<?> context) {
        if (!Texts.isPropertyPath(name)) {
            throw new UsageException(Messages.get("where.property.invalid", name));
        }
        return context.property(name);
    }

    /** File designated by a pipeline object, or {@code null} after reporting why there is none. */
    Path file(Object input, CmdletContext<?> context) {
        Object value = input;
        ScriptBlock evaluated = block != null ? block : property;
        if (evaluated != null) {
            var attempt = evaluated.tryInvoke(input);
            if (attempt.failed()) {
                Skipped.report(context, attempt.error(), input);
                return null;
            }
            value = attempt.value();
        }
        if (block != null && value == null) {
            return null; // the block chose to skip this object
        }
        Path file;
        try {
            file = value instanceof String text ? context.currentDirectory().resolve(text)
                    : FileArguments.file(value, context);
        } catch (InvalidPathException e) {
            context.error(Messages.get(keys.invalidPath(), value, e.getMessage()));
            return null;
        }
        if (file == null && block != null) {
            context.error(Messages.get("fileops.block.not.a.file", block.source(), Texts.abbreviate(value)));
        } else if (file == null) {
            context.error(property == null
                    ? Messages.get(keys.notAFile(), Texts.abbreviate(input))
                    : Messages.get(keys.propertyNotAFile(), propertyName, Texts.abbreviate(input)));
        }
        return file;
    }
}
