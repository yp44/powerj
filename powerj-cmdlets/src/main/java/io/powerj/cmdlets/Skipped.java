package io.powerj.cmdlets;

import io.powerj.api.CmdletContext;

/**
 * What a cmdlet does when a block fails for one object (FR-36): the error is non-blocking, it is reported, and the
 * object is skipped. The blocks are evaluated with {@code ScriptBlock.tryInvoke}, which lets a cancellation through.
 */
final class Skipped {

    private Skipped() {
    }

    static void report(CmdletContext<?> context, RuntimeException error, Object input) {
        context.error(Messages.get("pipeline.object.skipped", error.getMessage(), Where.abbreviate(input)));
    }
}
