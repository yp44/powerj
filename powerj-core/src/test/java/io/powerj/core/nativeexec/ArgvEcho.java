package io.powerj.core.nativeexec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PROTOTYPE of issue #128 (Windows), test code: the program that {@code WindowsSpawnTest} starts to see what a
 * child receives. The first argument is the file to write; it gets one line {@code [argument]} per other argument, then the
 * variable {@code POWERJ_PROBE}, the variable {@code USERPROFILE} and the current directory (UTF-8, {@code \n}).
 */
public final class ArgvEcho {

    private ArgvEcho() {
    }

    public static void main(String[] args) throws IOException {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            lines.add("[" + args[i] + "]");
        }
        lines.add("POWERJ_PROBE=" + System.getenv("POWERJ_PROBE"));
        lines.add("USERPROFILE=" + System.getenv("USERPROFILE"));
        lines.add("user.dir=" + System.getProperty("user.dir"));
        Files.write(Path.of(args[0]), lines, StandardCharsets.UTF_8);
    }
}
