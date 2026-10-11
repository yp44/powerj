package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileTest {

    @TempDir
    Path tmp;

    @Test
    void missingFileHasNoCommand() throws Exception {
        assertThat(Profile.read(tmp.resolve("profile.pj"))).isEmpty();
    }

    @Test
    void blankLinesAndCommentsAreIgnored() {
        assertThat(Profile.commands(List.of("# comment", "", "  ", "$a = 1", "   # indented", "pwd")))
                .containsExactly(new Profile.Command(4, "$a = 1"), new Profile.Command(6, "pwd"));
    }

    @Test
    void aCommandSpansLinesLikeTheInteractiveContinuation() {
        assertThat(Profile.commands(List.of("$f = { n ->", "  n * 2", "}", "ls |", "  where { $_.dir }", "pwd")))
                .containsExactly(new Profile.Command(1, "$f = { n ->\n  n * 2\n}"),
                        new Profile.Command(4, "ls |\n  where { $_.dir }"), new Profile.Command(6, "pwd"));
    }

    @Test
    void anUnfinishedCommandAtTheEndIsKept() {
        assertThat(Profile.commands(List.of("pwd", "$f = { n ->", "  n")))
                .containsExactly(new Profile.Command(1, "pwd"), new Profile.Command(2, "$f = { n ->\n  n"));
    }

    @Test
    void utf8WithBomIsRead() throws Exception {
        Path file = tmp.resolve("profile.pj");
        Files.writeString(file, "﻿$name = \"Hélène\"\r\npwd\r\n", StandardCharsets.UTF_8);

        assertThat(Profile.read(file))
                .containsExactly(new Profile.Command(1, "$name = \"Hélène\""), new Profile.Command(2, "pwd"));
    }
}
