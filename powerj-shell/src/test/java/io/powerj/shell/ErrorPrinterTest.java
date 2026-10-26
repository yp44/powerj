package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;

/** One line per message, in red, prefixed with the file and the line while the profile runs. */
class ErrorPrinterTest {

    private final StringWriter text = new StringWriter();

    private String printed(java.util.function.Consumer<ErrorPrinter> action) throws Exception {
        try (var screen = new TestTerminal("")) {
            var printer = new ErrorPrinter(screen.terminal(), new PrintWriter(text, true));
            action.accept(printer);
        }
        return text.toString().replace(System.lineSeparator(), "\n");
    }

    @Test
    void aMessageIsAnErrorLineInRed() throws Exception {
        String out = printed(printer -> printer.print("unknown command: x"));

        assertThat(out).contains("unknown command: x").contains("\033[31m").endsWith("\n");
    }

    @Test
    void thePrefixComesBeforeTheMessageUntilItIsCleared() throws Exception {
        String out = printed(printer -> {
            printer.prefix("profile.pj:3: ");
            printer.print("boom");
            printer.prefix(null);
            printer.print("after");
        });

        assertThat(out).contains("profile.pj:3: boom").doesNotContain("profile.pj:3: after").contains("after");
    }
}
