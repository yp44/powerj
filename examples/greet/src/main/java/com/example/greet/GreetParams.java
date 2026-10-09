package com.example.greet;

import io.powerj.api.Option;

/** Options of {@code greet}. */
public record GreetParams(
        @Option(shortName = 'n', mandatory = true, description = "Name to greet") String name,
        @Option(shortName = 'c', description = "Number of repetitions") int count) {

    public GreetParams {
        if (count <= 0) {
            count = 1;
        }
    }
}
