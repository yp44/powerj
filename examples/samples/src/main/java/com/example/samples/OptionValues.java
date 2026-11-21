package com.example.samples;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What {@code echo-options} received: one component per option, holding the value the shell bound (the default of the
 * type when the option was not given), and {@code blockValue}, the result of the block that the cmdlet evaluated.
 *
 * @param block      the value of {@code --block} as the shell passed it: a {@code ScriptBlock} for {@code { … }}, the
 *                   value itself for {@code ( … )}
 * @param blockValue what the cmdlet made of it: the result of the block, or the value itself; {@code null} if the
 *                   block failed
 */
public record OptionValues(List<String> words, String text, int count, Integer number, long size, double ratio,
                           float factor, short small, byte tiny, Character letter, boolean verbose, Duration timeout,
                           Path path, Level level, List<String> tags, Object block, Pattern pattern,
                           Object blockValue) {
}
