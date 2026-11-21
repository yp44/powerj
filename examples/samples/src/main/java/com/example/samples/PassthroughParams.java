package com.example.samples;

import io.powerj.api.Option;

/** Options of {@code passthrough}: two switches (a {@code boolean} option takes no value). */
public record PassthroughParams(
        @Option(shortName = 'n', description = "Drops the null objects") boolean skipNull,
        @Option(shortName = 'o', description = "Emits an Optional holding each object") boolean optional) {
}
