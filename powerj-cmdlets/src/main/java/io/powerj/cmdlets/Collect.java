package io.powerj.cmdlets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Collected;

/**
 * {@code collect} : rassemble tous les objets reçus en une seule liste (spécification FR-36d), comme
 * {@code Stream.toList()}. La liste est toujours une liste, même vide ou d'un seul élément, et l'étape
 * suivante la reçoit entière ({@link Collected} n'est pas déroulée).
 * <pre>
 * (ls -r | where { f -> !f.dir } | collect).size()
 * ls -r | collect | map { l -> l.stream().sorted((a, b) -> Long.compare(b.size, a.size)).limit(5).toList() }
 * </pre>
 */
@CmdletInfo(name = "collect", category = "Filtres", summary = "Rassemble les objets du pipeline en une seule liste",
        examples = {"(ls -r | collect).size()", "$l = ls | where { f -> f.dir } | collect",
                "ls -r | collect | map { l -> l.stream().sorted((a, b) -> Long.compare(b.size, a.size)).limit(5).toList() }"})
public final class Collect implements Cmdlet<Collect.Params, Object, Collected> {

    /** {@code collect} n'a pas d'option. */
    public record Params() {
    }

    /** Objets reçus, par exécution (le contexte est propre à chaque exécution ; libéré si elle est annulée). */
    private final Map<CmdletContext<?>, List<Object>> received = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void begin(Params p, CmdletContext<Collected> context) {
        received.put(context, new ArrayList<>());
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Collected> context) {
        received.get(context).add(input);
    }

    @Override
    public void end(Params p, CmdletContext<Collected> context) {
        context.emit(new Collected<>(received.remove(context)));
    }
}
