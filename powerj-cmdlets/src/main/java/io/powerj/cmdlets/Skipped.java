package io.powerj.cmdlets;

import io.powerj.api.CmdletContext;

/**
 * What a cmdlet does when a block fails for one object (FR-36): the error is non-blocking, it is reported, and the
 * object is skipped. The blocks are evaluated with {@code ScriptBlock.tryInvoke}, which lets a cancellation through.
 */
final class Skipped {

    private Skipped() {
    }

    /** Reports {@code error} for {@code input}; an exception without a message is named by its type, never "null". */
    static void report(CmdletContext<?> context, RuntimeException error, Object input) {
        context.error(Messages.get("pipeline.object.skipped",
                error.getMessage() != null ? error.getMessage() : error.toString(), Texts.abbreviate(input)));
    }
}
