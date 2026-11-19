package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;

class BoundedLinesTest {

    private static List<String> lines(String text, int max) throws IOException {
        var reader = new BoundedLines(new StringReader(text), max);
        List<String> lines = new ArrayList<>();
        for (String line = reader.next(); line != null; line = reader.next()) {
            lines.add(line);
        }
        return lines;
    }

    @Test
    void linesEndAtLineFeedCarriageReturnOrBoth() throws IOException {
        assertThat(lines("a\nb\r\nc\rd", 100)).containsExactly("a", "b", "c", "d");
        assertThat(lines("a\n\nb\n", 100)).containsExactly("a", "", "b");
        assertThat(lines("a\r\n\r\nb", 100)).containsExactly("a", "", "b");
        assertThat(lines("", 100)).isEmpty();
        assertThat(lines("\n", 100)).containsExactly("");
        assertThat(lines("last", 100)).containsExactly("last");
    }

    @Test
    void aLineFeedAfterACarriageReturnIsTheSameTerminatorAcrossBuffers() throws IOException {
        // the \r is the last character of the first 8192-character buffer, the \n the first of the next
        String text = "x".repeat(8191) + "\r\nnext";

        assertThat(lines(text, 100_000)).containsExactly("x".repeat(8191), "next");
    }

    @Test
    void aLineAcrossBuffersIsOneLine() throws IOException {
        assertThat(lines("y".repeat(20_000) + "\nz", 100_000)).containsExactly("y".repeat(20_000), "z");
    }

    @Test
    void aLineOverTheBoundIsDroppedAndTheNextOneIsRead() throws IOException {
        var reader = new BoundedLines(new StringReader("ok\n" + "x".repeat(30_000) + "\nnext\n"), 100);

        assertThat(reader.next()).isEqualTo("ok");
        assertThatThrownBy(reader::next).isInstanceOf(BoundedLines.TooLong.class).hasMessageContaining("100");
        assertThat(reader.next()).isEqualTo("next");
        assertThat(reader.next()).isNull();
    }

    @Test
    void aLineOfExactlyTheBoundIsKeptAndOneMoreIsNot() throws IOException {
        assertThat(lines("x".repeat(100), 100)).containsExactly("x".repeat(100));
        assertThatThrownBy(() -> lines("x".repeat(101), 100)).isInstanceOf(BoundedLines.TooLong.class);
    }

    @Test
    void theInterruptFlagIsReadAtEveryRefillNotEveryFewThousandLines() throws IOException {
        // one line of 40 000 characters, read 8192 at a time: the flag is set while the third buffer is read
        Reader source = new Reader() {
            private int served;

            @Override
            public int read(char[] buffer, int offset, int length) {
                if (served >= 40_000) {
                    return -1;
                }
                int count = Math.min(length, 40_000 - served);
                java.util.Arrays.fill(buffer, offset, offset + count, 'a');
                served += count;
                if (served > 16_000) {
                    Thread.currentThread().interrupt();
                }
                return count;
            }

            @Override
            public void close() {
            }
        };
        var reader = new BoundedLines(source, 1_000_000);

        try {
            assertThatThrownBy(reader::next).isInstanceOf(CancellationException.class);
        } finally {
            Thread.interrupted(); // leave the thread of the test as it was
        }
    }
}
