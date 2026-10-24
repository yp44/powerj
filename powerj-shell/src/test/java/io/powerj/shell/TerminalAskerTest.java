package io.powerj.shell;

import static io.powerj.shell.TestTerminal.CTRL_C;
import static io.powerj.shell.TestTerminal.CTRL_D;
import static io.powerj.shell.TestTerminal.ENTER;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class TerminalAskerTest {

    private Optional<Boolean> answer(String keys) throws Exception {
        try (var terminal = new TestTerminal(keys)) {
            terminal.startTyping();
            Optional<Boolean> answer = new TerminalAsker(terminal.terminal()).ask("org.a",
                    List.of("javafx.base", "org.a"));
            assertThat(terminal.screen()).contains(
                    "The module \"org.a\" uses native code (modules: javafx.base, org.a). Load it? [y/N]");
            return answer;
        }
    }

    @Test
    void yesLoadsTheModule() throws Exception {
        assertThat(answer("y" + ENTER)).contains(true);
        assertThat(answer("oui" + ENTER)).contains(true);
    }

    @Test
    void anythingElseRefuses() throws Exception {
        assertThat(answer("n" + ENTER)).contains(false);
        assertThat(answer(ENTER)).contains(false);
        assertThat(answer("peut-etre" + ENTER)).contains(false);
    }

    @Test
    void noAnswerWhenTheUserCancelsOrTheInputEnds() throws Exception {
        assertThat(answer(CTRL_C)).isEmpty();
        assertThat(answer(CTRL_D)).isEmpty();
    }
}
