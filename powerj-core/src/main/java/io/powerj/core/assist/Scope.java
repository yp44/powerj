package io.powerj.core.assist;

import java.util.Map;

import io.powerj.api.Row;

/**
 * Types known in a block: {@code $_} and lambda parameters.
 *
 * @param sample {@link Row} with the columns declared by an upstream cmdlet ({@code select}, FR-69), or {@code null}
 */
record Scope(Class<?> current, Row sample, Map<String, Class<?>> parameters) { }
