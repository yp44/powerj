package io.powerj.core.exec;

import java.util.List;

/** Commande interne du shell ({@code cd}, {@code pwd}, {@code which}, {@code exit}, {@code history}…). */
@FunctionalInterface
public interface Builtin {

    /**
     * @param args arguments déjà évalués : mots ({@code String}) ou valeurs d'expressions
     * @return valeurs produites (affichées, ou affectées à une variable)
     */
    List<Object> run(List<Object> args, Session session) throws Exception;
}
