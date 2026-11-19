package io.powerj.cmdlets;

import java.io.IOException;
import java.io.Reader;
import java.util.concurrent.CancellationException;

/**
 * Reads the lines of a text with a bound on the length of a line, and a check of the interrupt flag at every refill of the
 * buffer (audit v2, S-04): {@code BufferedReader.readLine} grows one {@code StringBuilder} until the line ends, so
 * {@code /dev/zero} or a 2 GB minified file ended the session with an {@code OutOfMemoryError}, and Ctrl+C was not seen
 * inside a long line. A line ends at {@code \n}, {@code \r\n} or {@code \r}, as for {@code readLine}.
 */
final class BoundedLines {

    /** The longest line read as a {@code String}: 64 Mi characters, like a JSON value ({@code JsonSplitter}). */
    static final int MAX_LINE = 64 * 1024 * 1024;

    /** A line longer than the bound. The rest of the line has been read and dropped: the next call gives the next line. */
    static final class TooLong extends IOException {

        private static final long serialVersionUID = 1L;

        TooLong(int max) {
            super("line longer than " + max + " characters");
        }
    }

    private final Reader in;
    private final int max;
    private final char[] buffer = new char[8192];
    private int position;
    private int limit;
    private boolean pendingLineFeed; // a \r ended the last line: a \n that follows belongs to it

    BoundedLines(Reader in, int max) {
        this.in = in;
        this.max = max;
    }

    /**
     * The next line without its terminator, or {@code null} at the end of the text.
     *
     * @throws TooLong               for a line longer than the bound, after reading it to its end
     * @throws CancellationException if the thread is interrupted, checked at every refill of the buffer
     */
    String next() throws IOException {
        StringBuilder line = new StringBuilder();
        boolean started = false; // something of a line was read, so that the end of the text is a line, not the end
        boolean tooLong = false;
        while (true) {
            if (position == limit && !fill()) {
                return started ? finish(line, tooLong) : null;
            }
            if (pendingLineFeed) {
                pendingLineFeed = false;
                if (buffer[position] == '\n') {
                    position++;
                    continue;
                }
            }
            started = true;
            int start = position;
            while (position < limit && buffer[position] != '\n' && buffer[position] != '\r') {
                position++;
            }
            if (!tooLong) {
                if (line.length() + (position - start) > max) {
                    tooLong = true;
                    line = new StringBuilder(0); // what was read is dropped at once
                } else {
                    line.append(buffer, start, position - start);
                }
            }
            if (position < limit) { // a terminator
                pendingLineFeed = buffer[position++] == '\r';
                return finish(line, tooLong);
            }
        }
    }

    private String finish(StringBuilder line, boolean tooLong) throws TooLong {
        if (tooLong) {
            throw new TooLong(max);
        }
        return line.toString();
    }

    private boolean fill() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
        int count = in.read(buffer, 0, buffer.length);
        if (count < 0) {
            position = 0;
            limit = 0;
            return false;
        }
        position = 0;
        limit = count;
        return true;
    }
}
