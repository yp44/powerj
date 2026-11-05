package io.powerj.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;

/** The driver that calls {@code begin}, {@code process} for each object and {@code end}, as the shell does (#98). */
class CmdletDriverTest {

    record NoParams() { }

    /** Writes down every call it receives, and emits what it is given. */
    static final class Recorder implements Cmdlet<NoParams, String, String> {
        final List<String> calls = new ArrayList<>();
        int stopAfter = -1;

        @Override
        public void begin(NoParams params, CmdletContext<String> context) {
            calls.add("begin");
        }

        @Override
        public void process(NoParams params, String input, CmdletContext<String> context) {
            calls.add("process " + input);
            context.emit(input.toUpperCase());
            if (calls.size() - 1 == stopAfter) {
                context.stopInput();
            }
        }

        @Override
        public void end(NoParams params, CmdletContext<String> context) {
            calls.add("end");
        }
    }

    @Test
    void itCallsBeginThenProcessForEachObjectThenEndAndGivesBackWhatWasEmitted() throws Exception {
        var recorder = new Recorder();

        var context = new TestContext<String>();

        List<String> emitted = CmdletDriver.run(recorder, new NoParams(), context, "a", "b");

        assertThat(recorder.calls).containsExactly("begin", "process a", "process b", "end");
        assertThat(emitted).containsExactly("A", "B");
        assertThat(context.objectsSent).isEqualTo(2);
    }

    @Test
    void itWorksWithoutAnyInput() throws Exception {
        var recorder = new Recorder();

        assertThat(CmdletDriver.run(recorder, new NoParams(), new TestContext<>())).isEmpty();
        assertThat(recorder.calls).containsExactly("begin", "end");
    }

    @Test
    void itSendsNoMoreObjectsOnceTheCmdletHasStoppedItsInput() throws Exception {
        var recorder = new Recorder();
        recorder.stopAfter = 1; // stops while processing its first object

        var context = new TestContext<String>();

        List<String> emitted = CmdletDriver.run(recorder, new NoParams(), context, "a", "b", "c");

        assertThat(recorder.calls).containsExactly("begin", "process a", "end");
        assertThat(emitted).containsExactly("A");
        assertThat(context.objectsSent).as("the objects the cmdlet was given").isEqualTo(1);
    }

    @Test
    void itClosesTheStateOfTheExecutionWhenTheCmdletIsDone() throws Exception {
        var closed = new ArrayList<String>();
        var context = new TestContext<String>();
        Cmdlet<NoParams, String, String> cmdlet = new Cmdlet<>() {
            @Override
            public void begin(NoParams params, CmdletContext<String> ctx) {
                ctx.state(Resource.class, () -> new Resource(closed));
            }
        };

        CmdletDriver.run(cmdlet, new NoParams(), context);

        assertThat(closed).containsExactly("closed");
    }

    record Resource(List<String> closed) implements AutoCloseable {
        @Override
        public void close() {
            closed.add("closed");
        }
    }

    @Test
    void anExceptionOfTheCmdletReachesTheTestAsItIs() {
        Cmdlet<NoParams, String, String> failing = new Cmdlet<>() {
            @Override
            public void process(NoParams params, String input, CmdletContext<String> context) throws Exception {
                throw new java.io.IOException("disk " + input);
            }
        };

        assertThatThrownBy(() -> CmdletDriver.run(failing, new NoParams(), new TestContext<>(), "x"))
                .isInstanceOf(java.io.IOException.class).hasMessage("disk x");
    }
}
