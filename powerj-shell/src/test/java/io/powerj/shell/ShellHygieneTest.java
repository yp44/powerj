package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import io.powerj.core.runtime.PreviewResult;

/** The failures of the shell that nobody sees in a normal session (audit v2, R-20): they are logged or worded, not lost. */
class ShellHygieneTest {

    /** A line that fails to highlight is shown plain and logged at FINE, without what was typed beyond a command name. */
    @Test
    void aHighlightingFailureIsLoggedWithoutTheLine() {
        Logger logger = Logger.getLogger(ShellCompletion.class.getName() + "$Colors");
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        Level before = logger.getLevel();
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        try {
            var highlighter = ShellCompletion.highlighter(name -> {
                throw new IllegalStateException("broken " + name);
            }, _ -> false);

            var shown = highlighter.highlight(null, "boom --password hunter2");

            assertThat(shown.toString()).isEqualTo("boom --password hunter2");
            assertThat(records).singleElement().satisfies(record -> {
                assertThat(record.getLevel()).isEqualTo(Level.FINE);
                assertThat(record.getThrown()).isInstanceOf(IllegalStateException.class);
                assertThat(record.getMessage()).contains("boom").doesNotContain("hunter2");
            });
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(before);
        }
    }

    @Test
    void anErrorOfAPreviewIsWordedAsTheShellWordsItForARunningLine() {
        assertThat(Previewer.failed(new OutOfMemoryError("Java heap space")))
                .isEqualTo(new PreviewResult.Failed("out of memory: filter the data earlier in the pipeline"));
        assertThat(Previewer.failed(new StackOverflowError()))
                .isEqualTo(new PreviewResult.Failed("recursion too deep"));
        assertThat(Previewer.failed(new IllegalStateException("boom")))
                .isEqualTo(new PreviewResult.Failed("internal error: java.lang.IllegalStateException: boom (details in the log)"));
    }
}
