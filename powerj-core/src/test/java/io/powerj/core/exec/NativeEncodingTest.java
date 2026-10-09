package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

class NativeEncodingTest {

    private static final Path GIT = Path.of("C:/Program Files/Git/cmd/git.exe");

    private final NativeEncoding frenchConsole = new NativeEncoding(() -> OptionalInt.of(850));

    @Test
    void automaticUsesTheConsoleCodePage() {
        assertThat(frenchConsole.forProgram(GIT, Map.of())).isEqualTo(Charset.forName("cp850"));
        assertThat(frenchConsole.forProgram(GIT, Map.of("POWERJ_NATIVE_ENCODING", "auto")))
                .isEqualTo(Charset.forName("cp850"));
        assertThat(new NativeEncoding(OptionalInt::empty).forProgram(GIT, Map.of())).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void globalThenProgramSpecificVariable() {
        assertThat(frenchConsole.forProgram(GIT, Map.of("POWERJ_NATIVE_ENCODING", "windows-1252")))
                .isEqualTo(Charset.forName("windows-1252"));
        assertThat(frenchConsole.forProgram(GIT, Map.of(
                "POWERJ_NATIVE_ENCODING", "windows-1252", "POWERJ_NATIVE_ENCODING_GIT", "UTF-8")))
                .isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void invalidCharsetIsReported() {
        assertThatThrownBy(() -> frenchConsole.forProgram(GIT, Map.of("POWERJ_NATIVE_ENCODING_GIT", "klingon")))
                .hasMessage("unknown encoding 'klingon' (variable POWERJ_NATIVE_ENCODING_GIT)");
    }

    @Test
    void programKeyAndCodePages() {
        assertThat(NativeEncoding.programKey(Path.of("my-tool.v2.exe"))).isEqualTo("MY_TOOL_V2");
        assertThat(NativeEncoding.codePageCharset(65001)).isEqualTo(StandardCharsets.UTF_8);
        assertThat(NativeEncoding.codePageCharset(1252)).isEqualTo(Charset.forName("windows-1252"));
    }
}
