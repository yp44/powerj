package com.example.samples;

import io.powerj.api.Attempt;
import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.ScriptBlock;

/**
 * {@code echo-options -t hello -c 3 --size 10kb}: emits one {@link OptionValues} holding the value received for each
 * option, so that the author of a cmdlet sees what the shell gives a cmdlet for what the user typed. It reads no
 * pipeline and changes nothing.
 * <p>
 * <b>A block is evaluated by the cmdlet, not by the shell.</b> The shell turns {@code { … }} into a {@link ScriptBlock}
 * and passes it on; the cmdlet decides when to evaluate it and with which {@code $_}, as every built-in cmdlet does
 * ({@code where} once per object). This one evaluates it once, with {@code $_} equal to {@code null} since it has no
 * current object, as {@code join --right} does. {@code ( … )} is evaluated by the shell before the call: the cmdlet then
 * receives the value, not a block.
 */
@CmdletInfo(name = "echo-options", preview = true, category = "Examples",
        summary = "Emits the value received for each of its options, one option of each kind",
        examples = {"echo-options", "echo-options -t hello -c 3 --size 10kb --timeout 1.5s --level high",
                "echo-options --block { 1 + 2 }", "echo-options --tag a --tag b one two | select tags words"})
public final class EchoOptions implements Cmdlet<EchoOptionsParams, Void, OptionValues> {

    @Override
    public void begin(EchoOptionsParams p, CmdletContext<OptionValues> ctx) {
        ctx.emit(new OptionValues(p.words(), p.text(), p.count(), p.number(), p.size(), p.ratio(), p.factor(),
                p.small(), p.tiny(), p.letter(), p.verbose(), p.timeout(), p.path(), p.level(), p.tags(), p.block(),
                p.pattern(), valueOf(p.block(), ctx)));
    }

    /**
     * The result of {@code block} if it is a {@link ScriptBlock}, evaluated once with no current object; any other value
     * as it is. A block that fails is a non-blocking error and gives {@code null}: the object is still emitted.
     * {@code tryInvoke} throws a cancellation (Ctrl+C) instead of returning it.
     */
    private static Object valueOf(Object block, CmdletContext<?> ctx) {
        if (!(block instanceof ScriptBlock script)) {
            return block;
        }
        Attempt<Object> attempt = script.tryInvoke(null);
        if (attempt.failed()) {
            // an exception without a message is named by its type, never "null", as the built-in cmdlets do
            RuntimeException error = attempt.error();
            ctx.error(Messages.get("echo-options.block.failed",
                    error.getMessage() != null ? error.getMessage() : error.toString()));
            return null;
        }
        return attempt.value();
    }
}
