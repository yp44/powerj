package io.powerj.core.nativeexec;

import java.io.IOException;

/**
 * Issue #128 (Windows), a helper program of {@code WindowsSpawnTest}: writes {@code out} to standard output and {@code err} to standard error,
 * then reads standard input to its end and writes {@code stdin=<number of bytes>}. With the null device as input it ends at
 * once with {@code stdin=0}; with an input that nobody closes it would wait.
 */
public final class StdioEcho {

    private StdioEcho() {
    }

    public static void main(String[] args) throws IOException {
        System.out.println("out");
        System.err.println("err");
        int bytes = System.in.readAllBytes().length;
        System.out.println("stdin=" + bytes);
    }
}
