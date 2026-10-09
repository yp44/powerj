package io.powerj.cmdlets;

import java.util.concurrent.CancellationException;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.ScriptBlock;

/**
 * {@code map}: transforms each object of the pipeline, like {@code Stream.map} (specification FR-36c).
 * <pre>
 * ls -r | map { f -> f.name + " : " + f.name.length() }
 * ls | map FileEntry::name
 * </pre>
 * A {@code null} result emits nothing; a collection is unrolled by the pipeline.
 */
@CmdletInfo(name = "map", category = "Filters", summary = "Transforms each pipeline object with a lambda",
        examples = {"ls -r | map { f -> f.name + \" : \" + f.name.length() }", "ls | map FileEntry::name",
                "ls | map { $_.name.toUpperCase() }", "env | map EnvVar::name"})
public final class MapCmdlet implements Cmdlet<MapCmdlet.Params, Object, Object> {

    /** Parameters of {@code map}. */
    public record Params(
            @Option(position = 0, mandatory = true,
                    description = "Transformation: lambda { f -> … }, block { $_… } or method reference Type::method")
            Object function) {
    }

    @Override
    public void begin(Params p, CmdletContext<Object> context) {
        if (!(p.function() instanceof ScriptBlock)) {
            throw new IllegalArgumentException(Messages.get("map.function.expected"));
        }
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Object> context) {
        Object result;
        try {
            result = ((ScriptBlock) p.function()).invoke(input);
        } catch (CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            context.error(Messages.get("pipeline.object.skipped", e.getMessage(), Where.abbreviate(input)));
            return;
        }
        if (result != null) {
            context.emit(result);
        }
    }
}
