package io.powerj.cmdlets;

import java.util.ArrayList;
import java.util.List;

import io.powerj.api.CmdletContext;
import io.powerj.api.ScriptBlock;

/**
 * Keys given to a cmdlet ({@code sort size}, {@code sort { $_.name.length() }}): a property name or path is compiled
 * into the block {@code { $_.<property> }}, a block or a method reference is kept as is.
 */
final class Keys {

    private Keys() {
    }

    /**
     * Block of one key.
     *
     * @param invalidKey message key used when the argument is neither a property nor a block ({@code {0}}: the value)
     * @throws IllegalArgumentException for an invalid property name or an argument of another kind
     */
    static ScriptBlock block(Object argument, CmdletContext<?> context, String invalidKey) {
        return switch (argument) {
            case ScriptBlock block -> block;
            case String property when Where.PROPERTY.matcher(property).matches() -> context.compile("$_." + property);
            case String property -> throw new IllegalArgumentException(Messages.get("where.property.invalid", property));
            case null -> throw new IllegalArgumentException(Messages.get(invalidKey, "null"));
            default -> throw new IllegalArgumentException(Messages.get(invalidKey, argument));
        };
    }

    /** Blocks of the keys, in order; an empty list when no key is given. */
    static List<ScriptBlock> blocks(List<Object> arguments, CmdletContext<?> context, String invalidKey) {
        var blocks = new ArrayList<ScriptBlock>();
        if (arguments != null) {
            for (Object argument : arguments) {
                blocks.add(block(argument, context, invalidKey));
            }
        }
        return blocks;
    }
}
