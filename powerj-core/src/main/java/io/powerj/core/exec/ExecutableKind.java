package io.powerj.core.exec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Detects Windows graphical applications (GUI subsystem of the PE header), which are launched detached
 * (specification FR-39).
 */
public final class ExecutableKind {

    private static final int PE_POINTER_OFFSET = 0x3C;
    private static final int COFF_HEADER_SIZE = 20;
    private static final int SUBSYSTEM_OFFSET_IN_OPTIONAL_HEADER = 68;
    private static final int SUBSYSTEM_WINDOWS_GUI = 2;

    private ExecutableKind() {
    }

    /** {@code true} if the file is a PE executable of the Windows graphical subsystem. */
    public static boolean isWindowsGui(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return isWindowsGui(channel);
        } catch (IOException | RuntimeException _) {
            return false;
        }
    }

    static boolean isWindowsGui(FileChannel channel) throws IOException {
        ByteBuffer dos = read(channel, 0, 64);
        if (dos == null || dos.get(0) != 'M' || dos.get(1) != 'Z') {
            return false;
        }
        long peOffset = Integer.toUnsignedLong(dos.getInt(PE_POINTER_OFFSET));
        ByteBuffer signature = read(channel, peOffset, 4);
        if (signature == null || signature.getInt(0) != 0x00004550) { // "PE\0\0"
            return false;
        }
        long subsystemOffset = peOffset + 4 + COFF_HEADER_SIZE + SUBSYSTEM_OFFSET_IN_OPTIONAL_HEADER;
        ByteBuffer subsystem = read(channel, subsystemOffset, 2);
        return subsystem != null && Short.toUnsignedInt(subsystem.getShort(0)) == SUBSYSTEM_WINDOWS_GUI;
    }

    private static ByteBuffer read(FileChannel channel, long position, int length) throws IOException {
        if (position < 0 || position + length > channel.size()) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) {
                return null;
            }
        }
        return buffer.flip();
    }
}
