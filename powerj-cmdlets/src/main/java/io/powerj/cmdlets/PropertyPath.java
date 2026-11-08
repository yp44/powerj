package io.powerj.cmdlets;

import java.util.Objects;

import io.powerj.api.CmdletContext;
import io.powerj.api.ScriptBlock;
import io.powerj.api.UsageException;

/**
 * The block of a property, or of a path of properties, that a user typed as an argument of a cmdlet ({@code sort size},
 * {@code --property owner.name}): it reads the names one after the other through the context
 * ({@link CmdletContext#readProperty}), as every command reads the properties of an object. <b>The case of each name
 * matters</b>: {@code size} is not {@code Size}. An object that has no such property gives an error that names the property
 * (and the name in another case, if there is one), as {@code $_.size} does.
 */
final class PropertyPath implements ScriptBlock {

    private final String path;
    private final String[] names;
    private final CmdletContext<?> context;

    private PropertyPath(String path, CmdletContext<?> context) {
        this.path = path;
        this.names = path.split("\\.");
        this.context = context;
    }

    /**
     * @param path a property name or a path of them ({@code size}, {@code owner.name})
     * @throws UsageException if {@code path} is neither
     */
    static ScriptBlock of(String path, CmdletContext<?> context) {
        if (!Texts.isPropertyPath(path)) {
            throw new UsageException(Messages.get("where.property.invalid", path));
        }
        return new PropertyPath(path, Objects.requireNonNull(context, "context"));
    }

    @Override
    public Object invoke(Object current) {
        Object value = current;
        for (String name : names) {
            value = context.readProperty(value, name);
        }
        return value;
    }

    @Override
    public String source() {
        return "$_." + path;
    }
}
