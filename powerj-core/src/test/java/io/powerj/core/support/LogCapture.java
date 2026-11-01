package io.powerj.core.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * What a class writes in the log, at every level, for the length of a test. Use it in a {@code try}:
 * <pre>
 * try (var log = new LogCapture(Supervisor.class)) {
 *     ...
 *     assertThat(log.text()).doesNotContain("secret");
 * }
 * </pre>
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level levelBefore;
    private final Handler handler;
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    public LogCapture(Class<?> source) {
        this.logger = Logger.getLogger(source.getName());
        this.levelBefore = logger.getLevel();
        this.handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        handler.setLevel(Level.ALL);
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    public List<LogRecord> records() {
        return List.copyOf(records);
    }

    /**
     * Everything the records say, in one text: each message with its parameters filled in, and the message of the
     * exception it carries and of the causes of that one.
     */
    public String text() {
        var text = new StringBuilder();
        var formatter = new java.util.logging.SimpleFormatter();
        for (LogRecord record : records) {
            text.append(formatter.formatMessage(record)).append('\n');
            for (Throwable t = record.getThrown(); t != null; t = t.getCause()) {
                text.append(t).append('\n');
            }
        }
        return text.toString();
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
        logger.setLevel(levelBefore);
    }
}
