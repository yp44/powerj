package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class DecodingReaderTest {

    private static String readAll(byte[] bytes, Charset charset) throws IOException {
        var reader = new DecodingReader(new ByteArrayInputStream(bytes), charset);
        var text = new StringBuilder();
        char[] buffer = new char[7]; // small and odd: characters are decoded across refills
        int count;
        while ((count = reader.read(buffer, 0, buffer.length)) >= 0) {
            text.append(buffer, 0, count);
        }
        return text.toString();
    }

    @Test
    void readsTextInEveryCharset() throws IOException {
        String text = "café 日本語 😀 end\n";

        assertThat(readAll(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).isEqualTo(text);
        assertThat(readAll(text.getBytes(StandardCharsets.UTF_16LE), StandardCharsets.UTF_16LE)).isEqualTo(text);
        assertThat(readAll("café".getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1)).isEqualTo("café");
        assertThat(readAll(new byte[0], StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void returnsWhatItDecodedBeforeBadBytesThenThrows() throws IOException {
        byte[] bytes = {'o', 'k', '\n', (byte) 0xC3, 0x28, '\n'};
        var reader = new DecodingReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8);
        char[] buffer = new char[100];

        assertThat(reader.read(buffer, 0, buffer.length)).isEqualTo(3); // "ok\n" is not lost
        assertThat(new String(buffer, 0, 3)).isEqualTo("ok\n");
        assertThatThrownBy(() -> reader.read(buffer, 0, buffer.length)).isInstanceOf(CharacterCodingException.class);
        assertThatThrownBy(() -> reader.read(buffer, 0, buffer.length)).isInstanceOf(CharacterCodingException.class);
    }

    @Test
    void badBytesAtTheStartThrowAtOnce() {
        var reader = new DecodingReader(new ByteArrayInputStream(new byte[] {(byte) 0xFF}), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader.read(new char[10], 0, 10)).isInstanceOf(CharacterCodingException.class);
    }

    @Test
    void aTruncatedCharacterAtTheEndIsAnError() throws IOException {
        byte[] bytes = {'a', (byte) 0xE2, (byte) 0x82}; // the first two bytes of the euro sign
        var reader = new DecodingReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8);
        char[] buffer = new char[10];

        assertThat(reader.read(buffer, 0, 10)).isEqualTo(1);
        assertThatThrownBy(() -> reader.read(buffer, 0, 10)).isInstanceOf(CharacterCodingException.class);
    }
}
