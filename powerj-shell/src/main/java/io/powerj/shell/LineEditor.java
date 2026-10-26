package io.powerj.shell;

import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;

/**
 * Where the lines come from: what the read loop asks of the line editor, and nothing else. JLine's reader is one
 * ({@code reader::readLine}); a test is another.
 */
@FunctionalInterface
interface LineEditor {

    /**
     * Reads a line after showing {@code prompt}.
     *
     * @throws UserInterruptException Ctrl+C during the input: the line is dropped
     * @throws EndOfFileException     Ctrl+D on an empty line, or end of input
     * @throws java.io.IOError        the terminal failed
     */
    String readLine(String prompt);
}
