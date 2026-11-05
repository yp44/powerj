package io.powerj.testing;

import java.util.List;

import io.powerj.api.Cmdlet;

/**
 * Runs a cmdlet as the shell does (#98): {@code begin}, {@code process} for each object, {@code end}, then the state of
 * the execution is closed. The objects after the one during which the cmdlet called {@code stopInput()} are not sent,
 * as the runner reads no more once the cmdlet has stopped its input.
 */
public final class CmdletDriver {

    private CmdletDriver() {
    }

    /**
     * Runs {@code cmdlet} on {@code inputs} in {@code context} and gives back what it emitted. An exception of the cmdlet
     * is not wrapped. The test sets {@code context.hasInput} itself: the driver does not guess it.
     */
    @SafeVarargs
    public static <P extends Record, I, O> List<O> run(Cmdlet<P, I, O> cmdlet, P params, TestContext<O> context,
                                                       I... inputs) throws Exception {
        context.objectsSent = 0;
        cmdlet.begin(params, context);
        for (I input : inputs) {
            if (context.stopInputCalls > 0) {
                break;
            }
            context.objectsSent++;
            cmdlet.process(params, input, context);
        }
        cmdlet.end(params, context);
        context.closeStates();
        return context.emitted;
    }
}
