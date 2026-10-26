package io.powerj.cmdlets;


import io.powerj.api.CmdletContext;
import io.powerj.api.ScriptBlock;

/**
 * Text received by {@code from-json} and {@code from-csv} (specification FR-73): a {@code String} from the pipeline,
 * or the property of each object named by {@code --property}. A {@code String} is always text, never a file name.
 */
final class PipelineText {

    private final String property;
    private final ScriptBlock block;
    private final String notTextKey;

    /**
     * @param notTextKey message key of an object that is not a text, without {@code --property} ({@code {0}}: the
     *                   object)
     * @throws IllegalArgumentException for an invalid property name
     */
    PipelineText(String property, CmdletContext<?> context, String notTextKey) {
        this.property = property;
        this.block = property == null ? null : Keys.block(property, context, "where.property.invalid");
        this.notTextKey = notTextKey;
    }

    /** Text of an object, or {@code null} (after an error, or a {@code null} property) to skip it. */
    String text(Object input, CmdletContext<?> context) {
        if (block == null) {
            if (input instanceof CharSequence text) {
                return text.toString();
            }
            context.error(Messages.get(notTextKey, Texts.abbreviate(input)));
            return null;
        }
        var attempt = block.tryInvoke(input);
        if (attempt.failed()) {
            Skipped.report(context, attempt.error(), input);
            return null;
        }
        Object value = attempt.value();
        if (value == null || value instanceof CharSequence) {
            return value == null ? null : value.toString();
        }
        context.error(Messages.get("text.property.not.text", property, Texts.abbreviate(value)));
        return null;
    }
}
