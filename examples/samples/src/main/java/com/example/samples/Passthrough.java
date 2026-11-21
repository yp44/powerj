package com.example.samples;

import java.util.Optional;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;

/**
 * {@code ls | passthrough}: the simplest cmdlet that reads the pipeline. Each object received is emitted again, in
 * {@code process}; nothing happens in {@code begin} or {@code end}, and there is no state.
 * <p>
 * {@code --skip-null} drops the {@code null} objects; {@code --optional} emits {@code Optional.ofNullable(object)}
 * instead of the object. With both, the nulls are dropped first, so no {@code Optional.empty()} comes out. The shell
 * unrolls an {@code Optional} before the next stage (FR-30b): the {@code Optional}s are seen at the end of a line or in a
 * variable, while a next cmdlet receives their values.
 * <p>
 * {@code passThrough} is left {@code false} in {@link CmdletInfo}: with {@code --optional} the output is not the input,
 * and the annotation cannot depend on the options. Tab completion therefore does not look through this cmdlet.
 */
@CmdletInfo(name = "passthrough", preview = true, category = "Examples",
        summary = "Emits each object it receives, optionally without the nulls or wrapped in an Optional",
        examples = {"ls | passthrough", "ls | passthrough --optional", "java.util.Arrays.asList(\"a\", null) | passthrough -n"})
public final class Passthrough implements Cmdlet<PassthroughParams, Object, Object> {

    @Override
    public void process(PassthroughParams p, Object input, CmdletContext<Object> ctx) {
        if (input == null && p.skipNull()) {
            return;
        }
        ctx.emit(p.optional() ? Optional.ofNullable(input) : input);
    }
}
