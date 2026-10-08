package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutableKindTest {

    @TempDir
    Path tmp;

    /** Builds a minimal PE header with the requested subsystem (2 = GUI, 3 = console). */
    private Path pe(String name, int subsystem) throws Exception {
        int peOffset = 0x80;
        ByteBuffer b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        b.put(0, (byte) 'M').put(1, (byte) 'Z');
        b.putInt(0x3C, peOffset);
        b.putInt(peOffset, 0x00004550);
        b.putShort(peOffset + 4 + 20 + 68, (short) subsystem);
        return Files.write(tmp.resolve(name), b.array());
    }

    @Test
    void detectsGuiSubsystem() throws Exception {
        assertThat(ExecutableKind.isWindowsGui(pe("notepad.exe", 2))).isTrue();
        assertThat(ExecutableKind.isWindowsGui(pe("ipconfig.exe", 3))).isFalse();
    }

    @Test
    void nonPeFilesAreNotGui() throws Exception {
        assertThat(ExecutableKind.isWindowsGui(Files.writeString(tmp.resolve("script.cmd"), "@echo off"))).isFalse();
        assertThat(ExecutableKind.isWindowsGui(Files.write(tmp.resolve("tronque.exe"), new byte[] {'M', 'Z'}))).isFalse();
        assertThat(ExecutableKind.isWindowsGui(tmp.resolve("absent.exe"))).isFalse();
    }
}
