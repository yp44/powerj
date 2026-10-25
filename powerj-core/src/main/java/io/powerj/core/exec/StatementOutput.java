package io.powerj.core.exec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Destination of a statement's values: the terminal, or the file of a {@code >} redirection.
 * The file is opened only at the first value: a redirected native command writes it itself.
 */
final class StatementOutput implements AutoCloseable {

    /** Width of the tables written to a file: no truncation. */
    private static final int FILE_WIDTH = 10_000;

    private final ShellIo io;
    private final Optional<NativeRunner.FileTarget> target;
    /** Measure of the line, which is told how long closing (the last of the display) took; or {@code null}. */
    private final PerfRecorder perf;
    private OutputFormatter formatter;
    private PrintWriter file;

    StatementOutput(ShellIo io, Optional<NativeRunner.FileTarget> target, PerfRecorder perf) {
        this.io = io;
        this.target = target;
        this.perf = perf;
    }

    void accept(Object value) {
        formatter().accept(value);
    }

    private OutputFormatter formatter() {
        if (formatter == null) {
            if (target.isPresent()) {
                try {
                    var options = target.get().append()
                            ? new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                            : new StandardOpenOption[0];
                    file = new PrintWriter(Files.newBufferedWriter(target.get().file(), StandardCharsets.UTF_8, options));
                } catch (IOException e) {
                    throw new PjException(PjError.of(Messages.get("file.writeFailed", target.get().file(),
                            e.getMessage()), e));
                }
                formatter = new OutputFormatter(file, FILE_WIDTH);
            } else {
                formatter = new OutputFormatter(io.out(), io.width().getAsInt(), io.colors());
            }
        }
        return formatter;
    }

    @Override
    public void close() {
        long start = perf == null ? 0 : System.nanoTime();
        try {
            if (formatter != null) {
                formatter.close();
            }
            if (file != null) {
                file.close();
                if (file.checkError()) {
                    throw new PjException(Messages.get("file.writeFailed.noReason", target.orElseThrow().file()));
                }
            }
        } finally {
            if (perf != null) {
                perf.addDisplay(System.nanoTime() - start);
            }
        }
    }
}
