package io.powerj.cmdlets;

import java.util.Objects;

/**
 * Session environment variable (specification FR-36b).
 *
 * @param name  name
 * @param value value
 */
public record EnvVar(String name, String value) {

    public EnvVar {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
    }
}
