package io.powerj.cmdlets;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.ScriptBlock;

/**
 * {@code group}: one {@link Group} per distinct key, emitted when the input ends, in the order the keys first appear
 * (specification FR-71).
 * <pre>
 * ls -r | group ext | sort count -d | first 5
 * ls -r | group ext dir          # key = [ext, dir]
 * ls -r | group ext -n           # counts only
 * </pre>
 */
@CmdletInfo(name = "group", preview = true, category = "Filters", summary = "Groups the pipeline objects by key",
        examples = {"ls -r | group ext | sort count -d | first 5", "ls -r | group ext dir", "ls -r | group ext -n",
                "(ls -r | group ext)[0].items", "cat app.log | group | where { $_.count > 1 }"})
public final class GroupCmdlet implements Cmdlet<GroupCmdlet.Params, Object, Group> {

    /** Parameters of {@code group}. */
    public record Params(
            @Option(position = 0, description = "Grouping keys: property, property path or block { … }; none: the objects themselves")
            List<Object> keys,
            @Option(shortName = 'n', description = "Keeps only the count of each group, not its objects")
            boolean noItems) {
    }

    /** Objects of a group. */
    private static final class Bucket {
        final Object key;
        long count;
        final List<Object> items = new ArrayList<>();

        Bucket(Object key) {
            this.key = key;
        }
    }

    private record State(List<ScriptBlock> keys, Map<Object, Bucket> buckets) {
    }

    private final Map<CmdletContext<?>, State> states = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void begin(Params p, CmdletContext<Group> context) {
        states.put(context, new State(Keys.blocks(p.keys(), context, "group.key.invalid"), new LinkedHashMap<>()));
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Group> context) {
        State state = states.get(context);
        Object key;
        try {
            key = key(state.keys(), input);
        } catch (CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            context.error(Messages.get("pipeline.object.skipped", e.getMessage(), Where.abbreviate(input)));
            return;
        }
        Bucket bucket = state.buckets().computeIfAbsent(normalized(key), k -> new Bucket(key));
        bucket.count++;
        if (!p.noItems()) {
            bucket.items.add(input);
        }
    }

    @Override
    public void end(Params p, CmdletContext<Group> context) {
        State state = states.remove(context);
        for (Bucket bucket : state.buckets().values()) {
            context.emit(new Group(bucket.key, bucket.count, Collections.unmodifiableList(bucket.items)));
        }
    }

    /** Key of an object: the object itself, the value of the single key, or the list of the values of the keys. */
    private static Object key(List<ScriptBlock> keys, Object input) {
        if (keys.isEmpty()) {
            return input;
        }
        if (keys.size() == 1) {
            return keys.getFirst().invoke(input);
        }
        Object[] values = new Object[keys.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = keys.get(i).invoke(input);
        }
        return Collections.unmodifiableList(Arrays.asList(values));
    }

    /** Value compared to find the group: integral numbers as {@code Long} ({@code 1} and {@code 1L} are one group). */
    private static Object normalized(Object key) {
        return switch (key) {
            case Integer i -> (long) i;
            case Short s -> (long) s;
            case Byte b -> (long) b;
            case List<?> list -> {
                List<Object> values = new ArrayList<>(list.size());
                list.forEach(value -> values.add(normalized(value)));
                yield values;
            }
            case null, default -> key;
        };
    }
}
