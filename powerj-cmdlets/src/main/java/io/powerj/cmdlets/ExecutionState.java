package io.powerj.cmdlets;

import io.powerj.api.CmdletContext;

/** Helpers for the state of an execution ({@link CmdletContext#state}). */
final class ExecutionState {

    private ExecutionState() {
    }

    /**
     * The state that {@code begin} made, for a cmdlet whose {@code begin} does the work of building it (it reads a
     * second source, expands the arguments…): {@code process} and {@code end} find it, they never make it.
     */
    static <S> S begun(CmdletContext<?> context, Class<S> type) {
        return context.state(type, () -> {
            throw new IllegalStateException("begin was not called: no " + type.getSimpleName());
        });
    }
}
