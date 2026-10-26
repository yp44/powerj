package io.powerj.shell;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Startup profile {@code profile.pj} (specification FR-63): commands run when the interactive shell starts.
 * Blank lines and {@code #} comments are ignored; a command spans several lines with the same rule as the
 * interactive continuation (FR-02). The scripts of the non-interactive mode are read the same way.
 */
final class Profile {

    /**
     * Command of the profile.
     *
     * @param line number of its first line (from 1)
     * @param text its lines joined with {@code \n}
     */
    record Command(int line, String text) { }

    private Profile() {
    }

    /**
     * Reads the commands of the file, in UTF-8 (a leading BOM is ignored).
     *
     * @return no command if the file does not exist
     */
    static List<Command> read(Path file) throws IOException {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException _) {
            return List.of();
        }
        if (content.startsWith("﻿")) {
            content = content.substring(1); // written by Notepad
        }
        return commands(content.lines().toList());
    }

    static List<Command> commands(List<String> lines) {
        List<Command> commands = new ArrayList<>();
        StringBuilder pending = null;
        int start = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (pending == null) {
                if (line.isBlank() || line.strip().startsWith("#")) {
                    continue;
                }
                pending = new StringBuilder(line);
                start = i + 1;
            } else {
                pending.append('\n').append(line);
            }
            if (InputCompleteness.check(pending.toString()) instanceof InputCompleteness.Result.Complete) {
                commands.add(new Command(start, pending.toString()));
                pending = null;
            }
        }
        if (pending != null) {
            commands.add(new Command(start, pending.toString())); // incomplete at the end: its error is reported
        }
        return commands;
    }
}
