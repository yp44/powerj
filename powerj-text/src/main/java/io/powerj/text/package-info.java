/**
 * What {@code core}, {@code cmdlets} and {@code fx} share about the output contract (ADR 0010): the reading of a record
 * ({@code @Display}, {@code @Bytes}, {@code @Highlight}), the formatting of sizes, dates and durations, the parsing of
 * the units of a size, and the text that guards a regular expression.
 * <p>
 * <b>What goes in</b> is what at least two of those modules already wrote a copy of. <b>What stays out</b> is the engine
 * and its state, what only one module uses, the message bundles of the other modules, and any dependency beyond
 * {@code powerj-api} and the JDK (an ArchUnit rule checks it). <b>Not API:</b> no promise of compatibility.
 */
package io.powerj.text;
