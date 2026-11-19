package io.powerj.cmdlets;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;

/**
 * A reader that does not lose what it decoded when it meets bytes the charset cannot decode (audit v2, R-12).
 * {@code InputStreamReader} throws at once, so the characters decoded in the same call, up to a buffer of text, are lost,
 * and with them the matches of {@code grep} on those lines. This one returns what it decoded and throws the
 * {@link CharacterCodingException} on the next call: the lines before the bad bytes are complete, and the error is
 * the line that holds them. No fallback: the user chooses the charset with {@code --encoding}.
 */
final class DecodingReader extends Reader {

    private final InputStream in;
    private final CharsetDecoder decoder;
    private final ByteBuffer bytes = ByteBuffer.allocate(8192);
    private boolean endOfInput;
    private boolean finished;
    /** The failure found after some characters were returned: thrown by the next call. */
    private CharacterCodingException pending;

    DecodingReader(InputStream in, Charset charset) {
        this.in = in;
        this.decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        bytes.flip(); // empty, ready to be read
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (pending != null) {
            throw pending;
        }
        if (finished) {
            return -1;
        }
        CharBuffer out = CharBuffer.wrap(buffer, offset, length);
        while (true) {
            CoderResult result = decoder.decode(bytes, out, endOfInput);
            if (result.isOverflow()) {
                return out.position() - offset;
            }
            if (result.isError()) {
                try {
                    result.throwException();
                } catch (CharacterCodingException e) {
                    if (out.position() > offset) {
                        pending = e; // after what was decoded
                        return out.position() - offset;
                    }
                    throw e;
                }
            }
            // underflow: the bytes are used up
            if (endOfInput) {
                decoder.flush(out);
                finished = true;
                return out.position() > offset ? out.position() - offset : -1;
            }
            if (out.position() > offset) {
                return out.position() - offset; // what there is, without waiting for more
            }
            fill();
        }
    }

    private void fill() throws IOException {
        bytes.compact();
        int count = in.read(bytes.array(), bytes.position(), bytes.remaining());
        if (count < 0) {
            endOfInput = true;
        } else {
            bytes.position(bytes.position() + count);
        }
        bytes.flip();
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
