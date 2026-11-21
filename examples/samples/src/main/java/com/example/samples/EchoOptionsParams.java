package com.example.samples;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import io.powerj.api.Option;

/**
 * Options of {@code echo-options}: one option of each kind a cmdlet can declare. None is mandatory, so that the cmdlet
 * run alone shows the default of each type ({@code mandatory = true} is shown by the {@code greet} example).
 * <p>
 * Only {@code -t}, {@code -c} and {@code -v} have a short form: {@code -v} keeps the meaning that the specification
 * reserves for it ({@code --verbose}, FR-18b), no other reserved letter is used, and the other options show that a
 * short form is optional.
 */
public record EchoOptionsParams(
        @Option(position = 0, description = "Words that are not options") List<String> words,
        @Option(shortName = 't', description = "Text") String text,
        @Option(shortName = 'c', description = "Integer, 0 when not given") int count,
        @Option(description = "Boxed integer, null when not given") Integer number,
        @Option(description = "Size, with a unit: 10kb, 1.5mb") long size,
        @Option(description = "Decimal number") double ratio,
        @Option(description = "Decimal number, as a float") float factor,
        @Option(description = "Short integer, from -32768 to 32767") short small,
        @Option(description = "Byte, from -128 to 127") byte tiny,
        // Character, not char: an absent char would be '\0', a NUL character written as is in a table or a CSV file
        @Option(description = "Exactly one character") Character letter,
        @Option(shortName = 'v', description = "Switch, without a value") boolean verbose,
        @Option(description = "Duration, with a unit: 250ms, 1.5s, 5m, 2h, 7d") Duration timeout,
        @Option(description = "Path") Path path,
        @Option(description = "Level: low, medium or high") Level level,
        @Option(longName = "tag", description = "Tag, repeatable: --tag a --tag b") List<String> tags,
        @Option(description = "A block { … }, evaluated by the cmdlet, or any value") Object block,
        @Option(description = "A Pattern object: (java.util.regex.Pattern.compile(\"a.*\"))") Pattern pattern) {
}
