package io.powerj.cmdlets;

import java.util.Objects;

/**
 * Variable d'environnement de la session (spécification FR-36b).
 *
 * @param name  nom
 * @param value valeur
 */
public record EnvVar(String name, String value) {

    public EnvVar {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
    }
}
