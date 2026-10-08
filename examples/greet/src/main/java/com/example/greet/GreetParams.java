package com.example.greet;

import io.powerj.api.Option;

/** Options of {@code greet}. */
public record GreetParams(
        @Option(shortName = 'n', mandatory = true, description = "Nom à saluer") String name,
        @Option(shortName = 'c', description = "Nombre de répétitions") int count) {

    public GreetParams {
        if (count <= 0) {
            count = 1;
        }
    }
}
