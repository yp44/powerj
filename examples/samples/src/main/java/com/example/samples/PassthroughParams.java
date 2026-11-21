package com.example.samples;

import io.powerj.api.Option;

/**
 * Options of {@code passthrough}: two switches (a {@code boolean} option takes no value). {@code --skip-null} is {@code -s},
 * not {@code -n}: {@code -n} is reserved for {@code --dry-run} (specification FR-18b), and a module should keep the
 * meaning that the user knows from the built-in cmdlets.
 */
public record PassthroughParams(
        @Option(shortName = 's', description = "Drops the null objects") boolean skipNull,
        @Option(shortName = 'o', description = "Emits an Optional holding each object") boolean optional) {
}
